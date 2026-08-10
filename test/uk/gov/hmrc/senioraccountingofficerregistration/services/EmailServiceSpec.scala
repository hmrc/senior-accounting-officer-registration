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

package uk.gov.hmrc.senioraccountingofficerregistration.services

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any as anyArg
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.EmailConnector
import uk.gov.hmrc.senioraccountingofficerregistration.models.{EmailRequest, EmailTemplate}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

import java.time.{Clock, Instant, ZoneId}

class EmailServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar with TestData {

  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()

  private val fixedClock = Clock.fixed(Instant.parse("2026-08-10T14:26:00Z"), ZoneId.of("Europe/London"))

  private def serviceWith(response: Future[HttpResponse]): (EmailConnector, EmailService) = {
    val emailConnector = mock[EmailConnector]
    when(emailConnector.postEmail(anyArg[EmailRequest])(using anyArg[HeaderCarrier]))
      .thenReturn(response)

    (emailConnector, EmailService(emailConnector, fixedClock))
  }

  "sendEmail" should {
    "send a registration confirmation email request to each contact" in {
      val (emailConnector, emailService) = serviceWith(Future.successful(HttpResponse(Status.ACCEPTED, "")))
      val requestCaptor                  = ArgumentCaptor.forClass(classOf[EmailRequest])
      val signUpRequest                  = generateSignUpRequest(seed = 1)
      val etmpSuccessResponse            = generateEtmpSuccessResponse(seed = 4)

      emailService
        .sendEmail(
          EmailTemplate.RegistrationConfirmation,
          signUpRequest,
          etmpSuccessResponse
        )
        .futureValue

      verify(emailConnector, times(2)).postEmail(requestCaptor.capture())(using anyArg[HeaderCarrier])
      requestCaptor.getAllValues.asScala.toSeq shouldBe Seq(
        EmailRequest(
          to = Seq("contact1@example.com"),
          templateId = EmailTemplate.RegistrationConfirmation.templateId,
          parameters = Map(
            "recipientName"     -> "contact 1",
            "companyName"       -> "example company",
            "submittedDateTime" -> "10 August 2026 at 03:26PM",
            "referenceId"       -> etmpSuccessResponse.success.dsaoIdNumber
          )
        ),
        EmailRequest(
          to = Seq("contact2@example.com"),
          templateId = EmailTemplate.RegistrationConfirmation.templateId,
          parameters = Map(
            "recipientName"     -> "contact 2",
            "companyName"       -> "example company",
            "submittedDateTime" -> "10 August 2026 at 03:26PM",
            "referenceId"       -> etmpSuccessResponse.success.dsaoIdNumber
          )
        )
      )
    }

    "complete successfully when the email service returns BAD_REQUEST" in {
      val (_, emailService) = serviceWith(Future.successful(HttpResponse(Status.BAD_REQUEST, "bad request")))

      emailService
        .sendEmail(
          EmailTemplate.RegistrationConfirmation,
          generateSignUpRequest(seed = 1),
          generateEtmpSuccessResponse(seed = 4)
        )
        .futureValue shouldBe ()
    }

    "complete successfully when the email service returns an unexpected status" in {
      val (_, emailService) = serviceWith(Future.successful(HttpResponse(Status.INTERNAL_SERVER_ERROR, "error")))

      emailService
        .sendEmail(
          EmailTemplate.RegistrationConfirmation,
          generateSignUpRequest(seed = 1),
          generateEtmpSuccessResponse(seed = 4)
        )
        .futureValue shouldBe ()
    }

    "complete successfully when the email connector fails" in {
      val (_, emailService) = serviceWith(Future.failed(RuntimeException("email unavailable")))

      emailService
        .sendEmail(
          EmailTemplate.RegistrationConfirmation,
          generateSignUpRequest(seed = 1),
          generateEtmpSuccessResponse(seed = 4)
        )
        .futureValue shouldBe ()
    }

    "continue sending emails when the first connector call fails" in {
      val emailConnector      = mock[EmailConnector]
      val emailService        = EmailService(emailConnector, fixedClock)
      val requestCaptor       = ArgumentCaptor.forClass(classOf[EmailRequest])
      val signUpRequest       = generateSignUpRequest(seed = 1)
      val etmpSuccessResponse = generateEtmpSuccessResponse(seed = 4)

      when(emailConnector.postEmail(anyArg[EmailRequest])(using anyArg[HeaderCarrier]))
        .thenReturn(
          Future.failed(RuntimeException("email unavailable")),
          Future.successful(HttpResponse(Status.ACCEPTED, ""))
        )

      emailService
        .sendEmail(
          EmailTemplate.RegistrationConfirmation,
          signUpRequest,
          etmpSuccessResponse
        )
        .futureValue shouldBe ()

      verify(emailConnector, times(2)).postEmail(requestCaptor.capture())(using anyArg[HeaderCarrier])
      requestCaptor.getAllValues.asScala.toSeq.map(_.to) shouldBe Seq(
        Seq("contact1@example.com"),
        Seq("contact2@example.com")
      )
    }
  }
}
