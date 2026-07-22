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
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.SignUpResult

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton()
class SignUpController @Inject() (cc: ControllerComponents, signUpService: SignUpService)(using ExecutionContext)
    extends BackendController(cc)
    with Logging {

  def signUp: Action[SignUpRequest] = Action.async(parse.json[SignUpRequest]) { implicit request =>
    request.headers
      .get("CorrelationId")
      .fold(
        Future.successful(BadRequest("CorrelationId header not found"))
      ) { header =>
        Try(UUID.fromString(header)).fold(
          _ => Future.successful(BadRequest("Invalid CorrelationId header")),
          header =>
            signUpService.signUp(request.body, header.toString).map {
              case SignUpResult.Success(subscriptionId) =>
                Ok(Json.toJson(SignUpResponse(subscriptionId)))
              case SignUpResult.MalformedResponse(downstreamService) =>
                logger.warn(s"[SignUp][$downstreamService][MalformedResponse][CorrelationId=$header]")
                BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)))
              case SignUpResult.BadRequestFailure(downstreamService) =>
                logger.warn(s"[SignUp][$downstreamService][BAD_REQUEST][CorrelationId=$header]")
                InternalServerError(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)))
              case SignUpResult.InternalServerFailure(downstreamService) =>
                logger.warn(s"[SignUp][$downstreamService][INTERNAL_SERVER_ERROR][CorrelationId=$header]")
                BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_ERROR)))
              case SignUpResult.ServiceUnavailableFailure(downstreamService) =>
                logger.warn(s"[SignUp][$downstreamService][SERVICE_UNAVAILABLE][CorrelationId=$header]")
                BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_UNAVAILABLE)))
              case SignUpResult.UnknownFailure(downstreamService, status) =>
                logger.warn(s"[SignUp][$downstreamService][Unknown][CorrelationId=$header]status=$status")
                BadGateway(Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)))
            }
        )
      }
  }
}
