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

import org.mockito.ArgumentMatchers.{any as anyArg, anyString}
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.Status
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.{
  DpsConnector,
  EtmpSubscriptionConnector,
  TaxEnrolmentsConnector
}
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{DownstreamService, SignUpResult}

import scala.concurrent.{ExecutionContext, Future}

import java.util.UUID

class SignUpServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with TestData {

  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()

  private val signUpRequest       = generateSignUpRequest(seed = 1)
  private val etmpSuccessResponse = generateEtmpSuccessResponse(seed = 4)
  private val subscriptionId      = etmpSuccessResponse.success.dsaoIdNumber
  private val correlationId       = UUID.randomUUID().toString

  private val etmpCreated = HttpResponse(Status.CREATED, Json.stringify(Json.toJson(etmpSuccessResponse)))

  private def connectors(): (EtmpSubscriptionConnector, DpsConnector, TaxEnrolmentsConnector, SignUpService) = {
    val etmpConnector          = mock(classOf[EtmpSubscriptionConnector])
    val taxEnrolmentsConnector = mock(classOf[TaxEnrolmentsConnector])
    val dpsConnector           = mock(classOf[DpsConnector])
    val service                = SignUpService(etmpConnector, taxEnrolmentsConnector, dpsConnector)
    (etmpConnector, dpsConnector, taxEnrolmentsConnector, service)
  }

  private def stubEtmp(etmpConnector: EtmpSubscriptionConnector, response: HttpResponse): Unit =
    when(etmpConnector.signUp(anyArg[SignUpRequest], anyArg[String])(using anyArg[HeaderCarrier]))
      .thenReturn(Future.successful(response))

  private def stubDps(dpsConnector: DpsConnector, response: HttpResponse): Unit =
    when(dpsConnector.replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
      .thenReturn(Future.successful(response))

  private def stubTaxEnrolments(taxEnrolmentsConnector: TaxEnrolmentsConnector, response: HttpResponse): Unit =
    when(taxEnrolmentsConnector.enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier]))
      .thenReturn(Future.successful(response))

  "signUp" should {
    "call tax-enrolments with DSAO known facts after ETMP and DPS succeed, then return Success" in {
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      val enrolmentCaptor = ArgumentCaptor.forClass(classOf[TaxEnrolmentRequest])

      stubEtmp(etmpConnector, etmpCreated)
      stubDps(dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(taxEnrolmentsConnector, HttpResponse(Status.NO_CONTENT, ""))

      service.signUp(signUpRequest, correlationId).futureValue shouldBe SignUpResult.Success(subscriptionId)

      verify(etmpConnector).signUp(ArgumentMatchers.eq(signUpRequest), anyString())(using anyArg[HeaderCarrier])
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

    "return MalformedResponse(ETMP) and not call DPS when ETMP returns 201 with an unparsable body" in {
      val (etmpConnector, dpsConnector, _, service) = connectors()
      stubEtmp(etmpConnector, HttpResponse(Status.CREATED, "not json"))

      service.signUp(signUpRequest, correlationId).futureValue shouldBe
        SignUpResult.MalformedResponse(DownstreamService.ETMP)

      verify(dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
    }

    "return InternalServerFailure(ETMP) and not call DPS when ETMP returns 500" in {
      val (etmpConnector, dpsConnector, _, service) = connectors()
      stubEtmp(etmpConnector, HttpResponse(Status.INTERNAL_SERVER_ERROR, ""))

      service.signUp(signUpRequest, correlationId).futureValue shouldBe
        SignUpResult.InternalServerFailure(DownstreamService.ETMP)

      verify(dpsConnector, never()).replaceSaoSubscription(anyArg[String], anyArg[SignUpRequest])(using
        anyArg[HeaderCarrier]
      )
    }

    "return UnknownFailure(ETMP, status) for an unmapped ETMP status" in {
      val (etmpConnector, _, _, service) = connectors()
      stubEtmp(etmpConnector, HttpResponse(Status.IM_A_TEAPOT, ""))

      service.signUp(signUpRequest, correlationId).futureValue shouldBe
        SignUpResult.UnknownFailure(DownstreamService.ETMP, Status.IM_A_TEAPOT)
    }

    "return ServiceUnavailableFailure(DPS) and not call tax-enrolments when DPS returns 503" in {
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      stubEtmp(etmpConnector, etmpCreated)
      stubDps(dpsConnector, HttpResponse(Status.SERVICE_UNAVAILABLE, ""))

      service.signUp(signUpRequest, correlationId).futureValue shouldBe
        SignUpResult.ServiceUnavailableFailure(DownstreamService.DPS)

      verify(taxEnrolmentsConnector, never()).enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier])
    }

    "return BadRequestFailure(TAX_ENROLMENTS) when tax-enrolments returns 400" in {
      val (etmpConnector, dpsConnector, taxEnrolmentsConnector, service) = connectors()
      stubEtmp(etmpConnector, etmpCreated)
      stubDps(dpsConnector, HttpResponse(Status.CREATED, ""))
      stubTaxEnrolments(taxEnrolmentsConnector, HttpResponse(Status.BAD_REQUEST, ""))

      service.signUp(signUpRequest, correlationId).futureValue shouldBe
        SignUpResult.BadRequestFailure(DownstreamService.TAX_ENROLMENTS)
    }
  }
}
