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

import play.api.http.MimeTypes
import play.api.libs.ws.writeableOf_String
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.senioraccountingofficerregistration.config.AppConfig

import scala.concurrent.{ExecutionContext, Future}

import java.net.URL
import javax.inject.Inject

class EmailConnector @Inject() (appConfig: AppConfig, httpClientV2: HttpClientV2)(using ExecutionContext) {

  def postEmail(body: String, domain: String)(using HeaderCarrier): Future[HttpResponse] = {
    val url: URL = url"${appConfig.emailHost}/${domain}/email"
    httpClientV2.post(url).setHeader("Content-Type" -> MimeTypes.JSON).withBody(body).execute[HttpResponse]
  }
}
