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

package support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient

abstract class ISpecBase
    extends PlaySpec
    with ScalaFutures
    with IntegrationPatience
    with GuiceOneServerPerSuite
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  protected implicit lazy val wireMockServer: WireMockServer = {
    val server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
    server.start()
    server
  }

  lazy val wireMockPort: Int       = wireMockServer.port()
  val wireMockHost                 = "localhost"
  lazy val wireMockBaseUrlAsString = s"http://$wireMockHost:$wireMockPort"

  override def beforeAll(): Unit = {
    super.beforeAll()
    WireMock.configureFor(wireMockHost, wireMockPort)
    wireMockServer.start()
  }

  override def afterAll(): Unit = {
    wireMockServer.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    WireMock.reset()
  }

  def additionalConfigs: Map[String, Any] = Map.empty
  private def configs: Map[String, Any]   =
    Map(
      "microservice.services.auth.port"           -> wireMockPort.toString,
      "microservice.services.hip.port"            -> wireMockPort.toString,
      "microservice.services.tax-enrolments.port" -> wireMockPort.toString,
      "play.ws.followRedirects"                   -> "false"
    ) ++ additionalConfigs

  protected def applicationBuilder: GuiceApplicationBuilder = GuiceApplicationBuilder()

  final override def fakeApplication(): Application =
    applicationBuilder
      .configure(configs)
      .build()

  protected def wsClient: WSClient = app.injector.instanceOf[WSClient]
  protected def baseUrl            = s"http://localhost:$port"
}
