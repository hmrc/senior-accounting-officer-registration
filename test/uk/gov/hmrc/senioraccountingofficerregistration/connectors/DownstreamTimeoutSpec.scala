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
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Configuration, Environment}
import uk.gov.hmrc.http.{GatewayTimeoutException, HeaderCarrier}
import uk.gov.hmrc.mongo.play.PlayMongoModule
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.{DownstreamService, Outcome, SignUpResult}

import scala.concurrent.duration.*

class DownstreamTimeoutSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with GuiceOneAppPerSuite
    with BeforeAndAfterAll
    with TestData {

  private val TestRequestTimeout = 500.millis
  private val DownstreamDelay    = 5.seconds

  private val wireMockServer = WireMockServer(options().dynamicPort())

  override given patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  override def fakeApplication(): Application = {
    wireMockServer.start()

    GuiceApplicationBuilder()
      .configure(
        "microservice.services.hip.protocol"            -> "http",
        "microservice.services.hip.host"                -> "localhost",
        "microservice.services.hip.port"                -> wireMockServer.port(),
        "microservice.services.hip.clientId"            -> "some-client-id",
        "microservice.services.hip.secret"              -> "some-client-secret",
        "microservice.services.tax-enrolments.protocol" -> "http",
        "microservice.services.tax-enrolments.host"     -> "localhost",
        "microservice.services.tax-enrolments.port"     -> wireMockServer.port(),
        "play.ws.timeout.request"                       -> TestRequestTimeout.toString
      )
      .disable[PlayMongoModule]
      .build()
  }

  override def afterAll(): Unit = {
    wireMockServer.stop()
    super.afterAll()
  }

  private lazy val etmpConnector = app.injector.instanceOf[EtmpSubscriptionConnector]
  private lazy val dpsConnector  = app.injector.instanceOf[DpsConnector]
  private lazy val signUpService = app.injector.instanceOf[SignUpService]

  private given HeaderCarrier = HeaderCarrier()

  private def stubSlowResponse(url: String): Unit =
    wireMockServer.stubFor(
      any(urlEqualTo(url))
        .willReturn(aResponse().withStatus(201).withFixedDelay(DownstreamDelay.toMillis.toInt))
    )

  "the downstream request timeout" should {
    "be 20 seconds, with a 6 second connection timeout, as inherited from bootstrap's backend.conf" in {
      val config = Configuration.load(Environment.simple())

      config.get[Duration]("play.ws.timeout.request") shouldBe 20.seconds
      config.get[Duration]("play.ws.timeout.connection") shouldBe 6.seconds
    }
  }

  "EtmpSubscriptionConnector" should {
    "fail with GatewayTimeoutException when ETMP does not respond within the request timeout" in {
      stubSlowResponse("/etmp/RESTAdapter/dsao/subscription")

      etmpConnector
        .signUp(generateSignUpRequest(seed = 1))
        .failed
        .futureValue shouldBe a[GatewayTimeoutException]
    }
  }

  "DpsConnector" should {
    "fail with GatewayTimeoutException when DPS does not respond within the request timeout" in {
      stubSlowResponse("/dapm/subscriptions/some-subscription-id")

      dpsConnector
        .replaceSaoSubscription("some-subscription-id", generateSignUpRequest(seed = 1))
        .failed
        .futureValue shouldBe a[GatewayTimeoutException]
    }
  }

  "SignUpService" should {
    "report the downstream service as unavailable when the call times out" in {
      stubSlowResponse("/etmp/RESTAdapter/dsao/subscription")

      signUpService.signUp(generateSignUpRequest(seed = 1)).futureValue shouldBe SignUpResult.Failed(
        DownstreamService.ETMP,
        Outcome.Unavailable,
        "unreachable: GatewayTimeoutException"
      )
    }
  }
}
