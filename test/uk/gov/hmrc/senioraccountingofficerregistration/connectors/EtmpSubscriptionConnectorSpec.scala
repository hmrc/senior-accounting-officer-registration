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
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.play.PlayMongoModule
import uk.gov.hmrc.senioraccountingofficerregistration.TestData

class EtmpSubscriptionConnectorSpec
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

  private lazy val connector = app.injector.instanceOf[EtmpSubscriptionConnector]

  "signUp" should {
    "post the sign-up request to ETMP and return the subscription ID" in {
      val request  = generateSignUpRequest(seed = 1)
      val response = generateSignUpResponse(seed = 4)

      val expectedEtmpRequest = Json.obj(
        "idType"   -> request.idType,
        "idNumber" -> request.idNumber
      )

      wireMockServer.stubFor(
        post(urlEqualTo("/RESTAdapter/dsao/subscription"))
          .withHeader(HeaderNames.CONTENT_TYPE, containing(MimeTypes.JSON))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo("Basic c29tZS1jbGllbnQtaWQ6c29tZS1jbGllbnQtc2VjcmV0"))
          .withHeader("X-Transmitting-System", equalTo("HIP"))
          .withHeader("X-Originating-System", equalTo("MDTP"))
          .withHeader("CorrelationId", matching("[0-9a-fA-F-]{36}"))
          .withHeader("X-Receipt-Date", matching("\\d{4}-\\d{2}-\\d{2}T.*Z"))
          .withRequestBody(equalToJson(Json.stringify(expectedEtmpRequest)))
          .willReturn(
            aResponse()
              .withStatus(Status.CREATED)
              .withHeader(HeaderNames.CONTENT_TYPE, MimeTypes.JSON)
              .withBody(Json.stringify(Json.toJson(response)))
          )
      )

      connector.signUp(request).futureValue shouldBe response
    }
  }
}
