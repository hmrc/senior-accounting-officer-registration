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

package uk.gov.hmrc.senioraccountingofficerregistration

import uk.gov.hmrc.senioraccountingofficerregistration.models.*

import scala.util.Random

trait TestData {

  protected def utr(seed: Int): String =
    f"${new Random(seed).nextLong(10000000000L)}%010d"

  protected def crn(seed: Int): String =
    f"${new Random(seed).nextLong(100000000L)}%08d"

  protected def generateContacts(): List[Contact] =
    List(
      Contact("contact 1", "contact1@example.com", "active", "en-GB"),
      Contact("contact 2", "contact2@example.com", "active", "en-GB")
    )

  protected def generateNominatedCompany(seed: Int): NominatedCompany =
    NominatedCompany("example company", utr(seed + 1), crn(seed + 2))

  protected def generateSignUpRequest(seed: Int): SignUpRequest = {
    SignUpRequest(
      etmpSafeId = "etmpSafeId",
      contacts = generateContacts(),
      nominatedCompany = generateNominatedCompany(seed)
    )
  }

  protected def generateEtmpSuccessResponse(seed: Int): EtmpSuccessResponse =
    EtmpSuccessResponse(
      Success(
        processingDate = "example date",
        dsaoIdNumber = s"SAOABC${crn(seed)}"
      )
    )
}
