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
      failure = RegistrationFailure(httpStatus = httpStatus, reason = reason),
      retriable = status == 408 || status == 429 || status >= 500 && status <= 599
    )
  }

  val malformed: OperationRejected =
    OperationRejected(
      failure = RegistrationFailure(httpStatus = 502, reason = Reason.DOWNSTREAM_SERVICE_MISALIGNMENT),
      retriable = false
    )
  val unavailable: RegistrationFailure =
    RegistrationFailure(httpStatus = 502, reason = Reason.DOWNSTREAM_SERVICE_UNAVAILABLE)
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

  private val expectedEtmpStatus          = 201
  private val expectedDpsStatus           = 201
  private val expectedTaxEnrolmentsStatus = 204
  private val expectedEmailStatus         = 202

  private def required[A](value: Option[A], field: String): A =
    value.getOrElse(throw new IllegalStateException(s"Missing required registration field: $field"))

  private def check(response: HttpResponse, expected: Int): Unit =
    if response.status != expected then throw RegistrationFailures.response(response.status)

  def execute(command: RegistrationCommand)(using HeaderCarrier): Future[RegistrationCommand] = {
    val data      = command.data
    val operation = Future.unit.flatMap { _ =>
      command.step match {
        case Etmp =>
          etmp.signUp(data.request).map { response =>
            check(response, expectedEtmpStatus)
            val accepted =
              Try(Json.parse(response.body).as[EtmpSuccessResponse]).getOrElse(throw RegistrationFailures.malformed)
            command.copy(data = data.copy(etmpResponse = Some(accepted)))
          }
        case Dps =>
          val etmpResponse = required(data.etmpResponse, "etmpResponse")
          dps
            .replaceSaoSubscription(etmpResponse.success.dsaoIdNumber, data.request)
            .map { response =>
              check(response, expectedDpsStatus)
              command
            }
        case TaxEnrolments =>
          val etmpResponse = required(data.etmpResponse, "etmpResponse")
          taxEnrolments
            .enrol(TaxEnrolmentRequest(data.request, etmpResponse))
            .map { response =>
              check(response, expectedTaxEnrolmentsStatus)
              command.copy(data = data.copy(authorization = None, completedAt = Some(clock.instant())))
            }
        case Email =>
          email.postEmail(required(command.email, "email")).map { response =>
            check(response, expectedEmailStatus)
            command
          }
        case Audit =>
          val etmpResponse = required(data.etmpResponse, "etmpResponse")
          val completedAt  = required(data.completedAt, "completedAt")
          val eventDetails = Map(
            "etmpSubscriptionId" -> etmpResponse.success.dsaoIdNumber,
            "etmpSafeId"         -> data.orchestrationId,

            "nominatedCompanyUtr"  -> data.request.nominatedCompany.utr.value,
            "nominatedCompanyCrn"  -> data.request.nominatedCompany.crn.value,
            "nominatedCompanyName" -> data.request.nominatedCompany.name.value
          ) ++
            data.request.contacts.value
              .take(2)
              .zipWithIndex
              .flatMap { case (contact, index) =>
                val prefix = s"contact${index + 1}"
                List(
                  s"${prefix}Name"     -> contact.name.value,
                  s"${prefix}Email"    -> contact.email.value,
                  s"${prefix}Language" -> contact.language.toString
                )
              }
              .toMap

          audit
            .sendEvent(
              DataEvent(
                auditProvider = Some("mdtp"),
                auditSource = "senior-accounting-officer",
                auditType = "SubscriptionSubmitted",
                eventId = data.orchestrationId,
                generatedAt = completedAt,
                detail = eventDetails,
                tags = Map(
                  "clientIP"     -> data.auditData.tags.clientIp,
                  "X-Request-ID" -> data.auditData.tags.xRequestId,
                  "deviceId"     -> data.auditData.tags.deviceId,
                  "clientPort"   -> data.auditData.tags.clientPort,
                  "path"         -> data.auditData.tags.path,
                  "X-Session-ID" -> data.auditData.tags.xSessionId
                )
              )
            )
            .map {
              case AuditResult.Success | AuditResult.Disabled => command
              case _: AuditResult.Failure => throw OperationRejected(RegistrationFailures.unavailable, true)
            }
      }
    }
    operation.map(_.copy(operationFinished = true)).recoverWith { case error: UpstreamErrorResponse =>
      Future.failed(RegistrationFailures.response(error.statusCode))
    }
  }
}
