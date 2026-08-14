/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.senioraccountingofficerregistration.services

import org.mockito.ArgumentMatchers.any as anyArg
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status
import play.api.libs.json.Json
import uk.gov.hmrc.http.{GatewayTimeoutException, HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.{
  DpsConnector,
  EtmpSubscriptionConnector,
  TaxEnrolmentsConnector
}
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{DownstreamService, Outcome, SignUpResult}

import scala.concurrent.{ExecutionContext, Future}

class SignUpServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar with TestData {

  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()

  private val signUpRequest       = generateSignUpRequest(seed = 1)
  private val etmpSuccessResponse = generateEtmpSuccessResponse(seed = 4)
  private val subscriptionId      = etmpSuccessResponse.success.dsaoIdNumber

  private val etmpCreated = HttpResponse(Status.CREATED, Json.stringify(Json.toJson(etmpSuccessResponse)))

  private final case class Fixture(
      etmpConnector: EtmpSubscriptionConnector,
      dpsConnector: DpsConnector,
      taxEnrolmentsConnector: TaxEnrolmentsConnector,
      emailService: EmailService,
      service: SignUpService
  )

  private def connectors(): Fixture = {
    val etmpConnector          = mock[EtmpSubscriptionConnector]
    val taxEnrolmentsConnector = mock[TaxEnrolmentsConnector]
    val dpsConnector           = mock[DpsConnector]
    val emailService           = mock[EmailService]
    val service                = SignUpService(etmpConnector, taxEnrolmentsConnector, dpsConnector, emailService)

    when(
      emailService.sendEmail(
        anyArg[EmailTemplate],
        anyArg[SignUpRequest],
        anyArg[EtmpSuccessResponse]
      )(using anyArg[HeaderCarrier])
    ).thenReturn(Future.successful(()))

    Fixture(etmpConnector, dpsConnector, taxEnrolmentsConnector, emailService, service)
  }

  private def stubEtmp(etmpConnector: EtmpSubscriptionConnector, response: HttpResponse): Unit =
    when(etmpConnector.signUp(anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
      .thenReturn(Future.successful(response))

  private def stubDps(dpsConnector: DpsConnector, response: HttpResponse): Unit =
    when(dpsConnector.replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
      .thenReturn(Future.successful(response))

  private def stubTaxEnrolments(taxEnrolmentsConnector: TaxEnrolmentsConnector, response: HttpResponse): Unit =
    when(taxEnrolmentsConnector.enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier]))
      .thenReturn(Future.successful(response))

  private def etmpErrors(code: String, dsaoIdNumber: Option[String] = None): HttpResponse =
    HttpResponse(
      Status.UNPROCESSABLE_ENTITY,
      Json.stringify(
        Json.toJson(EtmpErrorResponse(EtmpErrors("2026-01-31T10:26:17Z", code, s"text for $code", dsaoIdNumber)))
      )
    )

  "signUp" should {
    "call tax-enrolments with DSAO known facts after ETMP and DPS succeed, then return Success" in {
      val fixture         = connectors()
      val enrolmentCaptor = ArgumentCaptor.forClass(classOf[TaxEnrolmentRequest])

      stubEtmp(fixture.etmpConnector, etmpCreated)
      stubDps(fixture.dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(fixture.taxEnrolmentsConnector, HttpResponse(Status.NO_CONTENT, ""))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Success(subscriptionId)

      verify(fixture.etmpConnector).signUp(ArgumentMatchers.eq(signUpRequest))(using anyArg[HeaderCarrier])
      verify(fixture.dpsConnector).replaceSaoSubscription(
        ArgumentMatchers.eq(subscriptionId),
        ArgumentMatchers.eq(signUpRequest)
      )(using anyArg[HeaderCarrier])
      verify(fixture.taxEnrolmentsConnector).enrol(enrolmentCaptor.capture())(using anyArg[HeaderCarrier])
      enrolmentCaptor.getValue shouldBe
        TaxEnrolmentRequest(
          identifiers = Seq(TaxEnrolmentKnownFact("EtmpSubscriptionId", subscriptionId)),
          verifiers = Seq(
            TaxEnrolmentKnownFact("CTUTR", signUpRequest.nominatedCompany.utr.value),
            TaxEnrolmentKnownFact("CRN", signUpRequest.nominatedCompany.crn.value)
          )
        )
      verify(fixture.emailService).sendEmail(
        ArgumentMatchers.eq(EmailTemplate.RegistrationConfirmation),
        ArgumentMatchers.eq(signUpRequest),
        ArgumentMatchers.eq(etmpSuccessResponse)
      )(using anyArg[HeaderCarrier])
    }

    "return MalformedResponse(ETMP) and not call DPS when ETMP returns 201 with an unparsable body" in {
      val fixture = connectors()
      stubEtmp(fixture.etmpConnector, HttpResponse(Status.CREATED, "not json"))

      fixture.service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.ETMP, Outcome.MalformedResponse, "status=201 unparsable response body")

      verify(fixture.dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
      verifyNoInteractions(fixture.emailService)
    }

    "return DownstreamError(ETMP) with the logged error detail and not call DPS when ETMP returns 500" in {
      val fixture = connectors()
      val body    = Json.stringify(
        Json.toJson(
          EtmpSystemError("HoD", EtmpSystemErrorResponse(EtmpSystemErrorDetail("500", "a message", "89505FF7")))
        )
      )
      stubEtmp(fixture.etmpConnector, HttpResponse(Status.INTERNAL_SERVER_ERROR, body))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.DownstreamError,
        "status=500 origin=HoD code=500 logID=89505FF7"
      )

      verify(fixture.dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
      verifyNoInteractions(fixture.emailService)
    }

    "return Unauthorised(ETMP) when ETMP returns 401" in {
      val fixture = connectors()
      stubEtmp(fixture.etmpConnector, HttpResponse(Status.UNAUTHORIZED, ""))

      fixture.service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.ETMP, Outcome.Unauthorised, "status=401")
      verifyNoInteractions(fixture.emailService)
    }

    "return Misalignment(ETMP) for an unmapped ETMP status" in {
      val fixture = connectors()
      stubEtmp(fixture.etmpConnector, HttpResponse(Status.IM_A_TEAPOT, ""))

      fixture.service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.ETMP, Outcome.Misalignment, "status=418")
      verifyNoInteractions(fixture.emailService)
    }

    "return Unavailable(ETMP) when the ETMP call does not complete" in {
      val fixture = connectors()
      when(fixture.etmpConnector.signUp(anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.failed(GatewayTimeoutException("timed out")))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.Unavailable,
        "unreachable: GatewayTimeoutException"
      )
      verifyNoInteractions(fixture.emailService)
    }

    "return AlreadySubscribed, and continue with the returned dsaoIdNumber, when ETMP returns 422 with code 002" in {
      val fixture             = connectors()
      val alreadySubscribedId = "XB0000493000308"

      stubEtmp(fixture.etmpConnector, etmpErrors(EtmpErrors.AlreadySubscribed, Some(alreadySubscribedId)))
      stubDps(fixture.dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(fixture.taxEnrolmentsConnector, HttpResponse(Status.NO_CONTENT, ""))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.AlreadySubscribed(
        alreadySubscribedId,
        "status=422 code=002 - continuing registration with dsaoIdNumber"
      )

      verify(fixture.dpsConnector).replaceSaoSubscription(
        ArgumentMatchers.eq(alreadySubscribedId),
        ArgumentMatchers.eq(signUpRequest)
      )(using anyArg[HeaderCarrier])
      verify(fixture.emailService).sendEmail(
        ArgumentMatchers.eq(EmailTemplate.RegistrationConfirmation),
        ArgumentMatchers.eq(signUpRequest),
        ArgumentMatchers.eq(EtmpSuccessResponse(Success("2026-01-31T10:26:17Z", alreadySubscribedId)))
      )(using anyArg[HeaderCarrier])
    }

    "return Unprocessable(ETMP) when ETMP returns 422 with code 002 but no dsaoIdNumber" in {
      val fixture = connectors()
      stubEtmp(fixture.etmpConnector, etmpErrors(EtmpErrors.AlreadySubscribed))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.Unprocessable,
        "status=422 code=002 dsaoIdNumber missing"
      )

      verify(fixture.dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
      verifyNoInteractions(fixture.emailService)
    }

    "return Unprocessable(ETMP) when ETMP returns 422 with any other code" in {
      val fixture = connectors()
      stubEtmp(fixture.etmpConnector, etmpErrors(EtmpErrors.CouldNotBeProcessed))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.Unprocessable,
        "status=422 code=003"
      )

      verify(fixture.dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
      verifyNoInteractions(fixture.emailService)
    }

    "return Unavailable(DPS) with the logged failures and not call tax-enrolments when DPS returns 503" in {
      val fixture = connectors()
      val body    = Json.stringify(
        Json.toJson(HipFailureResponse("HIP", HipFailures(Seq(HipFailure("MISSING_REQUIRED_FIELD", "body.contacts")))))
      )
      stubEtmp(fixture.etmpConnector, etmpCreated)
      stubDps(fixture.dpsConnector, HttpResponse(Status.SERVICE_UNAVAILABLE, body))

      fixture.service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.DPS,
        Outcome.Unavailable,
        "status=503 origin=HIP failures=[MISSING_REQUIRED_FIELD]"
      )

      verify(fixture.taxEnrolmentsConnector, never()).enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier])
      verifyNoInteractions(fixture.emailService)
    }

    "return BadRequest(TAX_ENROLMENTS) when tax-enrolments returns 400" in {
      val fixture = connectors()
      stubEtmp(fixture.etmpConnector, etmpCreated)
      stubDps(fixture.dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(fixture.taxEnrolmentsConnector, HttpResponse(Status.BAD_REQUEST, ""))

      fixture.service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.TAX_ENROLMENTS, Outcome.BadRequest, "status=400")
      verifyNoInteractions(fixture.emailService)
    }
  }
}
