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

import org.apache.pekko.util.ByteString
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.{BodyWritable, InMemoryBody}

object SignUpFixtures {
  implicit val stringAsJsonWriter: BodyWritable[String] =
    BodyWritable(str => InMemoryBody(ByteString.fromString(str)), "application/json")

  val validRequestBodyAsString: String =
    """{
      |  "etmpSafeId": "1234567890",
      |  "nominatedCompany": {
      |    "name": "Test Company Ltd PLC",
      |    "utr": "2233445567",
      |    "crn": "11223344"
      |  },
      |  "contacts": [
      |    {
      |      "name": "Jane Doe",
      |      "email": "jane.doe@example.com",
      |      "status": "valid",
      |      "language": "en-GB"
      |    }
      |  ]
      |}
      |""".stripMargin

  val validRequestBodyAsStringWithIdempotency: String =
    Json.stringify(Json.parse(validRequestBodyAsString).as[JsObject] ++ Json.obj("idempotencyKey" -> "testKey"))
}
