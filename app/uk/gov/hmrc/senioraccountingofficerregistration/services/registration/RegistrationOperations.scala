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

package uk.gov.hmrc.senioraccountingofficerregistration.services.registration

import play.api.libs.json.Json
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, UpstreamErrorResponse}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.senioraccountingofficerregistration.connectors.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.RegistrationStep.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import java.time.Clock
import javax.inject.{Inject, Singleton}

final case class OperationRejected(failure: RegistrationFailure, retriable: Boolean)
    extends RuntimeException(failure.reason.toString)

object RegistrationFailures {

  def response(status: Int): OperationRejected = {
    val (httpStatus, reason) = status match {
      case 400       => (500, Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)
      case 401 | 403 => (500, Reason.SERVICE_MISCONFIGURATION)
      case 500       => (502, Reason.DOWNSTREAM_SERVICE_ERROR)
      case 503       => (502, Reason.DOWNSTREAM_SERVICE_UNAVAILABLE)
      case _         => (502, Reason.DOWNSTREAM_SERVICE_MISALIGNMENT)
    }
    OperationRejected(
      RegistrationFailure(httpStatus, reason),
      status == 408 || status == 429 || status >= 500 && status <= 599
    )
  }

  val malformed: OperationRejected =
    OperationRejected(RegistrationFailure(502, Reason.DOWNSTREAM_SERVICE_MISALIGNMENT), false)
  val unavailable: RegistrationFailure = RegistrationFailure(502, Reason.DOWNSTREAM_SERVICE_UNAVAILABLE)
}

@Singleton
class RegistrationOperations @Inject() (
    etmp: EtmpSubscriptionConnector,
    dps: DpsConnector,
    taxEnrolments: TaxEnrolmentsConnector,
    email: EmailConnector,
    audit: AuditConnector,
    clock: Clock
)(using ExecutionContext) {

  private def check(response: HttpResponse, expected: Int): Unit =
    if response.status != expected then throw RegistrationFailures.response(response.status)

  def execute(command: RegistrationCommand)(using HeaderCarrier): Future[RegistrationCommand] = {
    val data      = command.data
    val operation = command.step match {
      case Etmp =>
        etmp.signUp(data.request).map { response =>
          check(response, 201)
          val accepted =
            Try(Json.parse(response.body).as[EtmpSuccessResponse]).getOrElse(throw RegistrationFailures.malformed)
          command.copy(data = data.copy(etmpResponse = Some(accepted)))
        }
      case Dps =>
        dps
          .replaceSaoSubscription(data.etmpResponse.get.success.dsaoIdNumber, data.request)
          .map { response =>
            check(response, 201)
            command
          }
      case TaxEnrolments =>
        taxEnrolments
          .enrol(TaxEnrolmentRequest(data.request, data.etmpResponse.get))
          .map { response =>
            check(response, 204)
            command.copy(data = data.copy(completedAt = Some(clock.instant())))
          }
      case Email =>
        email.postEmail(command.email.get).map { response =>
          check(response, 202)
          command
        }
      case Audit =>
        audit
          .sendEvent(
            DataEvent(
              auditSource = "senior-accounting-officer-registration",
              auditType = "RegistrationCompleted",
              eventId = data.orchestrationId,
              generatedAt = data.completedAt.get,
              detail = Map(
                "dsaoIdNumber"    -> data.etmpResponse.get.success.dsaoIdNumber,
                "orchestrationId" -> data.orchestrationId,
                "internalId"      -> data.internalId,
                "correlationId"   -> data.correlationId,
                "completedAt"     -> data.completedAt.get.toString
              )
            )
          )
          .map {
            case AuditResult.Success | AuditResult.Disabled => command
            case _: AuditResult.Failure => throw OperationRejected(RegistrationFailures.unavailable, true)
          }
    }
    operation.map(_.copy(operationFinished = true)).recoverWith { case error: UpstreamErrorResponse =>
      Future.failed(RegistrationFailures.response(error.statusCode))
    }
  }
}
