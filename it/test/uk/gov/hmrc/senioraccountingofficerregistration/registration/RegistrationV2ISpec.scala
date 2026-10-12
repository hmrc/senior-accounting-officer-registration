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

package uk.gov.hmrc.senioraccountingofficerregistration.registration

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSRequest
import support.{ISpecBase, MockDpsHelper, MockEtmpHelper, MockTaxEnrolmentHelper}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.senioraccountingofficerregistration.SignupIntegrationSpec.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep.*
import uk.gov.hmrc.senioraccountingofficerregistration.repositories.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.registration.RegistrationWorker
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future

class RegistrationV2ISpec extends ISpecBase with MockitoSugar {

  private val databaseName   = s"sao-registration-http-test-${UUID.randomUUID()}"
  private val correlationId  = UUID.randomUUID().toString
  private val subscriptionId = "SAOABC123456789"
  private val clock          = new Clock {
    private val millis = new AtomicLong(System.currentTimeMillis())

    override def instant(): Instant            = Instant.ofEpochMilli(millis.incrementAndGet())
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
  }

  private lazy val audit                                             = mock[AuditConnector]
  override protected def applicationBuilder: GuiceApplicationBuilder =
    super.applicationBuilder.overrides(bind[Clock].toInstance(clock), bind[AuditConnector].toInstance(audit))

  override def additionalConfigs: Map[String, Any] = Map(
    "mongodb.uri"                      -> s"mongodb://localhost:27017/$databaseName",
    "microservice.services.email.port" -> wireMockPort,
    "work-items.enabled"               -> false
  )
  private lazy val queues = app.injector.instanceOf[RegistrationQueues]
  private lazy val states = app.injector.instanceOf[RegistrationStateRepository]
  private lazy val worker = app.injector.instanceOf[RegistrationWorker]

  private def endpoint(path: String = ""): WSRequest = wsClient
    .url(s"$baseUrl/senior-accounting-officer-registration/v2/sign-up$path")
    .withHttpHeaders("Authorization" -> "Bearer caller-token", "correlationId" -> correlationId)

  private def auth(user: String = "user", enrolled: Boolean = false): Unit = {
    val enrolments =
      if enrolled then Json.arr(Json.obj("key" -> "HMRC-DSAO-ORG", "identifiers" -> Json.arr(), "state" -> "Activated"))
      else Json.arr()

    stubFor(
      post(urlEqualTo("/auth/authorise"))
        .willReturn(okJson(Json.obj("internalId" -> user, "allEnrolments" -> enrolments).toString))
    )
    ()
  }

  private def run(step: RegistrationStep): Unit = worker.processNext(step).futureValue

  override def beforeEach(): Unit = {
    super.beforeEach()
    queues.all.values.foreach { repository =>
      repository.initialised.futureValue
      repository.collection.deleteMany(Filters.empty()).toFuture().futureValue
    }
    states.initialised.futureValue
    states.collection.deleteMany(Filters.empty()).toFuture().futureValue
    reset(audit)
    when(audit.sendEvent(any())(using any(), any())).thenReturn(Future.successful(AuditResult.Success))
    auth()
  }

  override def afterAll(): Unit = {
    val cleanup = MongoComponent(s"mongodb://localhost:27017/$databaseName")
    try cleanup.database.drop().toFuture().futureValue
    finally {
      cleanup.client.close()
      super.afterAll()
    }
  }

