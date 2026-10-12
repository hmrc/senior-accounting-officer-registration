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

package uk.gov.hmrc.senioraccountingofficerregistration.services.registration

import play.api.libs.json.Json
import play.api.mvc.{Result, Results}
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.SignUpRequest
import uk.gov.hmrc.senioraccountingofficerregistration.models.{ApiError, Reason, SignUpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.repositories.registration.*

import scala.concurrent.{ExecutionContext, Future}

import java.time.Clock
import javax.inject.Inject

class RegistrationService @Inject() (queues: RegistrationQueues, states: RegistrationStateRepository, clock: Clock)(
    using ExecutionContext
) {

  def submit(internalId: String, enrolled: Boolean, correlationId: String, request: SignUpRequest): Future[Result] = {
    val validation = request.idempotencyKey match {
      case None                          => Some(Reason.MISSING_REQUIRED_FIELD)
      case Some(key) if key.trim.isEmpty => Some(Reason.CANNOT_BE_EMPTY)
      case Some(key) if key.length > 256 => Some(Reason.LENGTH_OUT_OF_BOUNDS)
      case _                             => None
    }
    validation match {
      case Some(reason) =>
        Future.successful(Results.BadRequest(Json.toJson(Seq(ApiError(reason, Some("/idempotencyKey"))))))
      case None =>
        val data = RegistrationData.create(internalId, correlationId, request, clock.instant())
        val root = queues(RegistrationStep.Etmp)
        root.findRoot(data.scopeKey).flatMap {
          case Some(existing)   => accepted(existing.item, data)
          case None if enrolled => Future.successful(Results.Forbidden(Json.toJson(ApiError(Reason.ALREADY_ENROLED))))
          case None             => root.enqueue(RegistrationCommand.start(data)).flatMap(accepted(_, data))
        }
    }
  }

  private def accepted(existing: RegistrationCommand, incoming: RegistrationData): Future[Result] =
    if existing.data.request != incoming.request then
      Future.successful(
        Results.Conflict(Json.toJson(ApiError(Reason.IDEMPOTENCY_KEY_CONFLICT, Some("/idempotencyKey"))))
      )
    else
      states
        .initialize(existing.data)
        .map(_ => Results.Accepted(Json.obj("idempotencyKey" -> existing.data.idempotencyKey)))

  def status(internalId: String, key: String): Future[Result] =
    states.get(RegistrationData.scopeKey(internalId, key)).map {
      case None        => Results.NotFound(Json.toJson(ApiError(Reason.NOT_FOUND)))
      case Some(state) =>
        (state.failure, state.subscriptionId) match {
          case (Some(failure), _) => Results.Status(failure.httpStatus)(Json.toJson(failure.error))
          case (None, Some(id))   => Results.Ok(Json.toJson(SignUpResponse(id)))
          case _                  => Results.NoContent
        }
    }
}
