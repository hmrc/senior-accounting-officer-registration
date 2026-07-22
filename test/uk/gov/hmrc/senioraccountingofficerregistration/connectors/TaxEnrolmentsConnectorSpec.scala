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
import uk.gov.hmrc.senioraccountingofficerregistration.models.{TaxEnrolmentKnownFact, TaxEnrolmentRequest}

class TaxEnrolmentsConnectorSpec
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
        "microservice.services.tax-enrolments.protocol" -> "http",
        "microservice.services.tax-enrolments.host"     -> "localhost",
        "microservice.services.tax-enrolments.port"     -> wireMockServer.port()
      )
      .disable[PlayMongoModule]
      .build()
  }

  override def afterAll(): Unit = {
    wireMockServer.stop()
    super.afterAll()
  }

  private given HeaderCarrier = HeaderCarrier()

  private lazy val connector = app.injector.instanceOf[TaxEnrolmentsConnector]

  private val request = TaxEnrolmentRequest(
    identifiers = Seq(TaxEnrolmentKnownFact("EtmpSubscriptionId", "SAOABC123456789")),
    verifiers = Seq(
      TaxEnrolmentKnownFact("CTUTR", "1234567890"),
      TaxEnrolmentKnownFact("CRN", "AB123456")
    )
  )

  "enrol" should {
    "put the DSAO enrolment request to tax-enrolments and return the raw 2xx response" in {
      wireMockServer.stubFor(
        put(urlEqualTo("/tax-enrolments/service/HMRC-DSAO-ORG/enrolment"))
          .withHeader(HeaderNames.CONTENT_TYPE, containing(MimeTypes.JSON))
          .withRequestBody(equalToJson(Json.stringify(Json.toJson(request))))
          .willReturn(aResponse().withStatus(Status.NO_CONTENT))
      )

      connector.enrol(request).futureValue.status shouldBe Status.NO_CONTENT
    }

    "return the raw response without throwing on a non-2xx status" in {
      wireMockServer.stubFor(
        put(urlEqualTo("/tax-enrolments/service/HMRC-DSAO-ORG/enrolment"))
          .willReturn(aResponse().withStatus(Status.INTERNAL_SERVER_ERROR))
      )

      connector.enrol(request).futureValue.status shouldBe Status.INTERNAL_SERVER_ERROR
    }
  }
}
