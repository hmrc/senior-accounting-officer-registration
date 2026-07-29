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

import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Result
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}
import play.api.{Application, inject}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{DownstreamService, SignUpResult}

import scala.concurrent.Future

import java.util.UUID

class SignUpControllerSpec
    extends AnyWordSpec
    with GuiceOneAppPerSuite
    with Matchers
    with BeforeAndAfterEach
    with TestData
    with MockitoSugar
    with OptionValues
    with ScalaFutures {

  private val signUpRequest       = generateSignUpRequest(seed = 1)
  private val etmpSuccessResponse = generateEtmpSuccessResponse(seed = 4)
  private val signUpResponse      = SignUpResponse(etmpSuccessResponse.success.dsaoIdNumber)

  private val mockSignUpService = mock[SignUpService]

  override def fakeApplication(): Application = GuiceApplicationBuilder()
    .overrides(
      bind[IdentifierAction].to[FakeIdentifierAction],
      bind[SignUpService].toInstance(mockSignUpService)
    )
    .build()

  private def postSignUp(
      body: JsValue,
      withCorrelationId: Option[String] = Some(UUID.randomUUID().toString)
  ): Future[Result] = {
    val base =
      FakeRequest("POST", routes.SignUpController.signUp.url).withHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
    val request = withCorrelationId match {
      case Some(correlationId) => base.withHeaders("CorrelationId" -> correlationId).withJsonBody(body)
      case _                   => base.withJsonBody(body)
    }
    route(app, request).value
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockSignUpService)
  }

  def SUT: SignUpController = app.injector.instanceOf[SignUpController]

  "POST /sign-up" should {
    "return 200 with the subscription ID when the sign up succeeds" in {
      when(mockSignUpService.signUp(meq(signUpRequest))(using any()))
        .thenReturn(Future.successful(SignUpResult.Success(signUpResponse.subscriptionId)))

      val result = postSignUp(Json.toJson(signUpRequest))

      status(result) shouldBe Status.OK
      contentAsJson(result).as[SignUpResponse] shouldBe signUpResponse
    }

    "return 400 with 'MISSING_CORRELATION_ID' message" in {
      val result = postSignUp(Json.toJson(signUpRequest), withCorrelationId = None)

      status(result) shouldBe Status.BAD_REQUEST
      contentAsString(result) shouldBe """[{"reason":"MISSING_CORRELATION_ID"}]"""
    }

    "return 400 with 'INVALID_CORRELATION_ID' message" in {
      val result =
        postSignUp(Json.toJson(signUpRequest), withCorrelationId = Some("CorrelationId"))

      status(result) shouldBe Status.BAD_REQUEST
      contentAsString(result) shouldBe """[{"reason":"INVALID_CORRELATION_ID"}]"""
    }

    "return 400 for an unparsable request body" in {
      val result = postSignUp(Json.obj("idType" -> "UTR"))

      status(result) shouldBe Status.BAD_REQUEST
    }

    "return 400 when the request contains no contacts" in {
      val body = Json.toJson(signUpRequest).as[JsObject] ++ Json.obj("contacts" -> Json.arr())

      status(postSignUp(body)) shouldBe Status.BAD_REQUEST
    }

    "return 400 when a contact email is invalid" in {
      val body = Json.obj(
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

      status(postSignUp(body)) shouldBe Status.BAD_REQUEST
    }
  }

  "POST /sign-up downstream failures" should {
    def resultFor(failure: SignUpResult): Future[Result] = {
      when(mockSignUpService.signUp(meq(signUpRequest))(using any())).thenReturn(Future.successful(failure))
      postSignUp(Json.toJson(signUpRequest))
    }

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
