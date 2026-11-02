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
import play.api.http.Status.{BAD_GATEWAY, BAD_REQUEST, FORBIDDEN, IM_A_TEAPOT, INTERNAL_SERVER_ERROR, NOT_FOUND, SERVICE_UNAVAILABLE, UNAUTHORIZED}
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.{BodyWritable, InMemoryBody, WSClient}
import support.MockAuthHelper.testBearerToken
import support.{ISpecBase, MockAuthHelper, MockDpsHelper, MockEtmpHelper, MockTaxEnrolmentHelper}
import uk.gov.hmrc.senioraccountingofficerregistration.SignUpTaxEnrolmentErrorIntegrationSpec.*

import java.util.UUID

class SignUpTaxEnrolmentErrorIntegrationSpec extends ISpecBase {

  private val wsClient = app.injector.instanceOf[WSClient]

  private val url = s"$baseUrl/senior-accounting-officer-registration/sign-up"

  private val testSubscriptionId: String = "testSubscriptionId-01234567890"
  private val testCorrelationId: String  = UUID.randomUUID().toString

  private def postSignUp() =
    wsClient
      .url(url)
      .withHttpHeaders(
        HeaderNames.AUTHORIZATION -> testBearerToken,
        "correlationId"           -> testCorrelationId
      )
      .post(signUpBody)
      .futureValue

  "POST /sign-up endpoint" when {
    taxEnrolmentErrorMappings.foreach { case (taxEnrolmentStatus, expectedStatus, expectedReason) =>
      s"tax-enrolments responds with $taxEnrolmentStatus, after ETMP and DPS have succeeded" should {
        s"respond with $expectedStatus status and a $expectedReason error" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpOk(testSubscriptionId)
          MockDpsHelper.mockDpsOk(testSubscriptionId)
          MockTaxEnrolmentHelper.mockTaxEnrolmentFailure(taxEnrolmentStatus)

          val response = postSignUp()

          response.status mustBe expectedStatus
          response.body[String] mustBe s"""{"reason":"$expectedReason"}"""

          MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId)
          MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId)
          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
        }
      }
    }

    "tax-enrolments responds with an error body" should {
      "respond with the mapped error only, without echoing the downstream body" in {
        MockAuthHelper.mockAuthOk()
        MockEtmpHelper.mockEtmpOk(testSubscriptionId)
        MockDpsHelper.mockDpsOk(testSubscriptionId)
        MockTaxEnrolmentHelper.mockTaxEnrolmentFailure(
          BAD_REQUEST,
          """{"code":"INVALID_CREDENTIAL_ID","message":"user 1234 is not known to tax-enrolments"}"""
        )

        val response = postSignUp()

        response.status mustBe INTERNAL_SERVER_ERROR
        response.body[String] mustBe """{"reason":"DOWNSTREAM_SERVICE_MISALIGNMENT"}"""

        MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
      }
    }
  }
}

object SignUpTaxEnrolmentErrorIntegrationSpec {

  /** (tax-enrolments status, expected sign-up status, expected sign-up error reason) */
  val taxEnrolmentErrorMappings: Seq[(Int, Int, String)] = Seq(
    (BAD_REQUEST, INTERNAL_SERVER_ERROR, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
    (UNAUTHORIZED, INTERNAL_SERVER_ERROR, "SERVICE_MISCONFIGURATION"),
    (FORBIDDEN, INTERNAL_SERVER_ERROR, "SERVICE_MISCONFIGURATION"),
    (NOT_FOUND, BAD_GATEWAY, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
    (INTERNAL_SERVER_ERROR, BAD_GATEWAY, "DOWNSTREAM_SERVICE_ERROR"),
    (SERVICE_UNAVAILABLE, BAD_GATEWAY, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
    (IM_A_TEAPOT, BAD_GATEWAY, "DOWNSTREAM_SERVICE_MISALIGNMENT")
  )

  val signUpBody: String =
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

  implicit val stringAsJsonWriter: BodyWritable[String] =
    BodyWritable(str => InMemoryBody(ByteString.fromString(str)), "application/json")
}
