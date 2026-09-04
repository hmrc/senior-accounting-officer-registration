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

package support

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import play.api.http.HeaderNames

object MockDpsHelper {
  def dpsUrl(subscriptionId: String) = s"/dapm/subscriptions/$subscriptionId"

  def testEtmpSafeId   = "testEtmpSafeId"
  def testCompanyName  = "testCompanyName"
  def testCrn          = "crn"
  def testUtr          = "utr"
  def testContact1Name = "name"

  def mockDpsOk(subscriptionId: String): StubMapping =
    stubFor(
      put(urlEqualTo(dpsUrl(subscriptionId)))
        .willReturn(
          aResponse()
            .withHeader(HeaderNames.CONTENT_TYPE, "application/json")
            .withBody(s"""{
                         |  "etmpSafeId": "$testEtmpSafeId",
                         |  "nominatedCompany": {
                         |    "name": "$testCompanyName",
                         |    "crn": "$testCrn",
                         |    "utr": "$testUtr"
                         |  },
                         |  "contacts": [
                         |    {
                         |      "name": "$testContact1Name",
                         |      "email": "$testContact1Name@example.com",
                         |      "status": "ACTIVE",
                         |      "language": "en-GB"
                         |    }
                         |  ]
                         |}""".stripMargin)
            .withStatus(201)
        )
    )

  def mockDpsFailure(subscriptionId: String, status: Int, body: String = ""): StubMapping =
    stubFor(
      put(urlEqualTo(dpsUrl(subscriptionId)))
        .willReturn(
          aResponse()
            .withHeader(HeaderNames.CONTENT_TYPE, "application/json")
            .withBody(body)
            .withStatus(status)
        )
    )

  def verifyDpsWasCalled(subscriptionId: String, correlationId: String, times: Int = 1): Unit =
    verify(
      times,
      putRequestedFor(urlEqualTo(dpsUrl(subscriptionId = subscriptionId)))
        .withHeader("correlationId", equalTo(correlationId))
    )

}
