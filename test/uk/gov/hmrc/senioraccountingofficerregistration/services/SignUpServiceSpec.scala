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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any as anyArg
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.{EtmpSubscriptionConnector, TaxEnrolmentsConnector}
import uk.gov.hmrc.senioraccountingofficerregistration.models.*

import scala.concurrent.{ExecutionContext, Future}

class SignUpServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with TestData {

  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()

  private val signUpRequest  = generatedSignUpRequest(seed = 1)
  private val signUpResponse = generatedSignUpResponse(seed = 4)

  "signUp" should {
    "call tax-enrolments with DSAO known facts after ETMP succeeds" in {
      val etmpConnector          = mock(classOf[EtmpSubscriptionConnector])
      val taxEnrolmentsConnector = mock(classOf[TaxEnrolmentsConnector])
      val service                = SignUpService(etmpConnector, taxEnrolmentsConnector)
      val enrolmentCaptor        = ArgumentCaptor.forClass(classOf[TaxEnrolmentRequest])

      when(etmpConnector.signUp(anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.successful(signUpResponse))
      when(taxEnrolmentsConnector.enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.successful(()))

      service.signUp(signUpRequest).futureValue shouldBe signUpResponse

      verify(etmpConnector).signUp(signUpRequest)
      verify(taxEnrolmentsConnector).enrol(enrolmentCaptor.capture())(using anyArg[HeaderCarrier])
      enrolmentCaptor.getValue shouldBe
        TaxEnrolmentRequest(
          identifiers = Seq(TaxEnrolmentKnownFact("EtmpSubscriptionId", signUpResponse.saoSubscriptionId)),
          verifiers = Seq(
            TaxEnrolmentKnownFact("CTUTR", signUpRequest.ctutr),
            TaxEnrolmentKnownFact("CRN", signUpRequest.crn)
          )
        )
    }

    "not call tax-enrolments when ETMP fails" in {
      val etmpConnector          = mock(classOf[EtmpSubscriptionConnector])
      val taxEnrolmentsConnector = mock(classOf[TaxEnrolmentsConnector])
      val service                = SignUpService(etmpConnector, taxEnrolmentsConnector)

      when(etmpConnector.signUp(anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.failed(UpstreamErrorResponse("ETMP failed", 500)))

      service.signUp(signUpRequest).failed.futureValue shouldBe a[UpstreamErrorResponse]

      verify(taxEnrolmentsConnector, never()).enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier])
    }

    "fail when tax-enrolments fails" in {
      val etmpConnector          = mock(classOf[EtmpSubscriptionConnector])
      val taxEnrolmentsConnector = mock(classOf[TaxEnrolmentsConnector])
      val service                = SignUpService(etmpConnector, taxEnrolmentsConnector)

      when(etmpConnector.signUp(anyArg[SignUpRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.successful(signUpResponse))
      when(taxEnrolmentsConnector.enrol(anyArg[TaxEnrolmentRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(Future.failed(UpstreamErrorResponse("tax-enrolments failed", 500)))

      service.signUp(signUpRequest).failed.futureValue shouldBe a[UpstreamErrorResponse]
    }
  }
}
