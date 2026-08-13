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

class ContactSpec extends AnyWordSpec with Matchers with TestData {

  case class Test(contact: Contact)

  object Test {
    given OFormat[Test] = Json.format
  }

  "Json reader for Contact" must {
    "return a Contact when the value is valid" in {
      val testContact = Contact(
        name = PersonName(generateAlphanumeric(PersonName.maxPersonNameLength)),
        email = Email(s"${generateAlphanumeric(Email.maxEmailLength - 2)}@a"),
        status = EmailStatus.valid,
        language = Language.`en-GB`
      )
      val testContactAsJson =
        s"""
           |{
           | "name": "${testContact.name.value}",
           | "email": "${testContact.email.value}",
           | "status": "valid",
           | "language": "en-GB"
           |}""".stripMargin

      val r = Json
        .parse(s"""
             |{
             | "contact" : $testContactAsJson
             |}""".stripMargin)
        .validate[Test]

      r.asEither mustBe Right(Test(testContact))
    }

  }
}
