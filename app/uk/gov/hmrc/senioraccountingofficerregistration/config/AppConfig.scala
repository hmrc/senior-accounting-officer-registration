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

package uk.gov.hmrc.senioraccountingofficerregistration.config

import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.{Inject, Singleton}

@Singleton
class AppConfig @Inject() (servicesConfig: ServicesConfig, config: Configuration) {

  val appName: String = config.get[String]("appName")

  val etmpSubscriptionUrl: String =
    s"${servicesConfig.baseUrl("hip")}/RESTAdapter/dsao/subscription"

  val dpsReplaceSaoSubscriptionUrl: String =
    s"${servicesConfig.baseUrl("hip")}/subscriptions/"

  val taxEnrolmentsDsaoEnrolmentUrl: String =
    s"${servicesConfig.baseUrl("tax-enrolments")}/tax-enrolments/service/HMRC-DSAO-ORG/enrolment"

  private val hipClientId: String =
    config.get[String]("microservice.services.hip.clientId")

  private val hipClientSecret: String =
    config.get[String]("microservice.services.hip.secret")

  val etmpSubscriptionAuthorization: String =
    s"Basic ${Base64.getEncoder.encodeToString(s"$hipClientId:$hipClientSecret".getBytes(StandardCharsets.UTF_8))}"
    
  val dpsReplacementSaoSubscriptionAuthorization: String =
    s"Basic ${Base64.getEncoder.encodeToString(s"$hipClientId:$hipClientSecret".getBytes(StandardCharsets.UTF_8))}"
}
