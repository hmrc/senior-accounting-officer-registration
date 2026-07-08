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

import uk.gov.hmrc.senioraccountingofficerregistration.models.{SignUpRequest, SignUpResponse}

import scala.util.Random

trait TestData {

  protected def utr(seed: Int): String =
    f"${new Random(seed).nextLong(10000000000L)}%010d"

  protected def crn(seed: Int): String =
    f"${new Random(seed).nextLong(100000000L)}%08d"

  protected def generatedSignUpRequest(seed: Int): SignUpRequest =
    SignUpRequest(
      idType = "UTR",
      idNumber = utr(seed),
      ctutr = utr(seed + 1),
      crn = crn(seed + 2)
    )

  protected def generatedSignUpResponse(seed: Int): SignUpResponse =
    SignUpResponse(s"SAOABC${crn(seed)}")
}
