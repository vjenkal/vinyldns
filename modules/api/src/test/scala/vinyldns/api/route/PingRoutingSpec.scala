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

package vinyldns.api.route

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{Matchers, WordSpec}

class PingRoutingSpec
    extends WordSpec
    with ScalatestRouteTest
    with Directives
    with PingRoute
    with Matchers {

  def actorRefFactory: ActorSystem = system

  "GET ping" should {
    "return PONG" in {
      Get("/ping") ~> pingRoute ~> check {
        response.status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "PONG"
      }
    }
  }
}