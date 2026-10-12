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

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.*
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*

import scala.concurrent.{ExecutionContext, Future}

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

@Singleton
class RegistrationStateRepository @Inject() (mongo: MongoComponent, clock: Clock)(using ExecutionContext)
    extends PlayMongoRepository[RegistrationState](
      collectionName = "registration-orchestrations",
      mongoComponent = mongo,
      domainFormat = summon[play.api.libs.json.OFormat[RegistrationState]],
      indexes = Seq(
        IndexModel(Indexes.ascending("scopeKey")),
        IndexModel(Indexes.ascending("expiresAt"), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
      )
    ) {

  def initialize(data: RegistrationData): Future[Unit] = collection
    .updateOne(
      Filters.equal("_id", data.orchestrationId),
      Updates.setOnInsert("scopeKey", data.scopeKey),
      UpdateOptions().upsert(true)
    )
    .toFuture()
    .map(_ => ())

  def get(scopeKey: String): Future[Option[RegistrationState]] = collection
    .find(
      Filters.and(
        Filters.equal("scopeKey", scopeKey),
        Filters.or(Filters.exists("expiresAt", false), Filters.gt("expiresAt", clock.instant()))
      )
    )
    .headOption()

  def publish(command: RegistrationCommand): Future[Unit] = {
    val updates = publicationUpdates(command)
    if updates.isEmpty then Future.unit
    else
      collection
        .updateOne(
          Filters.and(Filters.equal("_id", command.data.orchestrationId), Filters.exists("terminalAt", false)),
          Updates.combine(updates*)
        )
        .toFuture()
        .flatMap { result =>
          if result.getMatchedCount > 0 then Future.unit
          else ensureStateExists(command.data.orchestrationId)
        }
  }

  private def publicationUpdates(command: RegistrationCommand): Seq[Bson] =
    if !command.step.blocking then Seq.empty
    else
      command.failure match {
        case Some(failure) =>
          Seq(Updates.set("failure", Codecs.toBson(failure)), Updates.set("terminalAt", clock.instant()))
        case None if command.step == RegistrationStep.TaxEnrolments =>
          Seq(
            Updates.set("subscriptionId", command.data.etmpResponse.get.success.dsaoIdNumber),
            Updates.set("terminalAt", command.data.completedAt.get)
          )
        case None => Seq.empty
      }

  private def ensureStateExists(orchestrationId: String): Future[Unit] =
    collection.find(Filters.equal("_id", orchestrationId)).headOption().flatMap {
      case Some(_) => Future.unit // Already published; handoff is replayable.
      case None    => Future.failed(new IllegalStateException("Missing registration state"))
    }

  def expire(id: String, at: Instant): Future[Instant] = collection
    .findOneAndUpdate(
      Filters.and(Filters.equal("_id", id), Filters.exists("expiresAt", false)),
      Updates.set("expiresAt", at),
      FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    )
    .toFutureOption()
    .flatMap {
      case Some(state) => Future.successful(expiryOf(state))
      case None        =>
        collection
          .find(Filters.equal("_id", id))
          .headOption()
          .map {
            case Some(state) => expiryOf(state)
            case None        => throw new IllegalStateException("Missing terminal registration state")
          }
    }

  private def expiryOf(state: RegistrationState): Instant =
    state.expiresAt.getOrElse(throw new IllegalStateException("Missing terminal registration state"))
}
