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

package uk.gov.hmrc.senioraccountingofficerregistration.controllers

import play.api.libs.json.*
import play.api.mvc.{Request, Result}
import uk.gov.hmrc.senioraccountingofficerregistration.helpers.JsonErrorHandling
import uk.gov.hmrc.senioraccountingofficerregistration.models.ApiError
import uk.gov.hmrc.senioraccountingofficerregistration.models.Reason

import scala.concurrent.Future
import scala.util.Try

object ValidateRequest {

  def as[T](block: T => Future[Result])(using request: Request[String], jsReads: Reads[T]): Future[Result] = {
    (for {
      json  <- Try(Json.parse(request.body)).toEither.left.map(_ => malformedRequestResponse)
      model <- json.validate[T].asEither.left.map(errs => toBadRequestResponse(errs.toSeq))
    } yield block(model)).merge
  }

  private def malformedRequestResponse: Future[Result] =
    Future.successful(JsonErrorHandling.malformedRequest)

  private def toBadRequestResponse(
      validationErrors: scala.collection.Seq[(JsPath, scala.collection.Seq[JsonValidationError])]
  ): Future[Result] =
    Future.successful(
      JsonErrorHandling.badRequest(
        validationErrors
          .flatMap((path, errorsAtPath) =>
            errorsAtPath.map(jsonError => ApiError(Reason.fromErrorMessage(jsonError.message), Some(path.toString)))
          )
          .toSeq
      )
    )

}
