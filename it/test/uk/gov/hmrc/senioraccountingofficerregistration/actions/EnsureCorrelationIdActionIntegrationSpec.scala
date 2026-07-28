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

package uk.gov.hmrc.senioraccountingofficerregistration.actions

import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.readableAsString
import play.api.mvc.*
import support.ISpecBase
import uk.gov.hmrc.senioraccountingofficerregistration.actions.EnsureCorrelationIdActionIntegrationSpec.*
import uk.gov.hmrc.senioraccountingofficerregistration.controllers.actions.EnsureCorrelationIdAction

import java.util.UUID
import scala.concurrent.ExecutionContext.global
import scala.concurrent.{ExecutionContext, Future}

class EnsureCorrelationIdActionIntegrationSpec extends ISpecBase with MockitoSugar {

  override def applicationBuilder: GuiceApplicationBuilder =
    GuiceApplicationBuilder()
      .appRoutes { app =>
        val testIdentifierAction: ActionBuilder[Request, AnyContent] =
          new ActionBuilder[Request, AnyContent] {
            override def invokeBlock[A](
                request: Request[A],
                block: Request[A] => Future[Result]
            ): Future[Result] =
              block(request)

            override def executionContext: ExecutionContext = global

            override def parser: BodyParser[AnyContent] = app.injector.instanceOf[DefaultActionBuilder].parser
          }

        val ensureCorrelationIdAction = app.injector.instanceOf[EnsureCorrelationIdAction]
        { case ("GET", testUrl) =>
          (testIdentifierAction andThen ensureCorrelationIdAction) { request =>
            Results.Ok(testSuccessBody(request.correlationId))
          }
        }
      }

  def targetUrl = s"$baseUrl$testPath"

  "When correlationId header is set" must {
    "pass the action successfully" in {
      val testCorrelationId = UUID.randomUUID().toString

      val response =
        wsClient
          .url(targetUrl)
          .withHttpHeaders(
            "correlationId" -> testCorrelationId
          )
          .get()
          .futureValue

      response.status mustBe Status.OK
      response.body[String] mustBe testSuccessBody(testCorrelationId)
    }
  }

  "When correlationId header is unset" must {
    "return 400" in {
      val response =
        wsClient
          .url(targetUrl)
          .get()
          .futureValue

      response.status mustBe Status.BAD_REQUEST
      response.body[String] mustBe """[{"reason":"MISSING_CORRELATION_ID"}]"""
    }
  }

  "When correlationId header is not a valid UUID" must {
    "return 400" in {
      val response =
        wsClient
          .url(targetUrl)
          .withHttpHeaders(
            "correlationId" -> "not a uuid"
          )
          .get()
          .futureValue

      response.status mustBe Status.BAD_REQUEST
      response.body[String] mustBe """[{"reason":"INVALID_CORRELATION_ID"}]"""
    }
  }

}

object EnsureCorrelationIdActionIntegrationSpec {
  val testPath                               = "/test-ensure-correlation-id-action"
  def testSuccessBody(correlationId: String) = s"Action Passed Successfully correlationId=$correlationId"
}
