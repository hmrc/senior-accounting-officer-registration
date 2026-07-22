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

import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.*
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.senioraccountingofficerregistration.config.AppConfig
import uk.gov.hmrc.senioraccountingofficerregistration.models.{EtmpSubscriptionRequest, SignUpRequest}

import scala.concurrent.{ExecutionContext, Future}

import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}

@Singleton
class EtmpSubscriptionConnector @Inject() (httpClient: HttpClientV2, appConfig: AppConfig, clock: Clock)(using
    ExecutionContext
) {

  def signUp(signUpRequest: SignUpRequest, correlationId: String)(using HeaderCarrier): Future[HttpResponse] =
    httpClient
      .post(url"${appConfig.etmpSubscriptionUrl}")
      .withBody(Json.toJson(EtmpSubscriptionRequest("UTR", signUpRequest.nominatedCompany.utr)))
      .setHeader(
        HeaderNames.AUTHORIZATION -> appConfig.etmpSubscriptionAuthorization,
        "X-Transmitting-System"   -> "HIP",
        "X-Originating-System"    -> "MDTP",
        "CorrelationId"           -> correlationId,
        "X-Receipt-Date" -> DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS))
      )
      .execute[HttpResponse]
}
