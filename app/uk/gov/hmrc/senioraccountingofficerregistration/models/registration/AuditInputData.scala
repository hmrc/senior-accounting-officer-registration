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

import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.http.HeaderCarrier

final case class AuditInputData(
    auditProvider: String,
    auditSource: String,
    auditType: String,
    tags: AuditTags
)
object AuditInputData {

  given OFormat[AuditInputData] = Json.format

  def create(
      path: String
  )(implicit hc: HeaderCarrier): AuditInputData =
    AuditInputData(
      auditProvider = "mdtp",
      auditSource = "senior-accounting-officer",
      auditType = "SubscriptionSubmitted",
      tags = AuditTags.create(path, hc)
    )
}

final case class AuditTags(
    xRequestId: String,
    xSessionId: String,
    deviceId: String,
    clientIp: String,
    clientPort: String,
    path: String
)
object AuditTags {

  given OFormat[AuditTags] = Json.format

  def create(path: String, hc: HeaderCarrier): AuditTags = AuditTags(
    xRequestId = hc.requestId match
      case Some(reqId) => reqId.value
      case None        => "-"
    ,
    xSessionId = hc.sessionId match
      case Some(sessId) => sessId.value
      case None         => "-"
    ,
    deviceId = hc.deviceID.getOrElse("-"),
    clientIp = hc.trueClientIp.getOrElse("-"),
    clientPort = hc.trueClientPort.getOrElse("-"),
    path = path
  )
}
