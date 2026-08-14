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
import support.MockAuthHelper.testBearerToken
import support.{ISpecBase, MockAuthHelper, MockDpsHelper, MockEtmpHelper, MockTaxEnrolmentHelper}
import uk.gov.hmrc.senioraccountingofficerregistration.SignupIntegrationSpec.*

import java.util.UUID

class SignupIntegrationSpec extends ISpecBase {

  private val wsClient = app.injector.instanceOf[WSClient]

  val url = s"$baseUrl/senior-accounting-officer-registration/sign-up"

  val testSubscriptionId: String = "testSubscriptionId-01234567890"
  val testCorrelationId: String  = UUID.randomUUID().toString

  "POST /sign-up endpoint" should {
    "respond with 200 status" in {
      MockAuthHelper.mockAuthOk()
      MockEtmpHelper.mockEtmpOk(testSubscriptionId)
      MockDpsHelper.mockDpsOk(testSubscriptionId)
      MockTaxEnrolmentHelper.mockTaxEnrolmentOk()

      val response =
        wsClient
          .url(url)
          .withHttpHeaders(
            HeaderNames.AUTHORIZATION -> testBearerToken,
            "correlationId"           -> testCorrelationId
          )
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
              |      "status": "valid",
              |      "language": "en-GB"
              |    }
              |  ]
              |}
              |""".stripMargin)
          .futureValue

      response.status mustBe 200
      response.body[String] mustBe s"""{"subscriptionId":"$testSubscriptionId"}"""

      MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId)
      MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId)
      MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
    }
  }
}

object SignupIntegrationSpec {
  implicit val stringAsJsonWriter: BodyWritable[String] =
    BodyWritable(str => InMemoryBody(ByteString.fromString(str)), "application/json")
}
