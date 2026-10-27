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

package uk.gov.hmrc.senioraccountingofficerregistration.services.v1

import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.senioraccountingofficerregistration.models.EtmpSuccessResponse
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.AuditInputData
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.SignUpRequest

import java.time.Clock
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class SignUpAuditService @Inject() (auditConnector: AuditConnector, clock: Clock)(using ExecutionContext)
    extends Logging {

  def auditSubscriptionSubmitted(
      request: SignUpRequest,
      response: EtmpSuccessResponse,
      auditData: AuditInputData
  )(using hc: HeaderCarrier): Future[Unit] = {
    val correlationId = hc.extraHeaders
      .collectFirst {
        case (name, value) if name.equalsIgnoreCase("correlationId") => value
      }
      .getOrElse("not-provided")

    def warn(reason: String): Unit =
      logger.warn(s"Unable to audit subscription submission: $reason [CorrelationId=$correlationId]")

    try {
      val details = Map(
        "etmpSubscriptionId"   -> response.success.dsaoIdNumber,
        "nominatedCompanyUtr"  -> request.nominatedCompany.utr.value,
        "nominatedCompanyCrn"  -> request.nominatedCompany.crn.value,
        "nominatedCompanyName" -> request.nominatedCompany.name.value
      ) ++ request.contacts.value
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
      val tags = auditData.tags
      auditConnector
        .sendEvent(
          DataEvent(
            auditProvider = Some(auditData.auditProvider),
            auditSource = auditData.auditSource,
            auditType = auditData.auditType,
            eventId = UUID.randomUUID().toString,
            generatedAt = clock.instant(),
            detail = details,
            tags = Map(
              "clientIP"     -> tags.clientIp,
              "X-Request-ID" -> tags.xRequestId,
              "deviceID"     -> tags.deviceId,
              "clientPort"   -> tags.clientPort,
              "path"         -> tags.path,
              "X-Session-ID" -> tags.xSessionId
            )
          )
        )
        .map {
          case AuditResult.Success | AuditResult.Disabled => ()
          case _: AuditResult.Failure                     => warn("delivery failed")
        }
        .recover { case NonFatal(error) => warn(error.getClass.getSimpleName) }
    } catch {
      case NonFatal(error) =>
        warn(error.getClass.getSimpleName)
        Future.successful(())
    }
  }
}
