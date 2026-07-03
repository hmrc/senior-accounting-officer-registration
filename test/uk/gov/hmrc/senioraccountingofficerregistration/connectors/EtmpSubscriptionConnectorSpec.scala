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
import uk.gov.hmrc.senioraccountingofficerregistration.models.{SignUpRequest, SignUpResponse}

class EtmpSubscriptionConnectorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with GuiceOneAppPerSuite
    with BeforeAndAfterAll {

  private val wireMockServer = WireMockServer(options().dynamicPort())

  override def fakeApplication(): Application = {
    wireMockServer.start()

    GuiceApplicationBuilder()
      .configure(
        "microservice.services.hip.protocol"      -> "http",
        "microservice.services.hip.host"          -> "localhost",
        "microservice.services.hip.port"          -> wireMockServer.port(),
        "microservice.services.hip.authorization" -> "Basic sometoken"
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
      val request  = SignUpRequest("UTR", "1234567890")
      val response = SignUpResponse("SAOABC123456")

      wireMockServer.stubFor(
        post(urlEqualTo("/sign-up"))
          .withHeader(HeaderNames.CONTENT_TYPE, containing(MimeTypes.JSON))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo("Basic sometoken"))
          .withHeader("X-Transmitting-System", equalTo("HIP"))
          .withHeader("X-Originating-System", equalTo("MDTP"))
          .withHeader("CorrelationId", matching("[0-9a-fA-F-]{36}"))
          .withHeader("X-Receipt-Date", matching("\\d{4}-\\d{2}-\\d{2}T.*Z"))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(request))))
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
