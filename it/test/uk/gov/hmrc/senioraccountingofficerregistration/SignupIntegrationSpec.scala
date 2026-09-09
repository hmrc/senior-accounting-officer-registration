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
import scala.annotation.tailrec
import scala.util.Random

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
          .post(validRequestBodyAsString)
          .futureValue

      response.status mustBe 200
      response.body[String] mustBe s"""{"subscriptionId":"$testSubscriptionId"}"""

      MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId)
      MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId)
      MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
    }

    "respond with 500 status" when {

      for (etmpStatus, (serviceResponseStatus, serviceResponseReason)) <- Map(
          400                        -> (500, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
          401                        -> (500, "SERVICE_MISCONFIGURATION"),
          403                        -> (500, "SERVICE_MISCONFIGURATION"),
          500                        -> (502, "DOWNSTREAM_SERVICE_ERROR"),
          503                        -> (502, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
          randomUnexpectedStatusCode -> (502, "DOWNSTREAM_SERVICE_MISALIGNMENT")
        )
      do {
        s"ETMP returns a $etmpStatus with reason=$serviceResponseReason" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpFailure(testSubscriptionId, etmpStatus)
          MockDpsHelper.mockDpsOk(testSubscriptionId)
          MockTaxEnrolmentHelper.mockTaxEnrolmentOk()

          val response =
            wsClient
              .url(url)
              .withHttpHeaders(
                HeaderNames.AUTHORIZATION -> testBearerToken,
                "correlationId"           -> testCorrelationId
              )
              .post(validRequestBodyAsString)
              .futureValue

          response.status mustBe serviceResponseStatus
          response.body[String] mustBe s"""{"reason":"$serviceResponseReason"}"""

          MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId, 1)
          MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId, 0)
          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId, 0)
        }
      }

      for (dpsStatus, (serviceResponseStatus, serviceResponseReason)) <- Map(
          400                        -> (500, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
          401                        -> (500, "SERVICE_MISCONFIGURATION"),
          403                        -> (500, "SERVICE_MISCONFIGURATION"),
          500                        -> (502, "DOWNSTREAM_SERVICE_ERROR"),
          503                        -> (502, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
          randomUnexpectedStatusCode -> (502, "DOWNSTREAM_SERVICE_MISALIGNMENT")
        )
      do {
        s"DPS returns a $dpsStatus" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpOk(testSubscriptionId)
          MockDpsHelper.mockDpsFailure(testSubscriptionId, dpsStatus)
          MockTaxEnrolmentHelper.mockTaxEnrolmentOk()

          val response =
            wsClient
              .url(url)
              .withHttpHeaders(
                HeaderNames.AUTHORIZATION -> testBearerToken,
                "correlationId"           -> testCorrelationId
              )
              .post(validRequestBodyAsString)
              .futureValue

          response.status mustBe serviceResponseStatus
          response.body[String] mustBe s"""{"reason":"$serviceResponseReason"}"""

          MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId)
          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId, 0)
        }
      }

      for (taxEnrolmentStatus, (serviceResponseStatus, serviceResponseReason)) <- Map(
          400                        -> (500, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
          401                        -> (500, "SERVICE_MISCONFIGURATION"),
          403                        -> (500, "SERVICE_MISCONFIGURATION"),
          500                        -> (502, "DOWNSTREAM_SERVICE_ERROR"),
          503                        -> (502, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
          randomUnexpectedStatusCode -> (502, "DOWNSTREAM_SERVICE_MISALIGNMENT")
        )
      do {
        s"Tax enrolment returns a $taxEnrolmentStatus" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpOk(testSubscriptionId)
          MockDpsHelper.mockDpsOk(testSubscriptionId)
          MockTaxEnrolmentHelper.mockTaxEnrolmentFailure(taxEnrolmentStatus)

          val response =
            wsClient
              .url(url)
              .withHttpHeaders(
                HeaderNames.AUTHORIZATION -> testBearerToken,
                "correlationId"           -> testCorrelationId
              )
              .post(validRequestBodyAsString)
              .futureValue

          response.status mustBe serviceResponseStatus
          response.body[String] mustBe s"""{"reason":"$serviceResponseReason"}"""

          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
        }
      }
    }
  }

}

object SignupIntegrationSpec {
  implicit val stringAsJsonWriter: BodyWritable[String] =
    BodyWritable(str => InMemoryBody(ByteString.fromString(str)), "application/json")

  val validRequestBodyAsString =
    """{
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
      |""".stripMargin

  private val expectedStatusCodes: Set[Int] = Set(
    200, 204, 400, 401, 403, 500, 503
  )

  @tailrec
  def randomUnexpectedStatusCode: Int =
    Some(Random.nextInt(700))
      .filterNot(expectedStatusCodes.contains) match {
      case Some(unexpectedStatusCode) => unexpectedStatusCode
      case _                          => randomUnexpectedStatusCode
    }
}
