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

class EmailStatusSpec extends AnyWordSpec with Matchers {

  case class Test(es: EmailStatus)

  object Test {
    given OFormat[Test] = Json.format
  }

  "Json reader for EmailStatus" must {
    EmailStatus.values.foreach { emailStatus =>
      s"return a EmailStatus when the value is $emailStatus" in {
        val result = Json
          .parse(s"""
               |{
               | "es" : "$emailStatus"
               |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Right(Test(emailStatus))
      }
    }

    "return an error" when {
      "the value is not one of the enum value with message INVALID_ENUM_VALUE" in {
        val result = Json
          .parse("""
              |{
              | "es" : "123456789"
              |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Left(List((JsPath.\("es"), List(JsonValidationError("INVALID_ENUM_VALUE")))))
      }
    }
  }
}