  "The v2 routes" should {
    "accept durably and expose success after the blocking steps, preserving correlation IDs" in {
      MockEtmpHelper.mockEtmpOk(subscriptionId)
      MockDpsHelper.mockDpsOk(subscriptionId)
      MockTaxEnrolmentHelper.mockTaxEnrolmentOk()
      stubFor(post(urlEqualTo("/hmrc/email")).willReturn(aResponse().withStatus(202)))
      val submitted = endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue
      submitted.status mustBe 202
      submitted.json mustBe Json.obj("idempotencyKey" -> "testKey")
      endpoint("/testKey").get().futureValue.status mustBe 204
      run(Etmp); run(Dps); run(TaxEnrolments)
      auth(enrolled = true)
      val completed = endpoint("/testKey").get().futureValue
      completed.status mustBe 200
      completed.json mustBe Json.obj("subscriptionId" -> subscriptionId)
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 202
      run(Email); run(Audit)
      MockEtmpHelper.verifyEtmpWasCalled(correlationId)
      MockDpsHelper.verifyDpsWasCalled(subscriptionId, correlationId)
      MockTaxEnrolmentHelper.verifyTaxEnrolmentWasCalled(correlationId)
      verify(
        1,
        postRequestedFor(urlEqualTo("/hmrc/email"))
          .withHeader("correlationId", equalTo(correlationId))
          .withRequestBody(matchingJsonPath("$.parameters.referenceId", equalTo(subscriptionId)))
      )
      verify(
        1,
        putRequestedFor(urlEqualTo("/tax-enrolments/service/HMRC-DSAO-ORG/enrolment"))
          .withHeader("Authorization", absent())
      )
      org.mockito.Mockito.verify(audit).sendEvent(any())(using any(), any())
    }

    "reject missing or invalid keys and malformed signup data" in {
      endpoint().post(validRequestBodyAsString).futureValue.status mustBe 400
      val body = Json.parse(validRequestBodyAsStringWithIdempotency).as[JsObject]
      Seq("", " ", "a" * 257).foreach { key =>
        endpoint().post((body ++ Json.obj("idempotencyKey" -> key)).toString).futureValue.status mustBe 400
      }
      endpoint().post("{").futureValue.status mustBe 400
      endpoint()
        .withHttpHeaders("Authorization" -> "Bearer caller-token")
        .post(validRequestBodyAsStringWithIdempotency)
        .futureValue
        .status mustBe 400
    }

    "return a persisted legacy error for permanent tax enrolment failure" in {
      MockEtmpHelper.mockEtmpOk(subscriptionId)
      MockDpsHelper.mockDpsOk(subscriptionId)
      stubFor(
        put(urlEqualTo("/tax-enrolments/service/HMRC-DSAO-ORG/enrolment")).willReturn(aResponse().withStatus(401))
      )
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 202
      run(Etmp); run(Dps); run(TaxEnrolments)
      val failed = endpoint("/testKey").get().futureValue
      failed.status mustBe 500
      failed.json mustBe Json.obj("reason" -> "SERVICE_MISCONFIGURATION")
      verify(0, postRequestedFor(urlEqualTo("/hmrc/email")))
    }

    "keep transient ETMP failures pending" in {
      MockEtmpHelper.mockEtmpFailure(subscriptionId, 503)
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 202
      run(Etmp)
      endpoint("/testKey").get().futureValue.status mustBe 204
      MockDpsHelper.verifyDpsWasCalled(subscriptionId, correlationId, 0)
    }

    "scope request keys to the authenticated owner" in {
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 202
      auth("someone-else")
      endpoint("/testKey").get().futureValue.status mustBe 404
      endpoint("/unknown").get().futureValue.status mustBe 404
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 202
    }

    "reject new registrations for enrolled users and requests without an authenticated identity" in {
      auth(enrolled = true)
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 403
      stubFor(post(urlEqualTo("/auth/authorise")).willReturn(okJson("{\"allEnrolments\":[]}")))
      endpoint().post(validRequestBodyAsStringWithIdempotency).futureValue.status mustBe 401
      stubFor(
        post(urlEqualTo("/auth/authorise")).willReturn(
          aResponse().withStatus(401).withHeader("WWW-Authenticate", "MDTP detail=\"MissingBearerToken\"")
        )
      )
      endpoint("/testKey").get().futureValue.status mustBe 401
    }
  }
}
