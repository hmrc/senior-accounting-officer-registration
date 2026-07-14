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

package uk.gov.hmrc.senioraccountingofficerregistration.connectors

import play.api.http.HeaderNames
import play.api.http.Status.CREATED
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.http.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.senioraccountingofficerregistration.config.AppConfig
import uk.gov.hmrc.senioraccountingofficerregistration.models.{ReplaceSaoSubscriptionRequest, SignUpRequest}

import scala.concurrent.{ExecutionContext, Future}

import java.time.Clock
import javax.inject.{Inject, Singleton}

@Singleton
class DpsConnector @Inject() (httpClient: HttpClientV2, appConfig: AppConfig, clock: Clock)(using ExecutionContext) {

  def replaceSaoSubscription(saoSubscriptionId: String, signUpRequest: SignUpRequest)(using
      HeaderCarrier
  ): Future[Unit] = {
    val replaceRequest: ReplaceSaoSubscriptionRequest =
      ReplaceSaoSubscriptionRequest(signUpRequest.etmpSafeId, signUpRequest.nominatedCompany, signUpRequest.contacts)
    httpClient
      .put(url"${appConfig.dpsReplaceSaoSubscriptionUrl}/${saoSubscriptionId}")
      .setHeader(
        HeaderNames.AUTHORIZATION -> appConfig.dpsReplacementSaoSubscriptionAuthorization
      )
      .withBody(Json.toJson(replaceRequest))
      .execute[HttpResponse]
      .map {
        case response if response.status == CREATED => ()
        case response                               =>
          throw UpstreamErrorResponse(
            s"DPS api returned ${response.status}",
            response.status
          )
      }
  }
}
