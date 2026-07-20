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

import play.api.libs.json.Json
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.senioraccountingofficerregistration.models.SignUpRequest
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton()
class SignUpController @Inject() (cc: ControllerComponents, signUpService: SignUpService)(using ExecutionContext)
    extends BackendController(cc) {

  def signUp: Action[SignUpRequest] = Action.async(parse.json[SignUpRequest]) { implicit request =>
    request.headers
      .get("correlationid")
      .fold(
        Future.successful(BadRequest("correlationid header not found"))
      ) { header =>
        Try(UUID.fromString(header)).fold(
          _ => Future.successful(BadRequest("invalid correlationid header")),
          header =>
            signUpService
              .signUp(request.body, header.toString)
              .map(signUpResponse => Ok(Json.toJson(signUpResponse)))
        )
      }
  }
}
