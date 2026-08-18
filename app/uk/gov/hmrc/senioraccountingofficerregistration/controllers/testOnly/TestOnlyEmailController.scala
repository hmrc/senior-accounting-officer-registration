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

package uk.gov.hmrc.senioraccountingofficerregistration.controllers.testOnly

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.*
import uk.gov.hmrc.senioraccountingofficerregistration.models.{EmailTemplate, EtmpSuccessResponse, Success}
import uk.gov.hmrc.senioraccountingofficerregistration.services.EmailService

import scala.concurrent.ExecutionContext

import javax.inject.{Inject, Singleton}

@Singleton()
class TestOnlyEmailController @Inject() (
    cc: ControllerComponents,
    emailService: EmailService
)(using ExecutionContext)
    extends BackendController(cc) {

  private val testEtmpSafeId  = "XE0000123456789"
  private val testCompanyName = "Test Company Ltd"
  private val testUtr         = "1234567890"
  private val testCrn         = "12345678"
  private val testReferenceId = "XDSAO000000001"

  def sendEmail(email: String, name: Option[String]): Action[AnyContent] = Action.async { request =>
    given HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

    val recipientName = name.map(_.trim).filter(_.nonEmpty).getOrElse(TestOnlyEmailController.nameFrom(email))

    emailService
      .sendEmail(
        EmailTemplate.RegistrationConfirmation,
        signUpRequest(email, recipientName),
        EtmpSuccessResponse(Success(processingDate = "2026-01-01T00:00:00Z", dsaoIdNumber = testReferenceId))
      )
      .map(_ =>
        Accepted(
          Json.obj(
            "sentTo"     -> email,
            "name"       -> recipientName,
            "templateId" -> EmailTemplate.RegistrationConfirmation.templateId
          )
        )
      )
  }

  private def signUpRequest(email: String, recipientName: String): SignUpRequest =
    SignUpRequest(
      etmpSafeId = EtmpSafeId(testEtmpSafeId),
      nominatedCompany = NominatedCompany(CompanyName(testCompanyName), Utr(testUtr), Crn(testCrn)),
      contacts = Contacts(
        List(Contact(PersonName(recipientName), Email(email), EmailStatus.valid, Language.`en-GB`))
      )
    )
}

object TestOnlyEmailController {

  def nameFrom(email: String): String =
    email.takeWhile(_ != '@').split("[._+-]+").filter(_.nonEmpty).map(_.capitalize).mkString(" ") match {
      case ""   => "QA Tester"
      case name => name
    }
}
