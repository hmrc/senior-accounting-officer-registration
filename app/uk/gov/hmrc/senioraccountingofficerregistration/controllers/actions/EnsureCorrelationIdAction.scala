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

package uk.gov.hmrc.senioraccountingofficerregistration.controllers.actions

import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.Results.BadRequest
import play.api.mvc.{ActionRefiner, Request, Result}
import uk.gov.hmrc.senioraccountingofficerregistration.models.{ApiError, CorrelatableRequest, Reason}

import scala.concurrent.{ExecutionContext, Future}

import EnsureCorrelationIdAction.*

class EnsureCorrelationIdAction @Inject() ()(override implicit val executionContext: ExecutionContext)
    extends ActionRefiner[Request, CorrelatableRequest]
    with Logging {

  override protected def refine[A](request: Request[A]): Future[Either[Result, CorrelatableRequest[A]]] =
    request.headers.get("correlationId") match {
      case Some(correlationId) if correlationId.matches(hipCorrelationIdRegex) =>
        Future.successful(Right(CorrelatableRequest(request, correlationId)))
      case Some(_) =>
        logger.warn(s"[BAD_REQUEST][INVALID_CORRELATION_ID] ${request.method} ${request.uri}")
        Future.successful(Left(BadRequest(Json.toJson(Seq(ApiError(reason = Reason.INVALID_CORRELATION_ID))))))
      case _ =>
        logger.warn(s"[BAD_REQUEST][MISSING_CORRELATION_ID] ${request.method} ${request.uri}")
        Future.successful(Left(BadRequest(Json.toJson(Seq(ApiError(reason = Reason.MISSING_CORRELATION_ID))))))
    }
}

object EnsureCorrelationIdAction {
  private def hipCorrelationIdRegex: String =
    "^[0-9a-fA-F]{8}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{4}[-][0-9a-fA-F]{12}$"
}
