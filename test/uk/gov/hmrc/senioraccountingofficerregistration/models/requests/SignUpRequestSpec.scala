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

class SignUpRequestSpec extends AnyWordSpec with Matchers with TestData {

  "Json reader for SignUpRequest" must {

    "return a SignUpRequest when all of the fields exist" in {
      val testCompany = NominatedCompany(
        crn = Crn(generateCrn),
        utr = Utr(generateUtr),
        name = CompanyName(generateAlphanumeric(CompanyName.maxCompanyNameLength))
      )

      val testContact = Contact(
        name = PersonName(generateAlphanumeric(PersonName.maxPersonNameLength)),
        email = Email(s"${generateAlphanumeric(Email.maxEmailLength - 2)}@a"),
        status = EmailStatus.valid,
        language = Language.`en-GB`
      )
      val testContacts = Contacts(List(testContact))
      val etmpSafeId   = generateAlphanumeric(EtmpSafeId.maxEtmpSafeIdLength)

      val result = Json
        .parse(s"""
             |{
             | "contacts": ${Json.toJson(testContacts)},
             | "nominatedCompany" : ${Json.toJson(testCompany)},
             | "etmpSafeId": "$etmpSafeId"
             |}""".stripMargin)
        .validate[SignUpRequest]

      result.asEither mustBe Right(
        SignUpRequest(
          contacts = Contacts(List(testContact)),
          nominatedCompany = testCompany,
          etmpSafeId = EtmpSafeId(etmpSafeId)
        )
      )
    }

    "return an error when the contacts are empty" in {
      val testCompany = NominatedCompany(
        crn = Crn(generateCrn),
        utr = Utr(generateUtr),
        name = CompanyName(generateAlphanumeric(CompanyName.maxCompanyNameLength))
      )

      val etmpSafeId = generateAlphanumeric(EtmpSafeId.maxEtmpSafeIdLength)

      val result = Json
        .parse(s"""
             |{
             | "contacts": [],
             | "nominatedCompany" : ${Json.toJson(testCompany)},
             | "etmpSafeId": "$etmpSafeId"
             |}""".stripMargin)
        .validate[SignUpRequest]

      result.asEither mustBe Left(List((JsPath.\("contacts"), List(JsonValidationError("ARRAY_MIN_ITEMS_NOT_MET")))))
    }

  }

}
