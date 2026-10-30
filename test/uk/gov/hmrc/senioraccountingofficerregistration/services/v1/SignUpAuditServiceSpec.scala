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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.senioraccountingofficerregistration.TestData
import uk.gov.hmrc.senioraccountingofficerregistration.models.registration.{AuditInputData, AuditTags}
import uk.gov.hmrc.senioraccountingofficerregistration.models.requests.Contacts

import scala.concurrent.{ExecutionContext, Future}

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

class SignUpAuditServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar with TestData {
  private given ExecutionContext = ExecutionContext.global
  private given HeaderCarrier    = HeaderCarrier()
  private val clock              = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
  private val request            = generateSignUpRequest(1)
  private val response           = generateEtmpSuccessResponse(4)

  "Subscription auditing" should {
    for count <- Seq(1, 2) do {
      s"emit the exact event for $count contacts" in {
        val connector = mock[AuditConnector]
        when(connector.sendEvent(any())(using any(), any())).thenReturn(Future.successful(AuditResult.Success))
        val service   = new SignUpAuditService(connector, clock)
        val input     = request.copy(contacts = Contacts(request.contacts.value.take(count)))
        val auditData = AuditInputData
          .create("/sign-up")
          .copy(tags = AuditTags("request", "session", "device", "ip", "port", "/sign-up"))
        service.auditSubscriptionSubmitted(input, response, auditData).futureValue shouldBe ()
        val captor = ArgumentCaptor.forClass(classOf[DataEvent])
        verify(connector).sendEvent(captor.capture())(using any(), any())
        val event = captor.getValue
        event.auditProvider shouldBe Some("mdtp")
        event.auditSource shouldBe "senior-accounting-officer"
        event.auditType shouldBe "SubscriptionSubmitted"
        UUID.fromString(event.eventId).toString shouldBe event.eventId
        event.generatedAt shouldBe clock.instant()
        event.tags shouldBe Map(
          "X-Request-ID" -> "request",
          "X-Session-ID" -> "session",
          "deviceID"     -> "device",
          "clientIP"     -> "ip",
          "clientPort"   -> "port",
          "path"         -> "/sign-up"
        )
        val expectedContacts = input.contacts.value.zipWithIndex.flatMap { case (contact, index) =>
          List(
            s"contact${index + 1}Name"     -> contact.name.value,
            s"contact${index + 1}Email"    -> contact.email.value,
            s"contact${index + 1}Language" -> contact.language.toString
          )
        }.toMap
        event.detail shouldBe Map(
          "etmpSubscriptionId"   -> response.success.dsaoIdNumber,
          "nominatedCompanyUtr"  -> input.nominatedCompany.utr.value,
          "nominatedCompanyCrn"  -> input.nominatedCompany.crn.value,
          "nominatedCompanyName" -> input.nominatedCompany.name.value
        ) ++ expectedContacts
      }
    }

    "default absent request headers and generate separate event identities" in {
      val connector = mock[AuditConnector]
      when(connector.sendEvent(any())(using any(), any())).thenReturn(Future.successful(AuditResult.Success))
      val service = new SignUpAuditService(connector, clock)
      (1 to 2).foreach(_ =>
        service.auditSubscriptionSubmitted(request, response, AuditInputData.create("/sign-up")).futureValue
      )
      val captor = ArgumentCaptor.forClass(classOf[DataEvent])
      verify(connector, times(2)).sendEvent(captor.capture())(using any(), any())
      captor.getValue.tags shouldBe Map(
        "X-Request-ID" -> "-",
        "X-Session-ID" -> "-",
        "deviceID"     -> "-",
        "clientIP"     -> "-",
        "clientPort"   -> "-",
        "path"         -> "/sign-up"
      )
      captor.getAllValues.get(0).eventId should not be captor.getAllValues.get(1).eventId
    }

    for (name, result) <- Seq(
        "disabled" -> Future.successful(AuditResult.Disabled),
        "rejected" -> Future.successful(AuditResult.Failure("unavailable")),
        "failed"   -> Future.failed(new RuntimeException("private payload"))
      )
    do {
      s"complete normally when delivery is $name" in {
        val connector = mock[AuditConnector]
        when(connector.sendEvent(any())(using any(), any())).thenReturn(result)
        new SignUpAuditService(connector, clock)
          .auditSubscriptionSubmitted(request, response, AuditInputData.create("/sign-up"))
          .futureValue shouldBe ()
      }
    }

    "contain synchronous connector exceptions" in {
      val connector = mock[AuditConnector]
      when(connector.sendEvent(any())(using any(), any())).thenThrow(new IllegalStateException("private payload"))
      new SignUpAuditService(connector, clock)
        .auditSubscriptionSubmitted(request, response, AuditInputData.create("/sign-up"))
        .futureValue shouldBe ()
    }
  }
}
