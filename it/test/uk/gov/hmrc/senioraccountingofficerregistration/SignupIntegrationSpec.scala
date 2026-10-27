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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.ArgumentCaptor
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.bind
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import scala.concurrent.Future

import play.api.http.HeaderNames
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.WSClient
import support.MockAuthHelper.testBearerToken
import support.SignUpFixtures.*
import support.{ISpecBase, MockAuthHelper, MockDpsHelper, MockEtmpHelper, MockTaxEnrolmentHelper}

import java.util.UUID

class SignupIntegrationSpec extends ISpecBase with MockitoSugar {

  private lazy val audit = mock[AuditConnector]
  override protected def applicationBuilder =
    super.applicationBuilder.overrides(bind[AuditConnector].toInstance(audit))

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(audit)
    when(audit.sendEvent(any())(using any(), any())).thenReturn(Future.successful(AuditResult.Success))
  }


  private val wsClient = app.injector.instanceOf[WSClient]

  val signUpUrl = s"$baseUrl/senior-accounting-officer-registration/sign-up"

  val testSubscriptionId: String = "testSubscriptionId-01234567890"
  val testCorrelationId: String  = UUID.randomUUID().toString

  "POST /sign-up endpoint" should {
    for (delivery <- Seq("success", "disabled", "rejected", "failed", "throws", "pending")) {
    s"respond with 200 status when audit delivery is $delivery" in {
      delivery match {
        case "disabled" => when(audit.sendEvent(any())(using any(), any())).thenReturn(Future.successful(AuditResult.Disabled))
        case "rejected" => when(audit.sendEvent(any())(using any(), any())).thenReturn(Future.successful(AuditResult.Failure("unavailable")))
        case "failed" => when(audit.sendEvent(any())(using any(), any())).thenReturn(Future.failed(new IllegalStateException("unavailable")))
        case "throws" => when(audit.sendEvent(any())(using any(), any())).thenThrow(new IllegalStateException("unavailable"))
        case "pending" => when(audit.sendEvent(any())(using any(), any())).thenReturn(scala.concurrent.Promise[AuditResult]().future)
        case _ => ()
      }
      MockAuthHelper.mockAuthOk()
      MockEtmpHelper.mockEtmpOk(testSubscriptionId)
      MockDpsHelper.mockDpsOk(testSubscriptionId)
      MockTaxEnrolmentHelper.mockTaxEnrolmentOk()

      val response =
        wsClient
          .url(signUpUrl)
          .withHttpHeaders(
            HeaderNames.AUTHORIZATION -> testBearerToken,
            "correlationId"           -> testCorrelationId
          )
          .post(validRequestBodyAsString)
          .futureValue

      response.status mustBe 200
      response.body[String] mustBe s"""{"subscriptionId":"$testSubscriptionId"}"""

      val event = ArgumentCaptor.forClass(classOf[DataEvent])
      verify(audit).sendEvent(event.capture())(using any(), any())
      event.getValue.auditType mustBe "SubscriptionSubmitted"
      event.getValue.detail("etmpSubscriptionId") mustBe testSubscriptionId
      event.getValue.tags("path") mustBe "/senior-accounting-officer-registration/sign-up"
      MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId)
      MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId)
      MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
    }

    }

    "respond with 500 status" when {

      for (etmpStatus, (serviceResponseStatus, serviceResponseReason)) <- Map(
          400 -> (500, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
          401 -> (500, "SERVICE_MISCONFIGURATION"),
          403 -> (500, "SERVICE_MISCONFIGURATION"),
          500 -> (502, "DOWNSTREAM_SERVICE_ERROR"),
          503 -> (502, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
          418 -> (502, "DOWNSTREAM_SERVICE_MISALIGNMENT")
        )
      do {
        s"ETMP returns a $etmpStatus with reason=$serviceResponseReason" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpFailure(testSubscriptionId, etmpStatus)
          MockDpsHelper.mockDpsOk(testSubscriptionId)
          MockTaxEnrolmentHelper.mockTaxEnrolmentOk()

          val response =
            wsClient
              .url(signUpUrl)
              .withHttpHeaders(
                HeaderNames.AUTHORIZATION -> testBearerToken,
                "correlationId"           -> testCorrelationId
              )
              .post(validRequestBodyAsString)
              .futureValue

          verify(audit, never()).sendEvent(any())(using any(), any())
          response.status mustBe serviceResponseStatus
          response.body[String] mustBe s"""{"reason":"$serviceResponseReason"}"""

          MockEtmpHelper.verifyEtmpWasCalled(testCorrelationId, 1)
          MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId, 0)
          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId, 0)
        }
      }

      for (dpsStatus, (serviceResponseStatus, serviceResponseReason)) <- Map(
          400 -> (500, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
          401 -> (500, "SERVICE_MISCONFIGURATION"),
          403 -> (500, "SERVICE_MISCONFIGURATION"),
          500 -> (502, "DOWNSTREAM_SERVICE_ERROR"),
          503 -> (502, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
          418 -> (502, "DOWNSTREAM_SERVICE_MISALIGNMENT")
        )
      do {
        s"DPS returns a $dpsStatus" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpOk(testSubscriptionId)
          MockDpsHelper.mockDpsFailure(testSubscriptionId, dpsStatus)
          MockTaxEnrolmentHelper.mockTaxEnrolmentOk()

          val response =
            wsClient
              .url(signUpUrl)
              .withHttpHeaders(
                HeaderNames.AUTHORIZATION -> testBearerToken,
                "correlationId"           -> testCorrelationId
              )
              .post(validRequestBodyAsString)
              .futureValue

          verify(audit, never()).sendEvent(any())(using any(), any())
          response.status mustBe serviceResponseStatus
          response.body[String] mustBe s"""{"reason":"$serviceResponseReason"}"""

          MockDpsHelper.verifyDpsWasCalled(testSubscriptionId, testCorrelationId)
          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId, 0)
        }
      }

      for (taxEnrolmentStatus, (serviceResponseStatus, serviceResponseReason)) <- Map(
          400 -> (500, "DOWNSTREAM_SERVICE_MISALIGNMENT"),
          401 -> (500, "SERVICE_MISCONFIGURATION"),
          403 -> (500, "SERVICE_MISCONFIGURATION"),
          500 -> (502, "DOWNSTREAM_SERVICE_ERROR"),
          503 -> (502, "DOWNSTREAM_SERVICE_UNAVAILABLE"),
          418 -> (502, "DOWNSTREAM_SERVICE_MISALIGNMENT")
        )
      do {
        s"Tax enrolment returns a $taxEnrolmentStatus" in {
          MockAuthHelper.mockAuthOk()
          MockEtmpHelper.mockEtmpOk(testSubscriptionId)
          MockDpsHelper.mockDpsOk(testSubscriptionId)
          MockTaxEnrolmentHelper.mockTaxEnrolmentFailure(taxEnrolmentStatus)

          val response =
            wsClient
              .url(signUpUrl)
              .withHttpHeaders(
                HeaderNames.AUTHORIZATION -> testBearerToken,
                "correlationId"           -> testCorrelationId
              )
              .post(validRequestBodyAsString)
              .futureValue

          verify(audit, never()).sendEvent(any())(using any(), any())
          response.status mustBe serviceResponseStatus
          response.body[String] mustBe s"""{"reason":"$serviceResponseReason"}"""

          MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(testCorrelationId)
        }
      }
    }
  }

}
