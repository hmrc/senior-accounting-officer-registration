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

package uk.gov.hmrc.senioraccountingofficerregistration.connectors

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.{HeaderNames, MimeTypes, Status}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.mongo.play.PlayMongoModule
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.models.ReplaceSaoSubscriptionRequest

import java.util.UUID

class DpsConnectorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with GuiceOneAppPerSuite
    with BeforeAndAfterAll
    with TestData {

  private val wireMockServer = WireMockServer(options().dynamicPort())

  override def fakeApplication(): Application = {
    wireMockServer.start()

    GuiceApplicationBuilder()
      .configure(
        "microservice.services.hip.protocol" -> "http",
        "microservice.services.hip.host"     -> "localhost",
        "microservice.services.hip.port"     -> wireMockServer.port(),
        "microservice.services.hip.clientId" -> "some-client-id",
        "microservice.services.hip.secret"   -> "some-client-secret"
      )
      .disable[PlayMongoModule]
      .build()
  }

  override def afterAll(): Unit = {
    wireMockServer.stop()
    super.afterAll()
  }

  private given HeaderCarrier = HeaderCarrier()

  private lazy val connector = app.injector.instanceOf[DpsConnector]
  private val correlationId  = UUID.randomUUID().toString

  "replaceSaoSubscription" should {
    "return 201 with empty payload" in {
      val signUpRequest                 = generateSignUpRequest(2)
      val replaceSaoSubscriptionRequest =
        ReplaceSaoSubscriptionRequest(signUpRequest.etmpSafeId, signUpRequest.nominatedCompany, signUpRequest.contacts)
      val expectedSignUpRequest = Json.toJson(replaceSaoSubscriptionRequest).as[JsObject]
      val subscriptionId        = "123"

      wireMockServer.stubFor(
        put(s"/subscriptions/${subscriptionId}")
          .withHeader(HeaderNames.CONTENT_TYPE, containing(MimeTypes.JSON))
          .withRequestBody(equalToJson(Json.stringify(expectedSignUpRequest)))
          .willReturn(aResponse().withStatus(Status.CREATED))
      )
      connector.replaceSaoSubscription(subscriptionId, signUpRequest, correlationId).futureValue shouldBe ()
    }
  }

  "fail when DPS returns a non 201 response" in {
    val signUpRequest                 = generateSignUpRequest(2)
    val replaceSaoSubscriptionRequest =
      ReplaceSaoSubscriptionRequest(signUpRequest.etmpSafeId, signUpRequest.nominatedCompany, signUpRequest.contacts)
    val expectedSignUpRequest = Json.toJson(replaceSaoSubscriptionRequest).as[JsObject]
    val subscriptionId        = "456"

    wireMockServer.stubFor(
      put(s"/subscriptions/${subscriptionId}")
        .withHeader(HeaderNames.CONTENT_TYPE, containing(MimeTypes.JSON))
        .withRequestBody(equalToJson(Json.stringify(expectedSignUpRequest)))
        .willReturn(aResponse().withStatus(Status.INTERNAL_SERVER_ERROR))
    )
    connector
      .replaceSaoSubscription(subscriptionId, signUpRequest, correlationId)
      .failed
      .futureValue shouldBe a[UpstreamErrorResponse]
  }
}
