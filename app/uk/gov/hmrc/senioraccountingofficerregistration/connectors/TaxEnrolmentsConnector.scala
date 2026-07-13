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

import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.*
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.senioraccountingofficerregistration.config.AppConfig
import uk.gov.hmrc.senioraccountingofficerregistration.models.TaxEnrolmentRequest

import scala.concurrent.{ExecutionContext, Future}

import javax.inject.{Inject, Singleton}

@Singleton
class TaxEnrolmentsConnector @Inject() (httpClient: HttpClientV2, appConfig: AppConfig)(using ExecutionContext) {

  def enrol(request: TaxEnrolmentRequest)(using HeaderCarrier): Future[Unit] =
    httpClient
      .put(url"${appConfig.taxEnrolmentsDsaoEnrolmentUrl}")
      .withBody(Json.toJson(request))
      .execute[HttpResponse]
      .map {
        case response if response.status >= 200 && response.status < 300 => ()
        case response                                                    =>
          throw UpstreamErrorResponse(
            s"Tax enrolments API returned ${response.status}",
            response.status
          )
      }
}
