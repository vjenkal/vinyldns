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

package vinyldns.api

import akka.actor._
import akka.testkit.TestEvent.Mute
import akka.testkit.{EventFilter, TestKit}
import akka.util.Timeout
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.effect._
import cats.implicits._
import cats.scalatest.ValidatedMatchers
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec, Suite}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

final case class TimeoutException(message: String) extends Throwable(message)

trait ResultHelpers {

  implicit val baseTimeout: Timeout = new Timeout(2.seconds)

  def await[T](f: => IO[_], duration: FiniteDuration = 1.second): T =
    awaitResultOf[T](f.map(_.asInstanceOf[T]).attempt, duration).toOption.get

  // Waits for the future to complete, then returns the value as an Either[Throwable, T]
  def awaitResultOf[T](
      f: => IO[Either[Throwable, T]],
      duration: FiniteDuration = 1.second): Either[Throwable, T] = {

    val timeOut = IO.sleep(duration) *> IO(
      TimeoutException("Timed out waiting for result").asInstanceOf[Throwable])

    IO.race(timeOut, f.handleError(e => Left(e))).unsafeRunSync() match {
      case Left(e) => Left(e)
      case Right(ok) => ok
    }
  }

  // Assumes that the result of the future operation will be successful, this will fail on a left disjunction
  def rightResultOf[T](f: => IO[Either[Throwable, T]], duration: FiniteDuration = 1.second): T =
    awaitResultOf[T](f, duration) match {
      case Right(result) => result
      case Left(error) => throw error
    }

  // Assumes that the result of the future operation will fail, this will error on a right disjunction
  def leftResultOf[T](
      f: => IO[Either[Throwable, T]],
      duration: FiniteDuration = 1.second): Throwable = awaitResultOf(f, duration).swap.toOption.get

  def leftValue[T](t: Either[Throwable, T]): Throwable = t.swap.toOption.get

  def rightValue[T](t: Either[Throwable, T]): T = t.toOption.get
}

object ValidationTestImprovements extends PropSpec with Matchers with ValidatedMatchers {

  implicit class ValidatedNelTestImprovements[DomainValidationError, A](
      value: ValidatedNel[DomainValidationError, A]) {

    def failures: List[DomainValidationError] = value match {
      case Invalid(e) => e.toList
      case Valid(_) =>
        fail("should have no failures!") // Will (correctly) cause expected failures to fail upon succeeding
    }

    def failWith[EE <: DomainValidationError](implicit tag: ClassTag[EE]): Unit =
      value.failures.map(_ shouldBe an[EE])
  }
}

class AkkaTestJawn
    extends TestKit(ActorSystem("vinyldns", VinylDNSConfig.config))
    with Suite
    with BeforeAndAfterAll
    with ResultHelpers {

  system.eventStream.publish(Mute(EventFilter.info(), EventFilter.debug(), EventFilter.warning()))

  override def afterAll(): Unit = system.terminate()
}
