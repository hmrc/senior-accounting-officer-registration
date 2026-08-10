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
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.EmailConnector

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.concurrent.{ExecutionContext, Future}

class EmailService(emailConnector: EmailConnector)(using ExecutionContext) extends Logging {

  def sendEmail(emailTemplate: String, senderName: String, email: String, companyName: String, ref: String)(
    using HeaderCarrier
  ): Future[Unit] = {
    
    lazy val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy 'at' hh:mma", Locale.ENGLISH))

    val body = Json.obj(
      "to" -> Array(email),
      "templateId" -> emailTemplate,
      "parameters" -> Json.obj(
        "recipientName" -> senderName,
        "companyName" -> companyName,
        "submittedDateTime" -> dateTime,
        "referenceId" -> ref
      )
    )
    emailConnector.postEmail(Json.stringify(body), "hmrc").map {
      case HttpResponse(ACCEPTED, body, _) => ()
      case HttpResponse(BAD_REQUEST, body, _) => logger.warn(s"Error from HMRC email service: $body")
    }
  }
  
}
