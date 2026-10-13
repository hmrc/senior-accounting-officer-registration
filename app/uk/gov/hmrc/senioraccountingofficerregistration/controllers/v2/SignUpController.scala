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

package uk.gov.hmrc.senioraccountingofficerregistration.controllers.v2

import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.senioraccountingofficerregistration.controllers.actions.EnsureCorrelationIdAction
import uk.gov.hmrc.senioraccountingofficerregistration.controllers.{BaseController, ValidateRequest}
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.SignUpRequest
import uk.gov.hmrc.senioraccountingofficerregistration.services.registration.RegistrationService

import scala.concurrent.ExecutionContext

import javax.inject.{Inject, Singleton}

@Singleton
class SignUpController @Inject() (
    cc: ControllerComponents,
    service: RegistrationService,
    identify: IdentifierAction,
    ensureCorrelation: EnsureCorrelationIdAction
)(using ExecutionContext)
    extends BaseController(cc) {

  def signUp: Action[String] = (identify andThen ensureCorrelation).async(parse.tolerantText) { implicit request =>
    val identity = request.attrs(RegistrationIdentity.key)
    ValidateRequest.as[SignUpRequest] { data =>
      service.submit(
        identity.internalId,
        identity.enrolled,
        request.correlationId,
        data,
        request.headers.get("Authorization")
      )
    }
  }

  def status(idempotencyKey: String): Action[String] = (identify andThen ensureCorrelation).async(parse.tolerantText) {
    request =>
      service.status(request.attrs(RegistrationIdentity.key).internalId, idempotencyKey)
  }
}
