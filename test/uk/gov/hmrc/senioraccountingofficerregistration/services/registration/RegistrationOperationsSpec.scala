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

package uk.gov.hmrc.senioraccountingofficerregistration.services.registration

import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.*
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep.*

import scala.concurrent.{ExecutionContext, Future}

import java.time.{Clock, Instant, ZoneOffset}

class RegistrationOperationsSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with MockitoSugar
    with TestData {

  given ExecutionContext = ExecutionContext.global

  given HeaderCarrier  = HeaderCarrier()
  private val clock    = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC)
  private val accepted = generateEtmpSuccessResponse(4)
  private val data     = RegistrationData.create(
    "user",
    "correlation",
    generateSignUpRequest(1).copy(idempotencyKey = Some("key")),
    clock.instant(),
    auditData = AuditInputData.create("auditPath")
  )

  private class Fixture {

    val etmp: EtmpSubscriptionConnector = mock[EtmpSubscriptionConnector]
    val dps: DpsConnector               = mock[DpsConnector]
    val tax: TaxEnrolmentsConnector     = mock[TaxEnrolmentsConnector]
    val email: EmailConnector           = mock[EmailConnector]
    val audit: AuditConnector           = mock[AuditConnector]
    val operations                      = new RegistrationOperations(etmp, dps, tax, email, audit, clock)
    val root: RegistrationCommand       = RegistrationCommand.start(data)
    val taxCommand: RegistrationCommand =
      root.copy(step = TaxEnrolments, data = data.copy(etmpResponse = Some(accepted)))
  }

  "Registration operations" should {
    "checkpoint ETMP output and copy it into the DPS command" in {
      val f = new Fixture
      when(f.etmp.signUp(any())(using any()))
        .thenReturn(Future.successful(HttpResponse(201, Json.stringify(Json.toJson(accepted)))))
      val result = f.operations.execute(f.root).futureValue
      result.operationFinished shouldBe true
      result.data.etmpResponse shouldBe Some(accepted)
      result.successors.map(_.step) shouldBe Seq(Dps)
      result.successors.head.data.etmpResponse shouldBe Some(accepted)
    }

    "pass the ETMP ID and registration request to DPS" in {
      val f = new Fixture
      when(f.dps.replaceSaoSubscription(any(), any())(using any())).thenReturn(Future.successful(HttpResponse(201, "")))
      val result = f.operations.execute(f.taxCommand.copy(step = Dps)).futureValue
      verify(f.dps).replaceSaoSubscription(meq(accepted.success.dsaoIdNumber), meq(data.request))(using any())
      result.successors.map(_.step) shouldBe Seq(TaxEnrolments)
    }

    "prepare independent audit and recipient commands after successful tax enrolment" in {
      val f = new Fixture
      when(f.tax.enrol(any())(using any())).thenReturn(Future.successful(HttpResponse(204, "")))
      val result = f.operations.execute(f.taxCommand).futureValue
      verify(f.tax).enrol(meq(TaxEnrolmentRequest(data.request, accepted)))(using any())
      result.data.completedAt shouldBe Some(clock.instant())
      result.successors.count(_.step == Audit) shouldBe 1
      result.successors.count(_.step == Email) shouldBe 2
      result.successors.filter(_.step == Email).foreach { command =>
        command.email.value.parameters("referenceId") shouldBe accepted.success.dsaoIdNumber
        command.email.value.parameters("submittedDateTime") shouldBe "6 October 2026 at 01:00PM"
      }
      result.successors.map(_.commandKey) shouldBe result.successors.map(_.commandKey).distinct
    }

    "reject malformed ETMP successes permanently" in {
      val f = new Fixture
      when(f.etmp.signUp(any())(using any())).thenReturn(Future.successful(HttpResponse(201, "{}")))
      f.operations.execute(f.root).failed.futureValue shouldBe RegistrationFailures.malformed
    }

    "fail clearly when a step is missing its required checkpoint data" in {
      val f       = new Fixture
      val command = f.root.copy(step = Dps)

      val failure = f.operations.execute(command).failed.futureValue
      failure shouldBe a[IllegalStateException]
      failure.getMessage shouldBe "Missing required registration field: etmpResponse"
    }

    "fail clearly when an email command has no email payload" in {
      val f       = new Fixture
      val command = f.root.copy(step = Email)

      val failure = f.operations.execute(command).failed.futureValue
      failure shouldBe a[IllegalStateException]
      failure.getMessage shouldBe "Missing required registration field: email"
    }

    "retain the legacy response mapping and classify transient responses" in {
      Seq(408, 429, 500, 502, 503, 504, 599).foreach(status =>
        RegistrationFailures.response(status).retriable shouldBe true
      )
      Seq(400, 401, 403, 404, 409, 422).foreach(status =>
        RegistrationFailures.response(status).retriable shouldBe false
      )
      RegistrationFailures.response(400).failure shouldBe RegistrationFailure(
        httpStatus = 500,
        reason = Reason.DOWNSTREAM_SERVICE_MISALIGNMENT
      )
      RegistrationFailures.response(401).failure shouldBe RegistrationFailure(
        httpStatus = 500,
        reason = Reason.SERVICE_MISCONFIGURATION
      )
      RegistrationFailures.response(503).failure shouldBe RegistrationFailure(
        httpStatus = 502,
        reason = Reason.DOWNSTREAM_SERVICE_UNAVAILABLE
      )
    }

    "classify exceptions raised by the HTTP client using their upstream status" in {
      val f = new Fixture
      when(f.tax.enrol(any())(using any())).thenReturn(Future.failed(UpstreamErrorResponse("unauthorized", 401)))
      f.operations.execute(f.taxCommand).failed.futureValue shouldBe RegistrationFailures.response(401)
    }

    "detect email failures rather than swallowing them" in {
      val f       = new Fixture
      val command = f.taxCommand
        .copy(data = f.taxCommand.data.copy(completedAt = Some(clock.instant())))
        .successors
        .find(_.step == Email)
        .value
      when(f.email.postEmail(any())(using any())).thenReturn(Future.successful(HttpResponse(503, "")))
      f.operations.execute(command).failed.futureValue shouldBe RegistrationFailures.response(503)
    }

    "reuse a stable audit event identity and surface delivery failures" in {
      val f       = new Fixture
      val command = f.taxCommand
        .copy(data = f.taxCommand.data.copy(completedAt = Some(clock.instant())))
        .successors
        .find(_.step == Audit)
        .value
      when(f.audit.sendEvent(any())(using any(), any()))
        .thenReturn(Future.successful(AuditResult.Failure("unavailable")))
      f.operations.execute(command).failed.futureValue shouldBe OperationRejected(
        RegistrationFailures.unavailable,
        true
      )
      val event = org.mockito.ArgumentCaptor.forClass(classOf[DataEvent])
      verify(f.audit).sendEvent(event.capture())(using any(), any())
      event.getValue.eventId shouldBe data.orchestrationId
      event.getValue.generatedAt shouldBe clock.instant()
      event.getValue.auditType shouldBe "SubscriptionSubmitted"
    }
  }
}
