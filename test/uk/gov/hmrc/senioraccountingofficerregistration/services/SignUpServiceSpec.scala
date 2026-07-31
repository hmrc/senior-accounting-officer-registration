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
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{DownstreamService, Outcome, SignUpResult}

import scala.concurrent.{ExecutionContext, Future}

class SignUpServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with TestData {

  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()

  private val signUpRequest       = generateSignUpRequest(seed = 1)
  private val etmpSuccessResponse = generateEtmpSuccessResponse(seed = 4)
  private val subscriptionId      = etmpSuccessResponse.success.dsaoIdNumber

  private val etmpCreated = HttpResponse(Status.CREATED, Json.stringify(Json.toJson(etmpSuccessResponse)))

  private def connectors(): (EtmpSubscriptionConnector, DpsConnector, TaxEnrolmentsConnector, SignUpService) = {
    val etmpConnector          = mock(classOf[EtmpSubscriptionConnector])
    val taxEnrolmentsConnector = mock(classOf[TaxEnrolmentsConnector])
    val dpsConnector           = mock(classOf[DpsConnector])
    val service                = SignUpService(etmpConnector, taxEnrolmentsConnector, dpsConnector)
    (etmpConnector, dpsConnector, taxEnrolmentsConnector, service)
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
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      val enrolmentCaptor = ArgumentCaptor.forClass(classOf[TaxEnrolmentRequest])

      stubEtmp(etmpConnector, etmpCreated)
      stubDps(dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(taxEnrolmentsConnector, HttpResponse(Status.NO_CONTENT, ""))

      service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Success(subscriptionId)

      verify(etmpConnector).signUp(ArgumentMatchers.eq(signUpRequest))(using anyArg[HeaderCarrier])
      verify(dpsConnector).replaceSaoSubscription(
        ArgumentMatchers.eq(subscriptionId),
        ArgumentMatchers.eq(signUpRequest)
      )(using anyArg[HeaderCarrier])
      verify(taxEnrolmentsConnector).enrol(enrolmentCaptor.capture())(using anyArg[HeaderCarrier])
      enrolmentCaptor.getValue shouldBe
        TaxEnrolmentRequest(
          identifiers = Seq(TaxEnrolmentKnownFact("EtmpSubscriptionId", subscriptionId)),
          verifiers = Seq(
            TaxEnrolmentKnownFact("CTUTR", signUpRequest.nominatedCompany.utr),
            TaxEnrolmentKnownFact("CRN", signUpRequest.nominatedCompany.crn)
          )
        )
    }

    "return Misalignment(ETMP) and not call DPS when ETMP returns 201 with an unparsable body" in {
      val (etmpConnector, dpsConnector, _, service) = connectors()
      stubEtmp(etmpConnector, HttpResponse(Status.CREATED, "not json"))

      service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.ETMP, Outcome.Misalignment, "status=201 unparsable response body")

      verify(dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
    }

    "return DownstreamError(ETMP) with the logged error detail and not call DPS when ETMP returns 500" in {
      val (etmpConnector, dpsConnector, _, service) = connectors()
      val body                                      = Json.stringify(
        Json.toJson(
          EtmpSystemError("HoD", EtmpSystemErrorResponse(EtmpSystemErrorDetail("500", "a message", "89505FF7")))
        )
      )
      stubEtmp(etmpConnector, HttpResponse(Status.INTERNAL_SERVER_ERROR, body))

      service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.DownstreamError,
        "status=500 origin=HoD code=500 message=<redacted> logID=89505FF7"
      )

      verify(dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
    }

    "return Unauthorised(ETMP) when ETMP returns 401" in {
      val (etmpConnector, _, _, service) = connectors()
      stubEtmp(etmpConnector, HttpResponse(Status.UNAUTHORIZED, ""))

      service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.ETMP, Outcome.Unauthorised, "status=401")
    }

    "return Misalignment(ETMP) for an unmapped ETMP status" in {
      val (etmpConnector, _, _, service) = connectors()
      stubEtmp(etmpConnector, HttpResponse(Status.IM_A_TEAPOT, ""))

      service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.ETMP, Outcome.Misalignment, "status=418")
    }

    "return Unavailable(ETMP) when the ETMP call does not complete" in {
      val (etmpConnector, _, _, service) = connectors()
      when(etmpConnector.signUp(anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.failed(GatewayTimeoutException("timed out")))

      service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.Unavailable,
        "unreachable: GatewayTimeoutException"
      )
    }

    "continue with the returned dsaoIdNumber when ETMP returns 422 with code 002" in {
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      val alreadySubscribedId                                            = "XB0000493000308"

      stubEtmp(etmpConnector, etmpErrors(EtmpErrors.AlreadySubscribed, Some(alreadySubscribedId)))
      stubDps(dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(taxEnrolmentsConnector, HttpResponse(Status.NO_CONTENT, ""))

      service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Success(alreadySubscribedId)

      verify(dpsConnector).replaceSaoSubscription(
        ArgumentMatchers.eq(alreadySubscribedId),
        ArgumentMatchers.eq(signUpRequest)
      )(using anyArg[HeaderCarrier])
    }

    "return Misalignment(ETMP) when ETMP returns 422 with any other code" in {
      val (etmpConnector, dpsConnector, _, service) = connectors()
      stubEtmp(etmpConnector, etmpErrors(EtmpErrors.CouldNotBeProcessed))

      service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.Misalignment,
        "status=422 code=003 text=<redacted>"
      )

      verify(dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
    }

    "return Unavailable(DPS) with the logged failures and not call tax-enrolments when DPS returns 503" in {
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      val body                                                           = Json.stringify(
        Json.toJson(HipFailureResponse("HIP", HipFailures(Seq(HipFailure("MISSING_REQUIRED_FIELD", "body.contacts")))))
      )
      stubEtmp(etmpConnector, etmpCreated)
      stubDps(dpsConnector, HttpResponse(Status.SERVICE_UNAVAILABLE, body))

      service.signUp(signUpRequest).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.DPS,
        Outcome.Unavailable,
        "status=503 origin=HIP failures=[MISSING_REQUIRED_FIELD:body.contacts]"
      )

      verify(taxEnrolmentsConnector, never()).enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier])
    }

    "return BadRequest(TAX_ENROLMENTS) when tax-enrolments returns 400" in {
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      stubEtmp(etmpConnector, etmpCreated)
      stubDps(dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(taxEnrolmentsConnector, HttpResponse(Status.BAD_REQUEST, ""))

      service.signUp(signUpRequest).futureValue shouldBe
        SignUpResult.Failed(DownstreamService.TAX_ENROLMENTS, Outcome.BadRequest, "status=400")
    }
  }
}
