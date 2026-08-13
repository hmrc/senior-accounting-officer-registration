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

import play.api.Logging
import play.api.http.Status.{ACCEPTED, BAD_REQUEST}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.EmailConnector
import uk.gov.hmrc.senioraccountingofficerregistration.models.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import java.time.format.DateTimeFormatter
import java.time.{Clock, LocalDateTime}
import java.util.Locale
import javax.inject.Inject

class EmailService @Inject() (emailConnector: EmailConnector, clock: Clock)(using ExecutionContext) extends Logging {

  def sendEmail(emailTemplate: EmailTemplate, signUpRequest: SignUpRequest, etmpSuccessResponse: EtmpSuccessResponse)(
      using HeaderCarrier
  ): Future[Unit] = {
    val emailDetails  = extractEmailDetails(signUpRequest, etmpSuccessResponse)
    val correlationId = summon[HeaderCarrier].extraHeaders
      .collectFirst { case (name, value) if name.equalsIgnoreCase("correlationId") => value }
      .fold("not-provided")(identity)
    val dateTime =
      LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("d MMMM yyyy 'at' hh:mma", Locale.ENGLISH))

    val emailRequests = for contact <- emailDetails.contacts yield {
      val request = EmailRequest(
        to = Seq(contact.email),
        templateId = emailTemplate.templateId,
        parameters = Map(
          "recipientName"     -> contact.name,
          "companyName"       -> emailDetails.companyName,
          "submittedDateTime" -> dateTime,
          "referenceId"       -> emailDetails.referenceId
        )
      )

      emailConnector
        .postEmail(request)
        .map {
          case HttpResponse(ACCEPTED, _, _)    => ()
          case HttpResponse(BAD_REQUEST, _, _) =>
            logger.warn(s"Error from HMRC email service: status=400 [CorrelationId=$correlationId]")
          case HttpResponse(status, _, _) =>
            logger.warn(s"Unexpected response from HMRC email service: status=$status [CorrelationId=$correlationId]")
        }
        .recover { case NonFatal(e) =>
          logger.warn(
            s"Unable to send registration confirmation email: ${e.getClass.getSimpleName} [CorrelationId=$correlationId]"
          )
        }
    }

    Future.sequence(emailRequests).map(_ => ())
  }

  private def extractEmailDetails(
      signUpRequest: SignUpRequest,
      etmpSuccessResponse: EtmpSuccessResponse
  ): EmailDetails = {
    EmailDetails(
      contacts = signUpRequest.contacts,
      companyName = signUpRequest.nominatedCompany.name,
      referenceId = etmpSuccessResponse.success.dsaoIdNumber
    )
  }

  private final case class EmailDetails(
      contacts: List[Contact],
      companyName: String,
      referenceId: String
  )

}
