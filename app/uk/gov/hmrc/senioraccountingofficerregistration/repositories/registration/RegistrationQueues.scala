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

import play.api.Configuration
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep

import scala.concurrent.ExecutionContext

import java.time.Clock
import javax.inject.{Inject, Singleton}

// Each orchestrated step has a distinct repository instance and collection.
@Singleton
class RegistrationQueues @Inject() (mongo: MongoComponent, configuration: Configuration, clock: Clock)(using
    ExecutionContext
) {

  val all: Map[RegistrationStep, RegistrationWorkRepository] = RegistrationStep.values.iterator.map { step =>
    step -> new RegistrationWorkRepository(step, mongo, configuration, clock)
  }.toMap

  def apply(step: RegistrationStep): RegistrationWorkRepository = all(step)
}
