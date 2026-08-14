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

import play.api.libs.json.*

enum Reason {
  case ALREADY_ENROLED
  case UNAUTHENTICATED
  case INVALID_CORRELATION_ID
  case MISSING_CORRELATION_ID
  case DOWNSTREAM_SERVICE_ERROR
  case DOWNSTREAM_SERVICE_UNAVAILABLE
  case DOWNSTREAM_SERVICE_MISALIGNMENT
  case SERVICE_MISCONFIGURATION
  case INVALID_ENUM_VALUE
  case ARRAY_MIN_ITEMS_NOT_MET
  case CANNOT_BE_EMPTY
  case INVALID_FORMAT
  case MALFORMED_REQUEST
  case MISSING_REQUIRED_FIELD
  case INVALID_DATA_TYPE
}

object Reason {
  given Writes[Reason] = Writes(reason => JsString(reason.toString))

  def fromErrorMessage(err: String): Reason = err match {
    case "error.path.missing"                     => Reason.MISSING_REQUIRED_FIELD
    case err if err.startsWith("error.expected.") => Reason.INVALID_DATA_TYPE
    case _                                        => Reason.valueOf(err)
  }
}

final case class ApiError(reason: Reason, path: Option[String] = None)

object ApiError {
  given OWrites[ApiError] = Json.writes[ApiError]
}
