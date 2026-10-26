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

import com.codahale.metrics.MetricRegistry
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.model.Filters
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.Helpers.*
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep.*
import uk.gov.hmrc.senioraccountingofficerregistration.repositories.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.registration.*
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.HeaderCarrier

class RegistrationOrchestrationISpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with IntegrationPatience
    with MockitoSugar
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with TestData {

  given ActorSystem = ActorSystem("registration-repository-test")

  given ExecutionContext = summon[ActorSystem].dispatcher

  given HeaderCarrier = HeaderCarrier()

  private class TestClock extends Clock {

    private val millis = new AtomicLong(System.currentTimeMillis())

    def advance(seconds: Long): Unit = {
      millis.addAndGet(seconds * 1000)
      ()
    }

    override def instant(): Instant            = Instant.ofEpochMilli(millis.incrementAndGet())
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
  }

  private val clock  = new TestClock
  private val mongo  = MongoComponent(s"mongodb://localhost:27017/sao-registration-test-${UUID.randomUUID()}")
  private val config = Configuration(
    "work-items.lease-seconds"             -> 120,
    "work-items.operation-timeout-seconds" -> 1,
    "work-items.retry-delays-seconds"      -> Seq(60, 300, 900, 3600),
    "work-items.retention-seconds"         -> 604800
  )

  private val queues     = new RegistrationQueues(mongo, config, clock)
  private val states     = new RegistrationStateRepository(mongo, clock)
  private val operations = mock[RegistrationOperations]
  private val worker     = new RegistrationWorker(
    queues,
    states,
    operations,
    config,
    summon[ActorSystem],
    new MetricRegistry,
    clock
  )

  private val service   = new RegistrationService(queues, states, clock)
  private val request   = generateSignUpRequest(1).copy(idempotencyKey = Some("key"))
  private val auditData = AuditInputData.create("auditPath")
  private val accepted  = generateEtmpSuccessResponse(4)

  override def beforeAll(): Unit = {
    super.beforeAll()
    queues.all.values.foreach(_.initialised.futureValue)
    states.initialised.futureValue
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    queues.all.values.foreach(_.collection.deleteMany(Filters.empty()).toFuture().futureValue)
    states.collection.deleteMany(Filters.empty()).toFuture().futureValue
    reset(operations)
    when(operations.execute(any())(using any())).thenAnswer { invocation =>
      val command = invocation.getArgument[RegistrationCommand](0)
      val data    = command.step match {
        case Etmp          => command.data.copy(etmpResponse = Some(accepted))
        case TaxEnrolments => command.data.copy(completedAt = Some(clock.instant()))
        case _             => command.data
      }
      Future.successful(command.copy(data = data, operationFinished = true))
    }
  }

  override def afterAll(): Unit = {
    try mongo.database.drop().toFuture().futureValue
    finally {
      mongo.client.close()
      summon[ActorSystem].terminate().futureValue
      super.afterAll()
    }
  }

  private def run(step: RegistrationStep): Unit = worker.processNext(step).futureValue

  private def entries(step: RegistrationStep): Seq[WorkItem[RegistrationCommand]] =
    queues(step).collection.find().toFuture().futureValue

  private def submit(): Unit =
    status(service.submit("user", false, "correlation", request, None, auditData)) shouldBe ACCEPTED

  private def blockingSteps(): Unit = {
    submit()
    run(Etmp)
    run(Dps)
    run(TaxEnrolments)
  }

  "Durable registration" should {
    "persist success, copy ETMP output and independently schedule confirmations" in {
      blockingSteps()
      contentAsJson(service.status("user", "key")) shouldBe Json.obj("subscriptionId" -> accepted.success.dsaoIdNumber)
      entries(Dps).head.item.data.etmpResponse shouldBe Some(accepted)
      entries(TaxEnrolments).head.item.data.etmpResponse shouldBe Some(accepted)
      entries(Email) should have size 2
      entries(Audit) should have size 1
      entries(Email).foreach(_.status shouldBe ProcessingStatus.ToDo)
    }

    "deduplicate simultaneous requests and reject conflicting or invalid requests" in {
      val responses =
        Future
          .sequence((1 to 8).map(_ => service.submit("user", false, "correlation", request, None, auditData)))
          .futureValue
      responses.map(_.header.status).distinct shouldBe Seq(ACCEPTED)
      entries(Etmp) should have size 1
      status(
        service.submit(
          "user",
          false,
          "correlation",
          request.copy(etmpSafeId =
            uk.gov.hmrc.senioraccountingofficerregistration.models.requests.EtmpSafeId("other")
          ),
          None,
          auditData
        )
      ) shouldBe CONFLICT
      status(
        service.submit("user", false, "correlation", request.copy(idempotencyKey = None), None, auditData)
      ) shouldBe BAD_REQUEST
      status(
        service.submit("user", false, "correlation", request.copy(idempotencyKey = Some(" ")), None, auditData)
      ) shouldBe BAD_REQUEST
      status(
        service.submit("user", false, "correlation", request.copy(idempotencyKey = Some("a" * 257)), None, auditData)
      ) shouldBe BAD_REQUEST
    }

    "allow an enrolled owner to replay and poll while isolating users" in {
      blockingSteps()
      status(service.submit("user", true, "correlation", request, None, auditData)) shouldBe ACCEPTED
      status(service.status("user", "key")) shouldBe OK
      status(service.status("other", "key")) shouldBe NOT_FOUND
      status(service.submit("other", true, "correlation", request, None, auditData)) shouldBe FORBIDDEN
      status(service.submit("other", false, "correlation", request, None, auditData)) shouldBe ACCEPTED
      entries(Etmp) should have size 2
    }

    "schedule each retry delay and fail after exactly five attempts" in {
      doReturn(Future.failed(RegistrationFailures.response(503, RegistrationStep.Etmp))).when(operations).execute(any())(using any())
      submit()
      Seq(60L, 300L, 900L, 3600L).zipWithIndex.foreach { (delay, index) =>
        run(Etmp)
        val work = entries(Etmp).head
        work.item.attempts shouldBe index + 1
        work.status shouldBe ProcessingStatus.Failed
        java.time.Duration.between(work.updatedAt, work.availableAt).getSeconds shouldBe delay
        run(Etmp)
        entries(Etmp).head.item.attempts shouldBe index + 1
        status(service.status("user", "key")) shouldBe NO_CONTENT
        clock.advance(delay + 1)
      }
      run(Etmp)
      entries(Etmp).head.status shouldBe ProcessingStatus.PermanentlyFailed
      entries(Etmp).head.item.attempts shouldBe 5
      entries(Dps) shouldBe empty
      status(service.status("user", "key")) shouldBe BAD_GATEWAY
      verify(operations, times(5)).execute(any())(using any())
    }

    "stop immediately on a permanent tax enrolment failure" in {
      submit()
      run(Etmp)
      run(Dps)
      doReturn(Future.failed(RegistrationFailures.response(401, RegistrationStep.TaxEnrolments))).when(operations).execute(any())(using any())
      run(TaxEnrolments)
      status(service.status("user", "key")) shouldBe INTERNAL_SERVER_ERROR
      entries(Email) shouldBe empty
      entries(Audit) shouldBe empty
    }

    "preserve success when both non-blocking queues exhaust retries" in {
      blockingSteps()
      doReturn(Future.failed(new RuntimeException("offline"))).when(operations).execute(any())(using any())
      (1 to 5).foreach { _ =>
        run(Email)
        run(Email)
        run(Audit)
        clock.advance(3601)
      }
      entries(Email).foreach(_.status shouldBe ProcessingStatus.PermanentlyFailed)
      entries(Audit).head.status shouldBe ProcessingStatus.PermanentlyFailed
      status(service.status("user", "key")) shouldBe OK
    }

    "recover a root accepted before state initialization" in {
      val data = RegistrationData.create("user", "correlation", request, clock.instant(), None, auditData)
      queues(Etmp).enqueue(RegistrationCommand.start(data)).futureValue
      run(Etmp)
      status(service.status("user", "key")) shouldBe NO_CONTENT
      entries(Dps) should have size 1
    }

    "resume a checkpointed handoff after lease expiry without repeating the connector" in {
      submit()
      val claimed  = queues(Etmp).claim().futureValue.value
      val finished =
        claimed.item.copy(operationFinished = true, data = claimed.item.data.copy(etmpResponse = Some(accepted)))
      queues(Etmp).save(claimed, finished).futureValue
      queues(Dps).enqueue(finished.successors.head).futureValue
      clock.advance(121)
      run(Etmp)
      entries(Dps) should have size 1
      entries(Etmp).head.status shouldBe ProcessingStatus.Succeeded
      verifyNoInteractions(operations)
    }

    "retry state publication without consuming another downstream attempt" in {
      submit()
      val unavailableStates = spy(states)
      doReturn(Future.failed(new RuntimeException("Mongo unavailable"))).when(unavailableStates).publish(any())
      val recoveringWorker = new RegistrationWorker(
        queues,
        unavailableStates,
        operations,
        config,
        summon[ActorSystem],
        new MetricRegistry,
        clock
      )
      recoveringWorker.processNext(Etmp).futureValue
      val checkpoint = entries(Etmp).head
      checkpoint.item.operationFinished shouldBe true
      checkpoint.item.attempts shouldBe 1
      checkpoint.status shouldBe ProcessingStatus.Failed
      entries(Dps) shouldBe empty
      reset(unavailableStates)
      clock.advance(61)
      recoveringWorker.processNext(Etmp).futureValue
      entries(Dps) should have size 1
      entries(Etmp).head.item.attempts shouldBe 1
      verify(operations, times(1)).execute(any())(using any())
    }

    "recover a partially persisted notification fanout without duplicating commands" in {
      submit()
      run(Etmp)
      run(Dps)
      val claimed   = queues(TaxEnrolments).claim().futureValue.value
      val completed = claimed.item.copy(
        operationFinished = true,
        data = claimed.item.data.copy(completedAt = Some(clock.instant()))
      )
      queues(TaxEnrolments).save(claimed, completed).futureValue
      states.publish(completed).futureValue
      queues(Audit).enqueue(completed.successors.find(_.step == Audit).value).futureValue
      worker.maintain().futureValue
      entries(Etmp).head.item.expiresAt shouldBe None
      clearInvocations(operations)
      clock.advance(121)
      run(TaxEnrolments)
      entries(Audit) should have size 1
      entries(Email) should have size 2
      status(service.status("user", "key")) shouldBe OK
      verifyNoInteractions(operations)
    }

    "retry timeouts and ignore a late completion of the timed-out attempt" in {
      submit()
      val response = scala.concurrent.Promise[RegistrationCommand]()
      doReturn(response.future).when(operations).execute(any())(using any())
      run(Etmp)
      entries(Etmp).head.status shouldBe ProcessingStatus.Failed
      status(service.status("user", "key")) shouldBe NO_CONTENT
      val timedOut = entries(Etmp).head.item
      response.success(
        timedOut.copy(operationFinished = true, data = timedOut.data.copy(etmpResponse = Some(accepted)))
      )
      entries(Etmp).head.item.operationFinished shouldBe false
      entries(Dps) shouldBe empty
    }

    "reject stale lease writes and claim an item only once" in {
      submit()
      val claims = Future.sequence((1 to 5).map(_ => queues(Etmp).claim())).futureValue.flatten
      claims should have size 1
      clock.advance(121)
      val fresh = queues(Etmp).claim().futureValue.value
      fresh.item.leaseToken should not be claims.head.item.leaseToken
      queues(Etmp).finish(claims.head).failed.futureValue shouldBe a[IllegalStateException]
      queues(Etmp).heartbeat(fresh).futureValue
    }

    "retain active notifications and expire the entire graph together only after completion" in {
      blockingSteps()
      worker.maintain().futureValue
      entries(Etmp).head.item.expiresAt shouldBe None
      run(Email); run(Email); run(Audit)
      worker.maintain().futureValue
      val expiry = entries(Etmp).head.item.expiresAt.value
      queues.all.keys.foreach(step => entries(step).foreach(_.item.expiresAt shouldBe Some(expiry)))
      states.get(RegistrationData.scopeKey("user", "key")).futureValue.value.expiresAt shouldBe Some(expiry)
      clock.advance(604801)
      status(service.status("user", "key")) shouldBe NOT_FOUND
      submit()
      entries(Etmp).head.item.expiresAt shouldBe None
    }

    "protect a successful terminal state from a later failure publication" in {
      blockingSteps()
      val tax = entries(TaxEnrolments).head.item
      states.publish(tax.copy(failure = Some(RegistrationFailures.unavailable))).futureValue
      status(service.status("user", "key")) shouldBe OK
    }

    "recover a transient failure without passing its error to successor commands" in {
      submit()
      doReturn(Future.failed(RegistrationFailures.response(503, RegistrationStep.Etmp))).when(operations).execute(any())(using any())
      run(Etmp); clock.advance(61)
      when(operations.execute(any())(using any())).thenAnswer { invocation =>
        val command = invocation.getArgument[RegistrationCommand](0)
        Future.successful(
          command.copy(operationFinished = true, data = command.data.copy(etmpResponse = Some(accepted)))
        )
      }
      run(Etmp)
      entries(Etmp).head.item.failure shouldBe None
      entries(Dps) should have size 1
    }
  }
}
