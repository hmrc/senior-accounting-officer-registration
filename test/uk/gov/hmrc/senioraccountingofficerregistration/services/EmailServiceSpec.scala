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

import org.mockito.ArgumentMatchers.any as anyArg
import org.mockito.Mockito.*
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.Status
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.EmailConnector

import scala.concurrent.{ExecutionContext, Future}

import java.time.{Clock, Instant, ZoneId}

class EmailServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with TestData {

  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()

  private val fixedClock = Clock.fixed(Instant.parse("2026-08-10T14:26:00Z"), ZoneId.of("Europe/London"))

  private def serviceWith(response: Future[HttpResponse]): (EmailConnector, EmailService) = {
    val emailConnector = mock(classOf[EmailConnector])
    when(emailConnector.postEmail(anyArg[String], anyArg[String])(using anyArg[HeaderCarrier]))
      .thenReturn(response)

    (emailConnector, EmailService(emailConnector, fixedClock))
  }

  "sendEmail" should {
    "send the registration confirmation email request" in {
      val (emailConnector, emailService) = serviceWith(Future.successful(HttpResponse(Status.ACCEPTED, "")))
      val bodyCaptor                     = ArgumentCaptor.forClass(classOf[String])
      val signUpRequest                  = generateSignUpRequest(seed = 1)
      val etmpSuccessResponse            = generateEtmpSuccessResponse(seed = 4)

      emailService
        .sendEmail(
          "dsao_registration_confirmation",
          signUpRequest,
          etmpSuccessResponse
        )
        .futureValue

      verify(emailConnector).postEmail(bodyCaptor.capture(), ArgumentMatchers.eq("hmrc"))(using anyArg[HeaderCarrier])
      Json.parse(bodyCaptor.getValue) shouldBe Json.obj(
        "to"         -> Seq("contact1@example.com"),
        "templateId" -> "dsao_registration_confirmation",
        "parameters" -> Json.obj(
          "recipientName"     -> "contact 1",
          "companyName"       -> "example company",
          "submittedDateTime" -> "10 August 2026 at 03:26PM",
          "referenceId"       -> etmpSuccessResponse.success.dsaoIdNumber
        )
      )
    }

    "complete successfully when the email service returns BAD_REQUEST" in {
      val (_, emailService) = serviceWith(Future.successful(HttpResponse(Status.BAD_REQUEST, "bad request")))

      emailService
        .sendEmail(
          "dsao_registration_confirmation",
          generateSignUpRequest(seed = 1),
          generateEtmpSuccessResponse(seed = 4)
        )
        .futureValue shouldBe ()
    }

    "complete successfully when the email service returns an unexpected status" in {
      val (_, emailService) = serviceWith(Future.successful(HttpResponse(Status.INTERNAL_SERVER_ERROR, "error")))

      emailService
        .sendEmail(
          "dsao_registration_confirmation",
          generateSignUpRequest(seed = 1),
          generateEtmpSuccessResponse(seed = 4)
        )
        .futureValue shouldBe ()
    }

    "complete successfully when the email connector fails" in {
      val (_, emailService) = serviceWith(Future.failed(RuntimeException("email unavailable")))

      emailService
        .sendEmail(
          "dsao_registration_confirmation",
          generateSignUpRequest(seed = 1),
          generateEtmpSuccessResponse(seed = 4)
        )
        .futureValue shouldBe ()
    }
  }
}
