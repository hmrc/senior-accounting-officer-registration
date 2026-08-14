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

package uk.gov.hmrc.senioraccountingofficerregistration.models.requests

import play.api.libs.json.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.Reason

final case class Utr(value: String) extends AnyVal

object Utr {
  val maxUtrLength: Int = 10

  given Reads[Utr] = Json.valueReads[Utr].flatMapResult {
    case utr if utr.value.isEmpty               => JsError(Reason.CANNOT_BE_EMPTY.toString)
    case utr if utr.value.length > maxUtrLength => JsError(Reason.INVALID_FORMAT.toString)
    case utr                                    => JsSuccess(utr)
  }
  given Writes[Utr] = Json.valueWrites

}
