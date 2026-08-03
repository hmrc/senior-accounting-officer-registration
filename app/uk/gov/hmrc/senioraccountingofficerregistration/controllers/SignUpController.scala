/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.senioraccountingofficerregistration.controllers

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.senioraccountingofficerregistration.controllers.actions.{EnsureCorrelationIdAction, IdentifierAction}
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{Outcome, SignUpResult}

import scala.concurrent.ExecutionContext

import javax.inject.{Inject, Singleton}

@Singleton()
class SignUpController @Inject() (
    cc: ControllerComponents,
    signUpService: SignUpService,
    identify: IdentifierAction,
    ensureCorrelation: EnsureCorrelationIdAction
)(using ExecutionContext)
    extends BaseController(cc)
    with Logging {

  def signUp: Action[SignUpRequest] = (identify andThen ensureCorrelation).async(parse.json[SignUpRequest]) {
    implicit request =>
      val correlationId = request.correlationId
      signUpService.signUp(request.body).map {
        case SignUpResult.Success(subscriptionId) =>
          Ok(Json.toJson(SignUpResponse(subscriptionId)))
        case SignUpResult.Failed(downstreamService, outcome, detail) =>
          logger.error(
            s"[SignUp][$downstreamService][${outcome.logMessage}][CorrelationId=$correlationId] $detail"
          )
          outcome match {
            case Outcome.BadRequest =>
              InternalServerError(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)))
            case Outcome.Unauthorised | Outcome.Forbidden =>
              InternalServerError(Json.toJson(ApiError(Reason.SERVICE_MISCONFIGURATION)))
            case Outcome.DownstreamError =>
              BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_ERROR)))
            case Outcome.Unavailable =>
              BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_UNAVAILABLE)))
            case Outcome.MalformedResponse | Outcome.Misalignment =>
              BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)))
          }
      }

  }
}
