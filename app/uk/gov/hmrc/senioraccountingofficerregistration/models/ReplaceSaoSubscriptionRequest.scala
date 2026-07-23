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

package uk.gov.hmrc.senioraccountingofficerregistration.models

import play.api.libs.functional.syntax.*
import play.api.libs.json.*

final case class ReplaceSaoSubscriptionRequest(
    etmpSafeId: String,
    nominatedCompany: NominatedCompany,
    contacts: List[Contact]
)

object ReplaceSaoSubscriptionRequest {
  given OFormat[ReplaceSaoSubscriptionRequest] = Json.format[ReplaceSaoSubscriptionRequest]
}

final case class NominatedCompany(name: String, utr: String, crn: String)

object NominatedCompany {
  given OFormat[NominatedCompany] = Json.format[NominatedCompany]
}

final case class Contact(name: String, email: String, status: String, language: String)

object Contact {

  private[models] val emailRegex = """^[^\s@.]+(\.[^\s@.]+)*@[^\s@.]+(\.[^\s@.]+)+$""".r

  private val reads: Reads[Contact] =
    ((JsPath \ "name").read[String] and
      (JsPath \ "email").read[String](Reads.pattern(emailRegex, "error.email.invalid")) and
      (JsPath \ "status").read[String] and
      (JsPath \ "language").read[String])(Contact.apply)

  given OFormat[Contact] = OFormat(reads, Json.writes[Contact])
}
