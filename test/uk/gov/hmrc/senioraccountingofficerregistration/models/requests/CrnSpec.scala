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

class CrnSpec extends AnyWordSpec with Matchers with TestData {

  case class Test(crn: Crn)
  object Test {
    given OFormat[Test] = Json.format
  }

  "Json reader for Crn" must {
    "return a Crn when the value is valid" in {
      val testCrn = generateCrn
      val result  = Json
        .parse(s"""
          |{
          | "crn" : "$testCrn"
          |}""".stripMargin)
        .validate[Test]

      result.asEither mustBe Right(Test(Crn(testCrn)))
    }

    "return an error" when {
      "the value is too short with message CANNOT_BE_EMPTY" in {
        val result = Json
          .parse("""
              |{
              | "crn" : ""
              |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Left(List((JsPath.\("crn"), List(JsonValidationError("CANNOT_BE_EMPTY")))))
      }

      "the value is too long with message INVALID_FORMAT" in {
        val result = Json
          .parse("""
              |{
              | "crn" : "123456789"
              |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Left(List((JsPath.\("crn"), List(JsonValidationError("INVALID_FORMAT")))))
      }
    }
  }
}
