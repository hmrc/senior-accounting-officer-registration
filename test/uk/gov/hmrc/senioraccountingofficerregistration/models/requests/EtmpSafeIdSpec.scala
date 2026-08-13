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

package uk.gov.hmrc.senioraccountingofficerregistration.models.requests

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.*
import uk.gov.hmrc.senioraccountingofficerregistration.TestData

class EtmpSafeIdSpec extends AnyWordSpec with Matchers with TestData {

  case class Test(id: EtmpSafeId)

  object Test {
    given OFormat[Test] = Json.format
  }

  "Json reader for EtmpSafeId" must {
    "return a EtmpSafeId when the value is valid" in {
      val testName = generateAlphanumeric(15)
      val result   = Json
        .parse(s"""
             |{
             | "id" : "$testName"
             |}""".stripMargin)
        .validate[Test]

      result.asEither mustBe Right(Test(EtmpSafeId(testName)))
    }

    "return an error" when {
      "the value is too short with message CANNOT_BE_EMPTY" in {
        val result = Json
          .parse("""
              |{
              | "id" : ""
              |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Left(List((JsPath.\("id"), List(JsonValidationError("CANNOT_BE_EMPTY")))))
      }

      "the value is too long with message INVALID_FORMAT" in {
        val result = Json
          .parse(s"""
              |{
              | "id" : "${generateAlphanumeric(16)}"
              |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Left(List((JsPath.\("id"), List(JsonValidationError("INVALID_FORMAT")))))
      }
    }
  }

}
