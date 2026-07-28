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
import play.api.mvc.*
import play.api.mvc.Results.*
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.senioraccountingofficerregistration.controllers.actions.IdentifierAction.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.{ApiError, Reason}

import scala.concurrent.{ExecutionContext, Future}

class IdentifierAction @Inject() (
    override val authConnector: AuthConnector,
    val parser: BodyParsers.Default
)(implicit val executionContext: ExecutionContext)
    extends ActionBuilder[Request, AnyContent]
    with AuthorisedFunctions
    with Logging {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {

    implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

    authorised().retrieve(Retrievals.allEnrolments) {
      case enrolments if enrolments.isAlreadyEnroled =>
        Future.successful(Forbidden(Json.toJson(ApiError(reason = Reason.ALREADY_ENROLED))))
      case _ => block(request)
    } recover {
      case _: NoActiveSession =>
        Unauthorized(Json.toJson(ApiError(reason = Reason.UNAUTHENTICATED)))
      case _: AuthorisationException =>
        Unauthorized(Json.toJson(ApiError(reason = Reason.UNAUTHENTICATED)))
    }
  }

}

object IdentifierAction {
  extension (enrolments: Enrolments) {
    def isAlreadyEnroled: Boolean =
      enrolments.enrolments.exists {
        case Enrolment("HMRC-DSAO-ORG", _, _, _) => true
        case _                                   => false
      }
  }
}
