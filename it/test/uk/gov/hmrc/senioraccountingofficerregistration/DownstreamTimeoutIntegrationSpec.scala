/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.senioraccountingofficerregistration

import org.apache.pekko.util.ByteString
import play.api.http.HeaderNames
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.{BodyWritable, InMemoryBody, WSClient}
import play.api.{Configuration, Environment}
import support.MockAuthHelper.testBearerToken
import support.{ISpecBase, MockAuthHelper, MockEtmpHelper}
import uk.gov.hmrc.senioraccountingofficerregistration.DownstreamTimeoutIntegrationSpec.*

import scala.concurrent.duration.*

import java.util.UUID

class DownstreamTimeoutIntegrationSpec extends ISpecBase {

  private val wsClient = app.injector.instanceOf[WSClient]

  private val url = s"$baseUrl/senior-accounting-officer-registration/sign-up"

  private val testCorrelationId: String = UUID.randomUUID().toString

  override def additionalConfigs: Map[String, Any] =
    Map("play.ws.timeout.request" -> testRequestTimeout.toString)

  "the downstream request timeout" should {
    "be 20 seconds, with a 6 second connection timeout, as inherited from bootstrap's backend.conf" in {
      val config = Configuration.load(Environment.simple())

      config.get[Duration]("play.ws.timeout.request") mustBe 20.seconds
      config.get[Duration]("play.ws.timeout.connection") mustBe 6.seconds
    }
  }

  "POST /sign-up endpoint" should {
    "respond with 502 status when a downstream service does not respond within the request timeout" in {
      MockAuthHelper.mockAuthOk()
      MockEtmpHelper.mockEtmpSlow(downstreamDelay.toMillis.toInt)

      val response =
        wsClient
          .url(url)
          .withHttpHeaders(
            HeaderNames.AUTHORIZATION -> testBearerToken,
            "correlationId"           -> testCorrelationId
          )
          .withRequestTimeout(downstreamDelay)
          .post("""{
              |  "etmpSafeId": "1234567890",
              |  "nominatedCompany": {
              |    "name": "Test Company Ltd PLC",
              |    "utr": "2233445567",
              |    "crn": "11223344"
              |  },
              |  "contacts": [
              |    {
              |      "name": "Jane Doe",
              |      "email": "jane.doe@example.com",
              |      "status": "Active",
              |      "language": "en-GB"
              |    }
              |  ]
              |}
              |""".stripMargin)
          .futureValue

      response.status mustBe 502
      response.body[String] mustBe """{"reason":"DOWNSTREAM_SERVICE_UNAVAILABLE"}"""

      MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId)
    }
  }
}

object DownstreamTimeoutIntegrationSpec {
  val testRequestTimeout: FiniteDuration = 500.millis
  val downstreamDelay: FiniteDuration    = 5.seconds

  implicit val stringAsJsonWriter: BodyWritable[String] =
    BodyWritable(str => InMemoryBody(ByteString.fromString(str)), "application/json")
}
