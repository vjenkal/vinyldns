/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.domain.batch

import cats.effect._
import cats.implicits._
import org.joda.time.DateTime
import vinyldns.api.domain.DomainValidations._
import vinyldns.api.domain.ReverseZoneHelpers.ptrIsInZone
import vinyldns.core.domain.auth.AuthPrincipal
import vinyldns.api.domain.batch.BatchChangeInterfaces._
import vinyldns.api.domain.batch.BatchTransformations._
import vinyldns.api.domain.dns.DnsConversions._
import vinyldns.core.domain.record.RecordType._
import vinyldns.core.domain.record.{RecordSet, RecordSetRepository}
import vinyldns.core.domain.zone.ZoneRepository
import vinyldns.api.domain.{RecordAlreadyExists, ZoneDiscoveryError}
import vinyldns.core.domain.batch.{BatchChange, BatchChangeRepository, BatchChangeSummaryList}

class BatchChangeService(
    zoneRepository: ZoneRepository,
    recordSetRepository: RecordSetRepository,
    batchChangeValidations: BatchChangeValidationsAlgebra,
    batchChangeRepo: BatchChangeRepository,
    batchChangeConverter: BatchChangeConverterAlgebra)
    extends BatchChangeServiceAlgebra {

  import batchChangeValidations._

  def applyBatchChange(
      batchChangeInput: BatchChangeInput,
      auth: AuthPrincipal): BatchResult[BatchChange] =
    for {
      _ <- validateBatchChangeInputSize(batchChangeInput).toBatchResult
      inputValidatedSingleChanges = validateInputChanges(batchChangeInput.changes)
      zoneMap <- getZonesForRequest(inputValidatedSingleChanges).toBatchResult
      changesWithZones = zoneDiscovery(inputValidatedSingleChanges, zoneMap)
      recordSets <- getExistingRecordSets(changesWithZones).toBatchResult
      validatedSingleChanges = validateChangesWithContext(changesWithZones, recordSets, auth)
      changeForConversion <- buildResponse(batchChangeInput, validatedSingleChanges, auth).toBatchResult
      conversionResult <- batchChangeConverter.sendBatchForProcessing(
        changeForConversion,
        zoneMap,
        recordSets)
    } yield conversionResult.batchChange

  def getBatchChange(id: String, auth: AuthPrincipal): BatchResult[BatchChange] =
    for {
      batchChange <- getExistingBatchChange(id)
      _ <- canGetBatchChange(batchChange, auth).toBatchResult
    } yield batchChange

  def getExistingBatchChange(id: String): BatchResult[BatchChange] =
    batchChangeRepo
      .getBatchChange(id)
      .map {
        case Some(bc) => Right(bc)
        case None => Left(BatchChangeNotFound(id))
      }
      .toBatchResult

  def getZonesForRequest(changes: ValidatedBatch[ChangeInput]): IO[ExistingZones] = {

    // zone name possibilities for all non-PTR changes
    def getPossibleNonPtrZoneNames(nonPtr: List[ChangeInput]): Set[String] = {
      val apexZoneNames = nonPtr.map(_.inputName)
      val nonApexZoneNames = apexZoneNames.map(getZoneFromNonApexFqdn)
      (apexZoneNames ++ nonApexZoneNames).filterNot(_ == "").toSet
    }

    // ipv4 search will be by filter, NOT by specific name because of classless reverse zone delegation
    def getPtrIpv4ZoneFilters(ipv4ptr: List[ChangeInput]): Set[String] =
      ipv4ptr.flatMap(input => getIPv4NonDelegatedZoneName(input.inputName)).toSet

    // zone name possibilities for ipv6 PTR
    def getPossiblePtrIpv6ZoneNames(ipv6ptr: List[ChangeInput]): Set[String] = {
      // TODO - should move min/max into config at some point. For now, only look for /20 through /64 zones by name
      val zoneCidrSmallest = 64 // largest CIDR means smaller zone
      val zoneCidrLargest = 20

      /*
        Logic here is tricky. Each digit is 4 bits, and there are 128 bits total.
        For a /20 zone, you need to keep 20/4 = 5 bits. That means you should drop (128 - 20)/4 = 27 characters
       */
      val toDropSmallest = (128 - zoneCidrSmallest) / 4
      val toDropLargest = (128 - zoneCidrLargest) / 4

      val ipv6ptrFullReverseNames =
        ipv6ptr.flatMap(input => getIPv6FullReverseName(input.inputName)).toSet
      ipv6ptrFullReverseNames.flatMap { name =>
        (toDropSmallest to toDropLargest).map { index =>
          // times 2 here because there are dots between each nibble in this form
          name.substring(index * 2)
        }
      }
    }

    val (ptr, nonPTR) = changes.getValid.partition(_.typ == PTR)
    val (ipv4ptr, ipv6ptr) = ptr.partition(ch => validateIpv4Address(ch.inputName).isValid)

    val nonPTRZoneNames = getPossibleNonPtrZoneNames(nonPTR)
    val ipv4ptrZoneFilters = getPtrIpv4ZoneFilters(ipv4ptr)
    val ipv6ZoneNames = getPossiblePtrIpv6ZoneNames(ipv6ptr)

    val nonIpv4ZoneLookup = zoneRepository.getZonesByNames(nonPTRZoneNames ++ ipv6ZoneNames)
    val ipv4ZoneLookup = zoneRepository.getZonesByFilters(ipv4ptrZoneFilters)

    for {
      nonIpv4Zones <- nonIpv4ZoneLookup
      ipv4Zones <- ipv4ZoneLookup
    } yield ExistingZones(ipv4Zones ++ nonIpv4Zones)
  }

  def getExistingRecordSets(
      changes: ValidatedBatch[ChangeForValidation]): IO[ExistingRecordSets] = {
    // TODO - this implementation may be problematic with many changes. Need to perf test/later change DB
    val uniqueGets = changes.getValid.map(change => (change.zone.id, change.recordName)).toSet

    val allIO = uniqueGets.map {
      case (zoneId, rsName) => recordSetRepository.getRecordSetsByName(zoneId, rsName)
    }

    val allSeq: IO[List[List[RecordSet]]] = allIO.toList.sequence

    allSeq.map(lst => ExistingRecordSets(lst.flatten))
  }

  def zoneDiscovery(
      changes: ValidatedBatch[ChangeInput],
      zoneMap: ExistingZones): ValidatedBatch[ChangeForValidation] =
    changes.mapValid { change =>
      change.typ match {
        case A | AAAA | TXT | MX => standardZoneDiscovery(change, zoneMap)
        case CNAME => cnameZoneDiscovery(change, zoneMap)
        case PTR if validateIpv4Address(change.inputName).isValid =>
          ptrIpv4ZoneDiscovery(change, zoneMap)
        case PTR if validateIpv6Address(change.inputName).isValid =>
          ptrIpv6ZoneDiscovery(change, zoneMap)
        case _ => ZoneDiscoveryError(change.inputName).invalidNel
      }
    }

  def standardZoneDiscovery(
      change: ChangeInput,
      zoneMap: ExistingZones): SingleValidation[ChangeForValidation] = {
    val nonApexName = getZoneFromNonApexFqdn(change.inputName)
    val apexZone = zoneMap.getByName(change.inputName)
    val nonApexZone = zoneMap.getByName(nonApexName)

    apexZone.orElse(nonApexZone) match {
      case Some(zn) =>
        ChangeForValidation(zn, relativize(change.inputName, zn.name), change).validNel
      case None => ZoneDiscoveryError(change.inputName).invalidNel
    }
  }

  def cnameZoneDiscovery(
      change: ChangeInput,
      zoneMap: ExistingZones): SingleValidation[ChangeForValidation] = {
    val nonApexName = getZoneFromNonApexFqdn(change.inputName)
    val apexZone = zoneMap.getByName(change.inputName)
    val nonApexZone = zoneMap.getByName(nonApexName)

    (apexZone, nonApexZone) match {
      case (None, Some(zn)) =>
        ChangeForValidation(zn, relativize(change.inputName, zn.name), change).validNel
      case (Some(_), _) => RecordAlreadyExists(change.inputName).invalidNel
      case (None, None) => ZoneDiscoveryError(change.inputName).invalidNel
    }
  }

  def ptrIpv4ZoneDiscovery(
      change: ChangeInput,
      zoneMap: ExistingZones): SingleValidation[ChangeForValidation] = {
    val zones = zoneMap.getipv4PTRMatches(change.inputName)

    if (zones.isEmpty)
      ZoneDiscoveryError(change.inputName).invalidNel
    else {
      val recordName = change.inputName.split('.').takeRight(1).mkString
      val validZones = zones.filter(zn => ptrIsInZone(zn, recordName, PTR).isRight)
      val zone =
        if (validZones.size > 1) validZones.find(zn => zn.name.contains("/")).get
        else validZones.head
      ChangeForValidation(zone, recordName, change).validNel
    }
  }

  def ptrIpv6ZoneDiscovery(
      change: ChangeInput,
      zoneMap: ExistingZones): SingleValidation[ChangeForValidation] = {
    val zones = zoneMap.getipv6PTRMatches(change.inputName)

    if (zones.isEmpty)
      ZoneDiscoveryError(change.inputName).invalidNel
    else {
      // the longest ipv6 zone name that matches this record is the zone holding the most limited IP space
      val zoneName = zones.map(_.name).foldLeft("") { (longestName, name) =>
        if (name.length > longestName.length) {
          name
        } else longestName
      }

      val changeForValidation = for {
        zone <- zoneMap.getByName(zoneName)
        recordName <- {
          // remove zone name from fqdn for recordname
          getIPv6FullReverseName(change.inputName).map(_.dropRight(zone.name.length + 1))
        }
      } yield ChangeForValidation(zone, recordName, change).validNel

      changeForValidation.getOrElse(ZoneDiscoveryError(change.inputName).invalidNel)
    }
  }

  def buildResponse(
      batchChangeInput: BatchChangeInput,
      transformed: ValidatedBatch[ChangeForValidation],
      auth: AuthPrincipal): Either[BatchChangeErrorResponse, BatchChange] =
    if (transformed.forall(_.isValid)) {
      val changes = transformed.getValid.map(_.asNewStoredChange)
      BatchChange(
        auth.userId,
        auth.signedInUser.userName,
        batchChangeInput.comments,
        DateTime.now,
        changes).asRight
    } else {
      InvalidBatchChangeResponses(batchChangeInput.changes, transformed).asLeft
    }

  def listBatchChangeSummaries(
      auth: AuthPrincipal,
      startFrom: Option[Int] = None,
      maxItems: Int = 100): BatchResult[BatchChangeSummaryList] = {
    batchChangeRepo.getBatchChangeSummariesByUserId(auth.userId, startFrom, maxItems)
  }.toBatchResult
}
