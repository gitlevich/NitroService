package com.gracenote.extraction.bbc.akka

import akka.actor._
import akka.testkit._
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.gracenote.extraction.bbc.akka.Coordinator.ScheduledProgram
import com.gracenote.extraction.bbc.akka.ScheduleFetcher.Protocol.StartUpForTest
import org.joda.time.DateTime
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import play.api.libs.ws._
import play.api.libs.ws.ning.NingWSClient
import org.mockito.Mockito._
import org.mockito.Matchers._
import play.mvc.Http.Status

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.xml._

class ScheduleFetcherSpec extends TestKit(ActorSystem("testSystem")) with WordSpecLike
                                  with Matchers with MockitoSugar
                                  with ImplicitSender with BeforeAndAfterEach with BeforeAndAfterAll {


  "When received ScheduleRequest fetcher" should {

    "respond with programs on HTTP status OK" in {
      when(scheduleResponse.status).thenReturn(Status.OK)
      fetcher ! cannedScheduleRequest

      expectMsg(cannedScheduleResponse)
    }

    "response should have expected page count" in {
      when(scheduleResponse.status).thenReturn(Status.OK)
      fetcher ! cannedScheduleRequest

      val response = receiveN(1).head.asInstanceOf[ScheduleResponse]
      response.totalPages shouldBe 1
    }

    "respond with retry request on HTTP status INTERNAL_SERVER_ERROR" in {
      when(scheduleResponse.status).thenReturn(Status.INTERNAL_SERVER_ERROR)
      fetcher ! cannedScheduleRequest

      expectMsg(cannedScheduleRequest.nextTry)
    }

    "respond with retry request on HTTP status REQUEST_TIMEOUT" in {
      when(scheduleResponse.status).thenReturn(Status.REQUEST_TIMEOUT)
      fetcher ! cannedScheduleRequest

      expectMsg(cannedScheduleRequest.nextTry)
    }

    "respond with retry request on HTTP status GATEWAY_TIMEOUT" in {
      when(scheduleResponse.status).thenReturn(Status.GATEWAY_TIMEOUT)
      fetcher ! cannedScheduleRequest

      expectMsg(cannedScheduleRequest.nextTry)
    }

    "respond with unrecoverable error on any other HTTP status" in {
      when(scheduleResponse.status).thenReturn(Status.BAD_REQUEST)
      fetcher ! cannedScheduleRequest

      expectMsg(UnrecoverableError(cannedScheduleRequest, ""))
    }

    "schedule no more than three retries after a recoverable server error response" in {
      when(scheduleResponse.status).thenReturn(Status.REQUEST_TIMEOUT)
      fetcher ! cannedScheduleRequest.copy(retryAttempt = ScheduleFetcher.maxRetries)

      expectNoMsg
    }
  }

  "When received ProgramAvailabilityRequest fetcher" should {
    "respond with ProgramAvailabilityResponse on HTTP status OK" in {
      when(scheduleResponse.status).thenReturn(Status.OK)
      fetcher ! cannedAvailabilityRequest

      expectMsg(cannedAvailabilityResponse)
    }

    "response should have expected page count" in {
      when(scheduleResponse.status).thenReturn(Status.OK)
      fetcher ! cannedAvailabilityRequest

      receiveN(1).head.asInstanceOf[ProgramAvailabilityResponse].isAvailable shouldBe true
    }

    "respond with retry request on HTTP status INTERNAL_SERVER_ERROR" in {
      when(scheduleResponse.status).thenReturn(Status.INTERNAL_SERVER_ERROR)
      fetcher ! cannedAvailabilityRequest

      expectMsg(cannedAvailabilityRequest.nextTry)
    }

    "respond with retry request on HTTP status REQUEST_TIMEOUT" in {
      when(scheduleResponse.status).thenReturn(Status.REQUEST_TIMEOUT)
      fetcher ! cannedAvailabilityRequest

      expectMsg(cannedAvailabilityRequest.nextTry)
    }

    "respond with retry request on HTTP status GATEWAY_TIMEOUT" in {
      when(scheduleResponse.status).thenReturn(Status.GATEWAY_TIMEOUT)
      fetcher ! cannedAvailabilityRequest

      expectMsg(cannedAvailabilityRequest.nextTry)
    }

    "respond that program is unavailable on any other HTTP status" in {
      when(scheduleResponse.status).thenReturn(Status.BAD_REQUEST)
      fetcher ! cannedAvailabilityRequest

      receiveN(1).head.asInstanceOf[ProgramAvailabilityResponse].isAvailable shouldBe false
    }

    "schedule no more than three retries after a recoverable server error response" in {
      when(scheduleResponse.status).thenReturn(Status.REQUEST_TIMEOUT)
      fetcher ! cannedAvailabilityRequest.copy(retryAttempt = ScheduleFetcher.maxRetries)

      expectNoMsg
    }

  }


  var fetcher: TestActorRef[ScheduleFetcher] = _
  var scheduleResponse: WSResponse = _

  override def beforeEach() = {
    scheduleResponse = mock[WSResponse]
    when(scheduleResponse.xml).thenReturn(XML.load(getClass.getClassLoader.getResourceAsStream("schedules.xml")))
    when(scheduleResponse.status).thenReturn(Status.OK)
    when(scheduleResponse.body).thenReturn("")
    val scheduleRequest = mock[WSRequest]
    when(scheduleRequest.get()).thenReturn(Future(scheduleResponse))
    when(scheduleRequest.withQueryString(any[(String, String)])).thenReturn(scheduleRequest)

    val availabilityResponse = mock[WSResponse]
    when(availabilityResponse.xml).thenReturn(XML.load(getClass.getClassLoader.getResourceAsStream("availability.xml")))
    when(availabilityResponse.status).thenReturn(Status.OK)
    when(availabilityResponse.body).thenReturn("")
    val availabilityRequest = mock[WSRequest]
    when(availabilityRequest.get()).thenReturn(Future(availabilityResponse))
    when(availabilityRequest.withQueryString(any[(String, String)])).thenReturn(scheduleRequest)

    val ws = mock[NingWSClient]
    when(ws.url(ScheduleFetcher.schedulesUrl)).thenReturn(scheduleRequest)
    when(ws.url(ScheduleFetcher.availabilityUrl)).thenReturn(availabilityRequest)

    fetcher = TestActorRef(Props(new ScheduleFetcher))
    fetcher ! StartUpForTest(ws)
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val cannedScheduleRequest = ScheduleRequest(
    "bbc_four_hd",
    DateTime.parse("2015-12-16T00:00:00.000-08:00"),
    DateTime.parse("2015-12-18T00:00:00.000-08:00"),
    pageToFetch = 1,
    retryAttempt = 0
  )

  val cannedScheduleResponse = ScheduleResponse(
    List(
      ScheduledProgram("bbc_four_hd","b06ssj6l","2015-12-25T19:00:00Z","2015-12-25T19:30:00Z","The Quizeum Series 2 Christmas Special"),
      ScheduledProgram("bbc_four_hd","b06t3qkz","2015-12-25T19:30:00Z","2015-12-25T20:45:00Z", "Carlos Acosta's Carmen: The Farewell Performance")
    ),
    1,
    cannedScheduleRequest
  )

  val cannedAvailabilityRequest = ProgramAvailabilityRequest(
    ScheduledProgram("bbc_four_hd","b06ssj6l","2015-12-25T19:00:00Z","2015-12-25T19:30:00Z","The Quizeum Series 2 Christmas Special"),
    retryAttempt = 0
  )

  val cannedAvailabilityResponse = ProgramAvailabilityResponse(
    ScheduledProgram("bbc_four_hd","b06ssj6l","2015-12-25T19:00:00Z","2015-12-25T19:30:00Z","The Quizeum Series 2 Christmas Special"),
    isAvailable = true)
}
