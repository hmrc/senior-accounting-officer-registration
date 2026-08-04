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
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.services.SignUpService.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

import javax.inject.{Inject, Singleton}

@Singleton
class SignUpService @Inject() (
    etmpSubscriptionConnector: EtmpSubscriptionConnector,
    taxEnrolmentsConnector: TaxEnrolmentsConnector,
    dpsConnector: DpsConnector
)(using ExecutionContext) {

  def signUp(signUpRequest: SignUpRequest)(using HeaderCarrier): Future[SignUpResult] =
    (for {
      accepted <- EitherT(
        etmpSubscriptionConnector
          .signUp(signUpRequest)
          .map(sanitiseEtmp)
          .recover(unreachable(DownstreamService.ETMP))
      )
      subscriptionId = accepted.response.success.dsaoIdNumber
      _ <- EitherT(
        dpsConnector
          .replaceSaoSubscription(subscriptionId, signUpRequest)
          .map(sanitiseDps)
          .recover(unreachable(DownstreamService.DPS))
      )
      _ <- EitherT(
        taxEnrolmentsConnector
          .enrol(TaxEnrolmentRequest(signUpRequest, accepted.response))
          .map(sanitiseTaxEnrolments)
          .recover(unreachable(DownstreamService.TAX_ENROLMENTS))
      )
    } yield accepted.result(subscriptionId)).merge[SignUpResult]

  private def sanitiseEtmp(response: HttpResponse): Either[SignUpResult & Failure, EtmpAccepted] =
    response.status match {
      case CREATED =>
        Try(Json.parse(response.body).as[EtmpSuccessResponse]).toEither
          .leftMap(_ => etmpFailure(Outcome.MalformedResponse, s"status=${response.status} unparsable response body"))
          .map(EtmpAccepted(_, alreadySubscribed = None))
      case UNPROCESSABLE_ENTITY => sanitiseEtmpUnprocessable(response)
      case status               =>
        Left(SignUpResult.Failed(DownstreamService.ETMP, outcomeFor(status), etmpDetail(response)))
    }

  private def sanitiseEtmpUnprocessable(response: HttpResponse): Either[SignUpResult & Failure, EtmpAccepted] =
    Try(Json.parse(response.body).as[EtmpErrorResponse]).toEither match {
      case Left(_) =>
        Left(etmpFailure(Outcome.MalformedResponse, s"status=${response.status} unparsable response body"))
      case Right(EtmpErrorResponse(errors)) =>
        val detail = s"status=${response.status} code=${errors.code}"
        if errors.code == EtmpErrors.AlreadySubscribed then
          errors.dsaoIdNumber match {
            case Some(dsaoIdNumber) =>
              Right(
                EtmpAccepted(
                  EtmpSuccessResponse(Success(errors.processingDate, dsaoIdNumber)),
                  alreadySubscribed = Some(s"$detail - continuing registration with dsaoIdNumber")
                )
              )
            case None => Left(etmpFailure(Outcome.Unprocessable, s"$detail dsaoIdNumber missing"))
          }
        else Left(etmpFailure(Outcome.Unprocessable, detail))
    }

  private def sanitiseDps(response: HttpResponse): Either[SignUpResult & Failure, Unit] =
    response.status match {
      case CREATED => Right(())
      case status  => Left(SignUpResult.Failed(DownstreamService.DPS, outcomeFor(status), downstreamDetail(response)))
    }

  private def sanitiseTaxEnrolments(response: HttpResponse): Either[SignUpResult & Failure, Unit] =
    response.status match {
      case NO_CONTENT => Right(())
      case status     =>
        Left(SignUpResult.Failed(DownstreamService.TAX_ENROLMENTS, outcomeFor(status), downstreamDetail(response)))
    }

  private def etmpFailure(outcome: Outcome, detail: String): SignUpResult & Failure =
    SignUpResult.Failed(DownstreamService.ETMP, outcome, detail)

  private def etmpDetail(response: HttpResponse): String =
    Try(Json.parse(response.body).as[EtmpSystemError]).toOption
      .fold(downstreamDetail(response))(systemError =>
        s"status=${response.status} origin=${systemError.origin} code=${systemError.response.error.code}" +
          s" logID=${systemError.response.error.logID}"
      )

  private def downstreamDetail(response: HttpResponse): String = {
    val status = s"status=${response.status}"
    Try(Json.parse(response.body).as[HipFailureResponse]).toOption
      .map { hipFailure =>
        val failures = hipFailure.response.failures.map(_.`type`).mkString(",")
        s"$status origin=${hipFailure.origin} failures=[$failures]"
      }
      .getOrElse(status)
  }

  private def unreachable[A](
      downstreamService: DownstreamService
  ): PartialFunction[Throwable, Either[SignUpResult & Failure, A]] = { case NonFatal(e) =>
    Left(
      SignUpResult.Failed(
        downstreamService,
        Outcome.Unavailable,
        s"unreachable: ${e.getClass.getSimpleName}"
      )
    )
  }
}

object SignUpService {

  private final case class EtmpAccepted(response: EtmpSuccessResponse, alreadySubscribed: Option[String]) {
    def result(subscriptionId: String): SignUpResult =
      alreadySubscribed.fold(SignUpResult.Success(subscriptionId))(
        SignUpResult.AlreadySubscribed(subscriptionId, _)
      )
  }

  enum DownstreamService {
    case ETMP, DPS, TAX_ENROLMENTS
  }

  enum Outcome(val logMessage: String) {
    case BadRequest        extends Outcome("BAD_REQUEST")
    case Unauthorised      extends Outcome("UNAUTHORIZED")
    case Forbidden         extends Outcome("FORBIDDEN")
    case Unprocessable     extends Outcome("UNPROCESSABLE_ENTITY")
    case DownstreamError   extends Outcome("INTERNAL_SERVER_ERROR")
    case Unavailable       extends Outcome("SERVICE_UNAVAILABLE")
    case MalformedResponse extends Outcome("MalformedResponse")
    case Misalignment      extends Outcome("Unknown")
  }

  sealed trait Failure

  enum SignUpResult {
    case Success(subscriptionId: String)
    case AlreadySubscribed(subscriptionId: String, detail: String)
    case Failed(downstreamService: DownstreamService, outcome: Outcome, detail: String) extends SignUpResult, Failure
  }

  private def outcomeFor(status: Int): Outcome =
    status match {
      case BAD_REQUEST           => Outcome.BadRequest
      case UNAUTHORIZED          => Outcome.Unauthorised
      case FORBIDDEN             => Outcome.Forbidden
      case UNPROCESSABLE_ENTITY  => Outcome.Unprocessable
      case INTERNAL_SERVER_ERROR => Outcome.DownstreamError
      case SERVICE_UNAVAILABLE   => Outcome.Unavailable
      case _                     => Outcome.Misalignment
    }
}
