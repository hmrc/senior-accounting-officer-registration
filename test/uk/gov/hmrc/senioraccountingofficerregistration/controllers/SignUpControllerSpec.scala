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
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Result
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.{
  DpsConnector,
  EtmpSubscriptionConnector,
  TaxEnrolmentsConnector
}
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{DownstreamService, SignUpResult}

import scala.concurrent.{ExecutionContext, Future}

import java.util.UUID

class SignUpControllerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with TestData {

  private given ExecutionContext = ExecutionContext.global
  private val actorSystem        = ActorSystem("SignUpControllerSpec")
  private given ActorSystem      = actorSystem

  private val signUpRequest       = generateSignUpRequest(seed = 1)
  private val etmpSuccessResponse = generateEtmpSuccessResponse(seed = 4)
  private val signUpResponse      = SignUpResponse(etmpSuccessResponse.success.dsaoIdNumber)
  private val correlationId       = UUID.randomUUID().toString

  private val etmpSubscriptionConnector = mock(classOf[EtmpSubscriptionConnector])
  private val taxEnrolmentsConnector    = mock(classOf[TaxEnrolmentsConnector])
  private val dpsConnector              = mock(classOf[DpsConnector])

  private def stubService(onSignUp: (SignUpRequest, String) => Future[SignUpResult]): SignUpService =
    new SignUpService(etmpSubscriptionConnector, taxEnrolmentsConnector, dpsConnector) {
      override def signUp(request: SignUpRequest, header: String)(using HeaderCarrier): Future[SignUpResult] =
        onSignUp(request, header)
    }

  private def controllerReturning(result: SignUpResult): SignUpController =
    new SignUpController(Helpers.stubControllerComponents(), stubService((_, _) => Future.successful(result)))

  private def postSignUp(
      controller: SignUpController,
      body: JsValue,
      withCorrelationId: Boolean = true
  ): Future[Result] = {
    val base    = FakeRequest("POST", "/sign-up").withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
    val request =
      if withCorrelationId then base.withHeaders("CorrelationId" -> correlationId).withJsonBody(body)
      else base.withJsonBody(body)
    call(controller.signUp, request)
  }

  override def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

  "POST /sign-up" should {
    "return 200 with the subscription ID when the sign up succeeds" in {
      val service = stubService { (request, header) =>
        header shouldBe correlationId
        request shouldBe signUpRequest
        Future.successful(SignUpResult.Success(signUpResponse.subscriptionId))
      }
      val controller = new SignUpController(Helpers.stubControllerComponents(), service)

      val result = postSignUp(controller, Json.toJson(signUpRequest))

      status(result) shouldBe Status.OK
      contentAsJson(result).as[SignUpResponse] shouldBe signUpResponse
    }

    "return 400 with 'CorrelationId header not found' message" in {
      val controller = controllerReturning(SignUpResult.Success(signUpResponse.subscriptionId))
      val result     = postSignUp(controller, Json.toJson(signUpRequest), withCorrelationId = false)

      status(result) shouldBe Status.BAD_REQUEST
      contentAsString(result) shouldBe "CorrelationId header not found"
    }

    "return 400 with 'Invalid CorrelationId header' message" in {
      val controller = controllerReturning(SignUpResult.Success(signUpResponse.subscriptionId))
      val result     = call(
        controller.signUp,
        FakeRequest("POST", "/sign-up")
          .withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
          .withHeaders("CorrelationId" -> "CorrelationId")
          .withJsonBody(Json.toJson(signUpRequest))
      )

      status(result) shouldBe Status.BAD_REQUEST
      contentAsString(result) shouldBe "Invalid CorrelationId header"
    }

    "return 400 for an unparsable request body" in {
      val controller = controllerReturning(SignUpResult.Success(signUpResponse.subscriptionId))
      val result     = postSignUp(controller, Json.obj("idType" -> "UTR"))

      status(result) shouldBe Status.BAD_REQUEST
    }

    "return 400 when the request contains no contacts" in {
      val controller = controllerReturning(SignUpResult.Success(signUpResponse.subscriptionId))
      val body       = Json.toJson(signUpRequest).as[JsObject] ++ Json.obj("contacts" -> Json.arr())

      status(postSignUp(controller, body)) shouldBe Status.BAD_REQUEST
    }

    "return 400 when a contact email is invalid" in {
      val controller = controllerReturning(SignUpResult.Success(signUpResponse.subscriptionId))
      val body       = Json.obj(
        "etmpSafeId"       -> signUpRequest.etmpSafeId,
        "nominatedCompany" -> Json.toJson(signUpRequest.nominatedCompany),
        "contacts"         -> Json.arr(
          Json.obj(
            "name"     -> "contact 1",
            "email"    -> "not-an-email",
            "status"   -> "active",
            "language" -> "en-GB"
          )
        )
      )

      status(postSignUp(controller, body)) shouldBe Status.BAD_REQUEST
    }
  }

  "POST /sign-up downstream failures" should {
    def resultFor(failure: SignUpResult): Future[Result] =
      postSignUp(controllerReturning(failure), Json.toJson(signUpRequest))

    "translate a MalformedResponse to 502 with a DOWNSTREAM_SERVICE_MISALIGNMENT error" in {
      val result = resultFor(SignUpResult.MalformedResponse(DownstreamService.ETMP))
      status(result) shouldBe Status.BAD_GATEWAY
      contentAsJson(result) shouldBe Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT))
    }

    "translate a BadRequestFailure to 500 with a DOWNSTREAM_SERVICE_MISALIGNMENT error" in {
      val result = resultFor(SignUpResult.BadRequestFailure(DownstreamService.DPS))
      status(result) shouldBe Status.INTERNAL_SERVER_ERROR
      contentAsJson(result) shouldBe Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT))
    }

    "translate an InternalServerFailure to 502 with a DOWNSTREAM_SERVICE_ERROR error" in {
      val result = resultFor(SignUpResult.InternalServerFailure(DownstreamService.DPS))
      status(result) shouldBe Status.BAD_GATEWAY
      contentAsJson(result) shouldBe Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_ERROR))
    }

    "translate a ServiceUnavailableFailure to 502 with a DOWNSTREAM_SERVICE_UNAVAILABLE error" in {
      val result = resultFor(SignUpResult.ServiceUnavailableFailure(DownstreamService.DPS))
      status(result) shouldBe Status.BAD_GATEWAY
      contentAsJson(result) shouldBe Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_UNAVAILABLE))
    }

    "translate an UnknownFailure to 502 with a DOWNSTREAM_SERVICE_MISALIGNMENT error" in {
      val result = resultFor(SignUpResult.UnknownFailure(DownstreamService.TAX_ENROLMENTS, Status.IM_A_TEAPOT))
      status(result) shouldBe Status.BAD_GATEWAY
      contentAsJson(result) shouldBe Json.toJson(ApiError(Reason.DOWNSTREAM_SERVICE_MISALIGNMENT))
    }
  }
}
