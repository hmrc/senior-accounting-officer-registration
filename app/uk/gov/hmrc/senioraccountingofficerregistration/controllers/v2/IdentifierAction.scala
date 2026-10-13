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

import play.api.libs.json.Json
import play.api.libs.typedmap.TypedKey
import play.api.mvc.*
import play.api.mvc.Results.*
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.auth.core.retrieve.~
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.senioraccountingofficerregistration.models.{ApiError, Reason}

import scala.concurrent.{ExecutionContext, Future}

import javax.inject.Inject

final case class RegistrationIdentity(internalId: String, enrolled: Boolean)
object RegistrationIdentity {

  val key: TypedKey[RegistrationIdentity] = TypedKey("registrationIdentity")
}

class IdentifierAction @Inject() (override val authConnector: AuthConnector, val parser: BodyParsers.Default)(using
    val executionContext: ExecutionContext
) extends ActionBuilder[Request, AnyContent]
    with AuthorisedFunctions {

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    given HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
    authorised()
      .retrieve(Retrievals.internalId and Retrievals.allEnrolments) {
        case Some(internalId) ~ enrolments =>
          val identity = RegistrationIdentity(internalId, enrolments.enrolments.exists(_.key == "HMRC-DSAO-ORG"))
          block(request.addAttr(RegistrationIdentity.key, identity))
        case _ => Future.successful(Unauthorized(Json.toJson(ApiError(Reason.UNAUTHENTICATED))))
      }
      .recover { case _: AuthorisationException =>
        Unauthorized(Json.toJson(ApiError(Reason.UNAUTHENTICATED)))
      }
  }
}
