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

package uk.gov.hmrc.senioraccountingofficerregistration.services

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.{EtmpSubscriptionConnector, TaxEnrolmentsConnector}
import uk.gov.hmrc.senioraccountingofficerregistration.models.{SignUpRequest, SignUpResponse, TaxEnrolmentRequest}

import scala.concurrent.{ExecutionContext, Future}

import javax.inject.{Inject, Singleton}

@Singleton
class SignUpService @Inject() (
    etmpSubscriptionConnector: EtmpSubscriptionConnector,
    taxEnrolmentsConnector: TaxEnrolmentsConnector
)(using ExecutionContext) {

  def signUp(signUpRequest: SignUpRequest)(using HeaderCarrier): Future[SignUpResponse] =
    for {
      signUpResponse <- etmpSubscriptionConnector.signUp(signUpRequest)
      _              <- taxEnrolmentsConnector.enrol(TaxEnrolmentRequest.dsao(signUpRequest, signUpResponse))
    } yield signUpResponse
}
