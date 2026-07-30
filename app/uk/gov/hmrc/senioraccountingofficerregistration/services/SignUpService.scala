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
import play.api.Logging
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
)(using ExecutionContext)
    extends Logging {

  def signUp(signUpRequest: SignUpRequest)(using HeaderCarrier): Future[SignUpResult] =
    (for {
      etmpSuccessResponse <- EitherT(
        etmpSubscriptionConnector
          .signUp(signUpRequest)
          .map(sanitiseEtmp)
          .recover(unreachable(DownstreamService.ETMP))
      )
      subscriptionId = etmpSuccessResponse.success.dsaoIdNumber
      _ <- EitherT(
        dpsConnector
          .replaceSaoSubscription(subscriptionId, signUpRequest)
          .map(sanitiseDps)
          .recover(unreachable(DownstreamService.DPS))
      )
      _ <- EitherT(
        taxEnrolmentsConnector
          .enrol(TaxEnrolmentRequest(signUpRequest, etmpSuccessResponse))
          .map(sanitiseTaxEnrolments)
          .recover(unreachable(DownstreamService.TAX_ENROLMENTS))
      )
    } yield SignUpResult.Success(subscriptionId)).merge[SignUpResult]

  private def sanitiseEtmp(response: HttpResponse)(using
      HeaderCarrier
  ): Either[SignUpResult & Failure, EtmpSuccessResponse] =
    response.status match {
      case CREATED =>
        Try(Json.parse(response.body).as[EtmpSuccessResponse]).toEither
          .leftMap(_ => etmpFailure(Outcome.Misalignment, s"status=${response.status} unparsable response body"))
      case UNPROCESSABLE_ENTITY => sanitiseEtmpUnprocessable(response)
      case status               =>
        Left(SignUpResult.Failed(DownstreamService.ETMP, outcomeFor(status), etmpDetail(response)))
    }

  private def sanitiseEtmpUnprocessable(response: HttpResponse)(using
      HeaderCarrier
  ): Either[SignUpResult & Failure, EtmpSuccessResponse] =
    Try(Json.parse(response.body).as[EtmpErrorResponse]).toEither match {
      case Left(_) =>
        Left(etmpFailure(Outcome.Misalignment, s"status=${response.status} unparsable response body"))
      case Right(EtmpErrorResponse(errors)) =>
        val detail = s"status=${response.status} code=${errors.code} text=${errors.text}"
        if errors.code == EtmpErrors.AlreadySubscribed then
          errors.dsaoIdNumber match {
            case Some(dsaoIdNumber) =>
              logger.warn(
                s"[SignUp][${DownstreamService.ETMP}][Business Partner already subscribed]" +
                  s"[CorrelationId=$correlationId] $detail - continuing registration with dsaoIdNumber"
              )
              Right(EtmpSuccessResponse(Success(errors.processingDate, dsaoIdNumber)))
            case None => Left(etmpFailure(Outcome.Misalignment, s"$detail dsaoIdNumber missing"))
          }
        else Left(etmpFailure(Outcome.Misalignment, detail))
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
          s" message=${systemError.response.error.message} logID=${systemError.response.error.logID}"
      )

  private def downstreamDetail(response: HttpResponse): String = {
    val status = s"status=${response.status}"
    Try(Json.parse(response.body).as[HipFailureResponse]).toOption
      .map { hipFailure =>
        val failures = hipFailure.response.failures.map(f => s"${f.`type`}:${f.reason}").mkString(",")
        s"$status origin=${hipFailure.origin} failures=[$failures]"
      }
      .orElse(Option(response.body).filter(_.nonEmpty).map(body => s"$status body=${body.take(MaxLoggedBody)}"))
      .getOrElse(status)
  }

  private def correlationId(using hc: HeaderCarrier): String =
    hc.extraHeaders.toMap.getOrElse("correlationId", "unknown")

  private def unreachable[A](
      downstreamService: DownstreamService
  ): PartialFunction[Throwable, Either[SignUpResult & Failure, A]] = { case NonFatal(e) =>
    Left(
      SignUpResult.Failed(
        downstreamService,
        Outcome.Unavailable,
        s"unreachable: ${e.getClass.getSimpleName}: ${e.getMessage}"
      )
    )
  }
}

object SignUpService {

  private val MaxLoggedBody = 500

  enum DownstreamService {
    case ETMP, DPS, TAX_ENROLMENTS
  }

  enum Outcome(val logMessage: String) {
    case BadRequest      extends Outcome("Bad Request")
    case Unauthorised    extends Outcome("Unauthorised")
    case Forbidden       extends Outcome("Forbidden")
    case DownstreamError extends Outcome("Downstream Internal Server Error")
    case Unavailable     extends Outcome("Service unavailable")
    case Misalignment    extends Outcome("Downstream service misalignment")
  }

  sealed trait Failure

  enum SignUpResult {
    case Success(subscriptionId: String)
    case Failed(downstreamService: DownstreamService, outcome: Outcome, detail: String) extends SignUpResult, Failure
  }

  private def outcomeFor(status: Int): Outcome =
    status match {
      case BAD_REQUEST           => Outcome.BadRequest
      case UNAUTHORIZED          => Outcome.Unauthorised
      case FORBIDDEN             => Outcome.Forbidden
      case INTERNAL_SERVER_ERROR => Outcome.DownstreamError
      case SERVICE_UNAVAILABLE   => Outcome.Unavailable
      case _                     => Outcome.Misalignment
    }
}
