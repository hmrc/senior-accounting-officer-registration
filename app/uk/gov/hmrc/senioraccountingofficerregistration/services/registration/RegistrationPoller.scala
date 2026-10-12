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

import org.apache.pekko.actor.{ActorSystem, Cancellable}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logging}
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Provider, Singleton}

@Singleton
class RegistrationPoller @Inject() (
    config: Configuration,
    system: ActorSystem,
    worker: Provider[RegistrationWorker],
    lifecycle: ApplicationLifecycle
)(using ExecutionContext)
    extends Logging {

  private val stopped                 = new AtomicBoolean(false)
  private val tasks: Seq[Cancellable] =
    if config.get[Boolean]("work-items.enabled") then {
      val interval = config.get[Long]("work-items.poll-interval-seconds").seconds
      RegistrationStep.values.toSeq.map(step => schedule(interval)(worker.get().processNext(step))) :+
        schedule(1.minute)(worker.get().maintain())
    } else Seq.empty

  lifecycle.addStopHook { () =>
    stopped.set(true)
    tasks.foreach(_.cancel())
    Future.unit
  }

  private def schedule(interval: FiniteDuration)(operation: => Future[Unit]): Cancellable = {
    val busy = new AtomicBoolean(false)
    system.scheduler.scheduleWithFixedDelay(5.seconds, interval) { () =>
      if !stopped.get() && busy.compareAndSet(false, true) then
        Future.unit
          .flatMap(_ => operation)
          .recover { case NonFatal(error) => logger.error("[Registration][PollerFailed]", error) }
          .andThen { case _ => busy.set(false) }
      ()
    }
  }
}
