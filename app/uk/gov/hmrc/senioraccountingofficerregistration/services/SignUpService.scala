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

import cats.data.EitherT
import cats.implicits.*
import play.api.http.Status.*
import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.{
  DpsConnector,
  EtmpSubscriptionConnector,
  TaxEnrolmentsConnector
}
import uk.gov.hmrc.senioraccountingofficerregistration.models.{EtmpSuccessResponse, SignUpRequest, TaxEnrolmentRequest}
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import javax.inject.{Inject, Singleton}

@Singleton
class SignUpService @Inject() (
    etmpSubscriptionConnector: EtmpSubscriptionConnector,
    taxEnrolmentsConnector: TaxEnrolmentsConnector,
    dpsConnector: DpsConnector
)(using ExecutionContext) {

  def signUp(signUpRequest: SignUpRequest, correlationId: String)(using HeaderCarrier): Future[SignUpResult] =
    (for {
      etmpSuccessResponse <- EitherT(etmpSubscriptionConnector.signUp(signUpRequest, correlationId).map(sanitiseEtmp))
      subscriptionId = etmpSuccessResponse.success.dsaoIdNumber
      _ <- EitherT(dpsConnector.replaceSaoSubscription(subscriptionId, signUpRequest).map(sanitiseDps))
      _ <- EitherT(
        taxEnrolmentsConnector.enrol(TaxEnrolmentRequest(signUpRequest, etmpSuccessResponse)).map(sanitiseTaxEnrolments)
      )
    } yield SignUpResult.Success(subscriptionId)).merge[SignUpResult]

  private def sanitiseEtmp(response: HttpResponse): Either[SignUpResult & Failure, EtmpSuccessResponse] =
    response match {
      case HttpResponse(CREATED, body, _) =>
        Try(Json.parse(body).as[EtmpSuccessResponse]).fold(
          _ => Left(SignUpResult.MalformedResponse(DownstreamService.ETMP)),
          Right(_)
        )
      case HttpResponse(status, _, _) => Left(toFailure(DownstreamService.ETMP, status))
    }

  private def sanitiseDps(response: HttpResponse): Either[SignUpResult & Failure, Unit] =
    response match {
      case HttpResponse(CREATED, _, _) => Right(())
      case HttpResponse(status, _, _)  => Left(toFailure(DownstreamService.DPS, status))
    }

  private def sanitiseTaxEnrolments(response: HttpResponse): Either[SignUpResult & Failure, Unit] =
    response.status match {
      case status if status >= 200 && status < 300 => Right(())
      case status                                  => Left(toFailure(DownstreamService.TAX_ENROLMENTS, status))
    }
}

object SignUpService {

  enum DownstreamService {
    case ETMP, DPS, TAX_ENROLMENTS
  }

  sealed trait Failure

  enum SignUpResult {
    case Success(subscriptionId: String)
    case MalformedResponse(downstreamService: DownstreamService)           extends SignUpResult, Failure
    case BadRequestFailure(downstreamService: DownstreamService)           extends SignUpResult, Failure
    case InternalServerFailure(downstreamService: DownstreamService)       extends SignUpResult, Failure
    case ServiceUnavailableFailure(downstreamService: DownstreamService)   extends SignUpResult, Failure
    case UnknownFailure(downstreamService: DownstreamService, status: Int) extends SignUpResult, Failure
  }

  private def toFailure(downstreamService: DownstreamService, status: Int): SignUpResult & Failure =
    status match {
      case BAD_REQUEST           => SignUpResult.BadRequestFailure(downstreamService)
      case INTERNAL_SERVER_ERROR => SignUpResult.InternalServerFailure(downstreamService)
      case SERVICE_UNAVAILABLE   => SignUpResult.ServiceUnavailableFailure(downstreamService)
      case other                 => SignUpResult.UnknownFailure(downstreamService, other)
    }
}
