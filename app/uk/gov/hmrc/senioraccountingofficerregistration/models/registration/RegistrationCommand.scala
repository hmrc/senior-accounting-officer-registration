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

package uk.gov.hmrc.senioraccountingofficerregistration.models.registration

import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.senioraccountingofficerregistration.models.{EmailRequest, EmailTemplate}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.Locale

final case class RegistrationCommand(
    commandKey: String,
    step: RegistrationStep,
    data: RegistrationData,
    email: Option[EmailRequest] = None,
    operationFinished: Boolean = false,
    attempts: Int = 0,
    failure: Option[RegistrationFailure] = None,
    lastError: Option[String] = None,
    leaseToken: Option[String] = None,
    expiresAt: Option[Instant] = None
) {

  private def required[A](value: Option[A], field: String): A =
    value.getOrElse(throw new IllegalStateException(s"Missing required registration field: $field"))

  private def next(step: RegistrationStep, email: Option[EmailRequest] = None): RegistrationCommand =
    RegistrationCommand(
      s"${data.orchestrationId}/$step/${email.map(e => RegistrationData.hash(Json.toJson(e).toString)).getOrElse("")}",
      step,
      data,
      email
    )

  def successors: Seq[RegistrationCommand] = {
    import RegistrationStep.*
    if failure.isDefined then Seq.empty
    else
      step match {
        case Etmp          => Seq(next(Dps))
        case Dps           => Seq(next(TaxEnrolments))
        case TaxEnrolments =>
          val etmpResponse = required(data.etmpResponse, "etmpResponse")
          val submitted    = DateTimeFormatter
            .ofPattern("d MMMM yyyy 'at' hh:mma", Locale.ENGLISH)
            .withZone(ZoneId.of("Europe/London"))
            .format(data.submittedAt)
          val emails = data.request.contacts.value.map { contact =>
            next(
              Email,
              Some(
                EmailRequest(
                  Seq(contact.email.value),
                  EmailTemplate.RegistrationConfirmation.templateId,
                  Map(
                    "recipientName"     -> contact.name.value,
                    "companyName"       -> data.request.nominatedCompany.name.value,
                    "submittedDateTime" -> submitted,
                    "referenceId"       -> etmpResponse.success.dsaoIdNumber
                  )
                )
              )
            )
          }
          next(Audit) +: emails.distinctBy(_.commandKey)
        case Email | Audit => Seq.empty
      }
  }
}
object RegistrationCommand {

  given Format[Instant] = MongoJavatimeFormats.instantFormat

  given OFormat[RegistrationCommand] = Json.format

  def start(data: RegistrationData): RegistrationCommand =
    RegistrationCommand(data.scopeKey, RegistrationStep.Etmp, data)
}
