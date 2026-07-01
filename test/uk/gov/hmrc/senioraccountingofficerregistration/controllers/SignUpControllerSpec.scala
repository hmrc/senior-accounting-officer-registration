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

import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.mock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.libs.json.Json
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.EtmpSubscriptionConnector
import uk.gov.hmrc.senioraccountingofficerregistration.models.{SignUpRequest, SignUpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService

import scala.concurrent.{ExecutionContext, Future}

class SignUpControllerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private given ExecutionContext = ExecutionContext.global
  private val actorSystem        = ActorSystem("SignUpControllerSpec")
  private given ActorSystem      = actorSystem

  private val signUpRequest  = SignUpRequest("UTR", "1234567890")
  private val signUpResponse = SignUpResponse("SAOABC123456")

  private val etmpSubscriptionConnector = mock(classOf[EtmpSubscriptionConnector])

  private val signUpService = new SignUpService(etmpSubscriptionConnector) {
    override def signUp(request: SignUpRequest)(using HeaderCarrier): Future[SignUpResponse] = {
      request shouldBe signUpRequest
      Future.successful(signUpResponse)
    }
  }

  private val controller = new SignUpController(Helpers.stubControllerComponents(), signUpService)

  override def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

  "POST /sign-up" should {
    "return 201 with the subscription ID returned by ETMP" in {
      val result = call(
        controller.signUp,
        FakeRequest("POST", "/sign-up")
          .withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
          .withJsonBody(Json.toJson(signUpRequest))
      )

      status(result) shouldBe Status.CREATED
      contentAsJson(result) shouldBe Json.toJson(signUpResponse)
    }

    "return 400 for an invalid request body" in {
      val result = call(
        controller.signUp,
        FakeRequest("POST", "/sign-up")
          .withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
          .withJsonBody(Json.obj("idType" -> "UTR"))
      )

      status(result) shouldBe Status.BAD_REQUEST
    }
  }
}
