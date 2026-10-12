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

package uk.gov.hmrc.senioraccountingofficerregistration.repositories.registration

import org.mongodb.scala.MongoWriteException
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.*
import play.api.Configuration
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.workitem.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*

import scala.concurrent.{ExecutionContext, Future}

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{Clock, Duration, Instant}
import java.util.UUID
import java.util.concurrent.TimeUnit

class RegistrationWorkRepository(
    val step: RegistrationStep,
    mongo: MongoComponent,
    configuration: Configuration,
    clock: Clock
)(using ExecutionContext)
    extends WorkItemRepository[RegistrationCommand](
      collectionName = s"registration-${step.toString.toLowerCase}",
      mongoComponent = mongo,
      itemFormat = summon[play.api.libs.json.Format[RegistrationCommand]],
      workItemFields = WorkItemFields.default.copy(availableAt = "availableAt"),
      extraIndexes = Seq(
        IndexModel(Indexes.ascending("item.commandKey"), IndexOptions().unique(true)),
        IndexModel(Indexes.ascending("item.data.orchestrationId")),
        IndexModel(Indexes.ascending("item.expiresAt"), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
      )
    ) {

  private val duplicateKeyErrorCode = 11000

  override def now(): Instant                 = clock.instant().truncatedTo(MILLIS)
  override val inProgressRetryAfter: Duration = Duration.ofSeconds(configuration.get[Long]("work-items.lease-seconds"))

  def enqueue(command: RegistrationCommand): Future[RegistrationCommand] = {
    require(command.step == step)
    val key = Filters.equal("item.commandKey", command.commandKey)
    // An expired request key can be reused even before Mongo's TTL monitor runs.
    for {
      _         <- collection.deleteOne(Filters.and(key, Filters.lte("item.expiresAt", now()))).toFuture()
      persisted <- pushNew(command).map(_.item).recoverWith {
        case error: MongoWriteException if error.getError.getCode == duplicateKeyErrorCode =>
          collection.find(key).head().map(_.item)
      }
    } yield persisted
  }

  def findRoot(scopeKey: String): Future[Option[WorkItem[RegistrationCommand]]] = collection
    .find(
      Filters.and(
        Filters.equal("item.commandKey", scopeKey),
        Filters.or(Filters.exists("item.expiresAt", false), Filters.gt("item.expiresAt", now()))
      )
    )
    .headOption()

  def claim(): Future[Option[WorkItem[RegistrationCommand]]] =
    pullOutstanding(now(), now()).flatMap {
      case None       => Future.successful(None)
      case Some(work) => claimLease(work)
    }

  private def claimLease(work: WorkItem[RegistrationCommand]): Future[Option[WorkItem[RegistrationCommand]]] =
    collection
      .findOneAndUpdate(
        Filters.and(
          Filters.equal("_id", work.id),
          Filters.equal("status", ProcessingStatus.InProgress),
          Filters.equal("updatedAt", work.updatedAt)
        ),
        Updates.set("item.leaseToken", UUID.randomUUID().toString),
        FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      )
      .toFutureOption()

  private def ownedBy(work: WorkItem[RegistrationCommand]): Bson = Filters.and(
    Filters.equal("_id", work.id),
    Filters.equal("status", ProcessingStatus.InProgress),
    Filters.equal(
      "item.leaseToken",
      work.item.leaseToken.getOrElse(throw new IllegalStateException("Work item has no lease token"))
    )
  )

  private def updateOwned(work: WorkItem[RegistrationCommand], updates: Bson): Future[Unit] =
    collection.updateOne(ownedBy(work), updates).toFuture().flatMap { result =>
      if result.getMatchedCount == 1 then Future.unit
      else Future.failed(new IllegalStateException("Work item lease lost"))
    }

  def heartbeat(work: WorkItem[RegistrationCommand]): Future[Unit] = updateOwned(work, Updates.set("updatedAt", now()))

  def save(work: WorkItem[RegistrationCommand], command: RegistrationCommand): Future[WorkItem[RegistrationCommand]] =
    updateOwned(work, Updates.combine(Updates.set("item", Codecs.toBson(command)), Updates.set("updatedAt", now())))
      .map(_ => work.copy(item = command))

  def finish(work: WorkItem[RegistrationCommand]): Future[Unit] = updateOwned(
    work,
    Updates.combine(
      Updates.set(
        "status",
        if work.item.failure.isDefined then ProcessingStatus.PermanentlyFailed else ProcessingStatus.Succeeded
      ),
      Updates.set("updatedAt", now())
    )
  )

  def retry(work: WorkItem[RegistrationCommand], delay: Long, error: String): Future[Unit] = {
    val updatedAt = now()
    updateOwned(
      work,
      Updates.combine(
        Updates.set("status", ProcessingStatus.Failed),
        Updates.set("availableAt", updatedAt.plusSeconds(delay)),
        Updates.set("updatedAt", updatedAt),
        Updates.set("item.lastError", error),
        Updates.inc("failureCount", 1)
      )
    )
  }

  def hasActive(orchestrationId: String): Future[Boolean] = collection
    .find(
      Filters.and(
        Filters.equal("item.data.orchestrationId", orchestrationId),
        Filters.nin("status", ProcessingStatus.Succeeded, ProcessingStatus.PermanentlyFailed)
      )
    )
    .headOption()
    .map(_.isDefined)

  def expire(orchestrationId: String, at: Instant): Future[Unit] = collection
    .updateMany(
      Filters.and(Filters.equal("item.data.orchestrationId", orchestrationId), Filters.exists("item.expiresAt", false)),
      Updates.set("item.expiresAt", at)
    )
    .toFuture()
    .map(_ => ())

  def retentionCandidates(): Future[Seq[WorkItem[RegistrationCommand]]] = collection
    .find(
      Filters.and(
        Filters.in("status", ProcessingStatus.Succeeded, ProcessingStatus.PermanentlyFailed),
        Filters.exists("item.expiresAt", false)
      )
    )
    .toFuture()
}
