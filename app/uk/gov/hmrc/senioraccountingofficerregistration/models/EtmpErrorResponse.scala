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

import play.api.libs.json.{Format, Json}

final case class EtmpErrors(
    processingDate: String,
    code: String,
    text: String,
    dsaoIdNumber: Option[String] = None
)

object EtmpErrors {
  val NoMasterDataFound: String   = "001"
  val AlreadySubscribed: String   = "002"
  val CouldNotBeProcessed: String = "003"
  val DuplicateSubmission: String = "004"

  given Format[EtmpErrors] = Json.format[EtmpErrors]
}

final case class EtmpErrorResponse(errors: EtmpErrors)

object EtmpErrorResponse {
  given Format[EtmpErrorResponse] = Json.format[EtmpErrorResponse]
}
