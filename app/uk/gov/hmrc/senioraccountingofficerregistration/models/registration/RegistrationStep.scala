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

import play.api.libs.json.*

import scala.util.Try

enum RegistrationStep {
  case Etmp, Dps, TaxEnrolments, Email, Audit

  def blocking: Boolean = this match {
    case Etmp | Dps | TaxEnrolments => true
    case Email | Audit              => false
  }
}
object RegistrationStep {

  given Format[RegistrationStep] = Format(
    json =>
      json
        .validate[String]
        .flatMap(value =>
          Try(RegistrationStep.valueOf(value)).fold(_ => JsError("Invalid registration step"), JsSuccess(_))
        ),
    step => JsString(step.toString)
  )
}
