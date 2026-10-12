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

package uk.gov.hmrc.senioraccountingofficerregistration.services.registration

import com.codahale.metrics.{Gauge, MetricRegistry}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep.*
import uk.gov.hmrc.senioraccountingofficerregistration.repositories.registration.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import java.time.Clock
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.{Inject, Singleton}

@Singleton
class RegistrationWorker @Inject() (
    queues: RegistrationQueues,
    states: RegistrationStateRepository,
    operations: RegistrationOperations,
    config: Configuration,
    actorSystem: ActorSystem,
    metrics: MetricRegistry,
    clock: Clock
)(using ExecutionContext)
    extends Logging {

  private val queueCounts = TrieMap.empty[String, AtomicInteger]
  private val timeout     = config.get[Long]("work-items.operation-timeout-seconds").seconds
  private val lease       = config.get[Long]("work-items.lease-seconds")
  require(timeout.toSeconds < lease, "Operation timeout must be shorter than the work item lease")

  def processNext(step: RegistrationStep): Future[Unit] = {
    val repository = queues(step)
    repository.claim().flatMap {
      case Some(work) => process(repository, work)
      case None       => Future.unit
    }
  }

  private def process(repository: RegistrationWorkRepository, work: WorkItem[RegistrationCommand]): Future[Unit] = {
    given HeaderCarrier   = HeaderCarrier(extraHeaders = Seq("correlationId" -> work.item.data.correlationId))
    val heartbeatInterval = (lease / 3).seconds
    val heartbeat         = actorSystem.scheduler.scheduleWithFixedDelay(heartbeatInterval, heartbeatInterval) { () =>
      repository.heartbeat(work).recover { case NonFatal(_) => () }
      ()
    }
    val processing = for {
      _        <- if work.item.step == Etmp then states.initialize(work.item.data) else Future.unit
      finished <- if work.item.operationFinished then Future.successful(Some(work)) else perform(repository, work)
      _        <- finished.fold(Future.unit)(handoff(repository, _))
    } yield ()

    processing
      .recoverWith { case NonFatal(error) => retry(repository, work, error) }
      .andThen { case _ => heartbeat.cancel() }
  }

  private def handoff(repository: RegistrationWorkRepository, finished: WorkItem[RegistrationCommand]): Future[Unit] =
    for {
      _ <- repository.heartbeat(finished)
      // Results are durable before status publication or successor creation.
      _ <- publish(finished.item)
      _ <- Future.traverse(finished.item.successors) { next =>
        for {
          _ <- repository.heartbeat(finished)
          _ <- queues(next.step).enqueue(next)
        } yield ()
      }
      _ <- repository.finish(finished)
    } yield recordCompletion(finished.item)

  private def publish(command: RegistrationCommand): Future[Unit] = states.publish(command)

  private def recordCompletion(command: RegistrationCommand): Unit = {
    val metric = if command.failure.isDefined then "permanent-failure" else "completed"
    metrics.counter(s"registration.${command.step}.$metric").inc()
    logger.info(
      s"[Registration][${command.step}][$metric][OrchestrationId=${command.data.orchestrationId}][CorrelationId=${command.data.correlationId}]"
    )
  }

  private def retry(
      repository: RegistrationWorkRepository,
      work: WorkItem[RegistrationCommand],
      error: Throwable
  ): Future[Unit] = {
    val command = work.item
    metrics.counter(s"registration.${command.step}.retry").inc()
    logger.warn(
      s"[Registration][${command.step}][Retry][OrchestrationId=${command.data.orchestrationId}][CorrelationId=${command.data.correlationId}]",
      error
    )
    repository.retry(work, retryDelays.head, error.getClass.getSimpleName)
  }

  private val retryDelays = config.get[Seq[Long]]("work-items.retry-delays-seconds")
  require(retryDelays.nonEmpty && retryDelays.forall(_ > 0), "Retry delays must be positive")

  private def withTimeout[A](operation: => Future[A]): Future[A] =
    Future.firstCompletedOf(
      Seq(
        Future.unit.flatMap(_ => operation),
        after(timeout, actorSystem.scheduler)(Future.failed(new TimeoutException("Registration operation timed out")))
      )
    )

  private def perform(repository: RegistrationWorkRepository, work: WorkItem[RegistrationCommand])(using
      HeaderCarrier
  ): Future[Option[WorkItem[RegistrationCommand]]] = {
    val command = work.item
    // Count dispatches durably, including attempts interrupted by a process restart.
    if command.attempts >= retryDelays.size + 1 then
      repository
        .save(
          work,
          command
            .copy(operationFinished = true, failure = Some(command.failure.getOrElse(RegistrationFailures.unavailable)))
        )
        .map(Some(_))
    else
      for {
        attempted <- repository.save(work, command.copy(attempts = command.attempts + 1))
        result    <- withTimeout(operations.execute(attempted.item)).map(Right(_)).recover {
          case rejection: OperationRejected => Left(rejection)
          case NonFatal(_)                  => Left(OperationRejected(RegistrationFailures.unavailable, true))
        }
        saved <- result match {
          case Right(completed) => repository.save(attempted, completed.copy(failure = None)).map(Some(_))
          case Left(rejection) if !rejection.retriable || attempted.item.attempts >= retryDelays.size + 1 =>
            repository
              .save(attempted, attempted.item.copy(operationFinished = true, failure = Some(rejection.failure)))
              .map(Some(_))
          case Left(rejection) =>
            repository.save(attempted, attempted.item.copy(failure = Some(rejection.failure))).flatMap { saved =>
              repository
                .retry(saved, retryDelays(attempted.item.attempts - 1), rejection.failure.reason.toString)
                .map { _ =>
                  metrics.counter(s"registration.${command.step}.retry").inc()
                  None
                }
            }
        }
      } yield saved
  }

  private val retentionOrder = List(Etmp, Dps, TaxEnrolments, Email, Audit)

  // A parent only finishes after its successors are persisted. Read parents before successors,
  // awaiting each result, so a handoff cannot move active work behind the retention scan.

  private def hasActiveGraph(orchestrationId: String): Future[Boolean] = {
    def loop(remaining: List[RegistrationStep]): Future[Boolean] = remaining match {
      case Nil          => Future.successful(false)
      case step :: rest =>
        queues(step).hasActive(orchestrationId).flatMap {
          case true  => Future.successful(true)
          case false => loop(rest)
        }
    }
    loop(retentionOrder)
  }

  def maintain(): Future[Unit] = for {
    _          <- updateQueueMetrics()
    candidates <- queues(Etmp).retentionCandidates()
    _          <- Future.traverse(candidates) { work =>
      val orchestrationId = work.item.data.orchestrationId
      hasActiveGraph(orchestrationId).flatMap {
        case true  => Future.unit
        case false => expireGraph(orchestrationId)
      }
    }
  } yield ()

  private def updateQueueMetrics(): Future[Unit] =
    Future
      .traverse(queues.all.values.toSeq) { repository =>
        repository.metrics.map(_.foreach { (name, count) =>
          val value = queueCounts.getOrElseUpdate(
            name, {
              val counter = new AtomicInteger()
              metrics.register(s"registration.queue.$name", new Gauge[Int] { def getValue: Int = counter.get() })
              counter
            }
          )
          value.set(count)
        })
      }
      .map(_ => ())

  private def expireGraph(orchestrationId: String): Future[Unit] = {
    val root    = queues(Etmp)
    val expires = clock.instant().plusSeconds(config.get[Long]("work-items.retention-seconds"))
    for {
      persistedExpiry <- states.expire(orchestrationId, expires)
      _ <- Future.traverse(queues.all.values.filterNot(_ == root).toSeq)(_.expire(orchestrationId, persistedExpiry))
      _ <- root.expire(orchestrationId, persistedExpiry)
    } yield ()
  }
}
