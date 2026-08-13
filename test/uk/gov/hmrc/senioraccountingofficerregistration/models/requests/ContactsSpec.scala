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

class ContactsSpec extends AnyWordSpec with Matchers with TestData {

  case class Test(contacts: Contacts)

  object Test {
    given OFormat[Test] = Json.format
  }

  "Json reader for Contacts" must {
    "return a Contacts when the value is valid" in {
      val testContact1 = Contact(
        name = PersonName(generateAlphanumeric(PersonName.maxPersonNameLength)),
        email = Email(s"${generateAlphanumeric(Email.maxEmailLength - 2)}@a"),
        status = EmailStatus.valid,
        language = Language.`en-GB`
      )

      val testContact2 = Contact(
        name = PersonName(generateAlphanumeric(PersonName.maxPersonNameLength)),
        email = Email(s"${generateAlphanumeric(Email.maxEmailLength - 2)}@a"),
        status = EmailStatus.valid,
        language = Language.`cy-GB`
      )

      val testCompanies = Json.arr(testContact1, testContact2)
      val result        = Json
        .parse(s"""
             |{
             | "contacts" : $testCompanies
             |}""".stripMargin)
        .validate[Test]

      result.asEither mustBe Right(Test(Contacts(List(testContact1, testContact2))))
    }

    "return an error" when {
      "the the list is empty with message ARRAY_MIN_ITEMS_NOT_MET" in {
        val result = Json
          .parse("""
              |{
              | "contacts" : []
              |}""".stripMargin)
          .validate[Test]

        result.asEither mustBe Left(List((JsPath.\("contacts"), List(JsonValidationError("ARRAY_MIN_ITEMS_NOT_MET")))))
      }
    }
  }
}
