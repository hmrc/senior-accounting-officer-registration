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
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.senioraccountingofficerregistration.models.EtmpSuccessResponse
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.SignUpRequest

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

final case class RegistrationData(
    orchestrationId: String,
    internalId: String,
    idempotencyKey: String,
    correlationId: String,
    request: SignUpRequest,
    submittedAt: Instant,
    etmpResponse: Option[EtmpSuccessResponse] = None,
    completedAt: Option[Instant] = None
) {

  def scopeKey: String = RegistrationData.scopeKey(internalId, idempotencyKey)
}
object RegistrationData {

  given play.api.libs.json.Format[Instant] = MongoJavatimeFormats.instantFormat

  given OFormat[RegistrationData] = Json.format

  def hash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  def scopeKey(internalId: String, key: String): String = hash(Json.arr(internalId, key).toString)

  def create(internalId: String, correlationId: String, request: SignUpRequest, now: Instant): RegistrationData =
    RegistrationData(
      UUID.randomUUID().toString,
      internalId,
      request.idempotencyKey.get,
      correlationId,
      request.copy(idempotencyKey = None),
      now
    )
}
