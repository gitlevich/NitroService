package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor.{Actor, ActorRef, FSM, PoisonPill, Props}
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import com.gracenote.extraction.bbc.akka.Coordinator.Message._
import com.gracenote.extraction.bbc.akka.Coordinator._
import com.gracenote.extraction.bbc.akka.FileWriter.OpenFile
import com.gracenote.extraction.bbc.akka.ScheduleFetcher.StartUp
import org.joda.time.DateTime

import scala.concurrent.duration._

private [bbc] object Coordinator {
  sealed trait State
  case object Idle extends State
  case object Active extends State

  case class Stats()

  case class ScheduledProgram(sid: String, pid: String, startTime: String, endTime: String, title: String)

  trait Retryable { def nextTry: Retryable }
  object Message {
    case class StartExtraction(file: File, rate: Rate)
    case class ProgramAvailabilityRequest(program: ScheduledProgram, retryAttempt: Int = 0) extends Retryable {
      def nextTry = copy(retryAttempt = retryAttempt + 1)
    }
    case class ProgramAvailabilityResponse(program: ScheduledProgram, isAvailable: Boolean)
    case class ScheduleRequest(serviceId: String, from: DateTime, to: DateTime, pageToFetch: Int = 1, retryAttempt: Int = 0) extends Retryable {
      def nextTry = copy(retryAttempt = retryAttempt + 1)
    }
    case class ScheduleResponse(programs: Seq[ScheduledProgram], totalPages: Int, request: ScheduleRequest) {
      def nextPageRequest = if (request.pageToFetch < totalPages) Some(request.copy(pageToFetch = request.pageToFetch + 1)) else None
    }
    case class UnrecoverableError(request: Any, message: String)
  }
}


class Coordinator() extends Actor with FSM[State, Stats] {
  startWith(Idle, Stats())

  private var fetcher: ActorRef = null
  private var throttler: ActorRef = null
  private var writer: ActorRef = null

  when(Idle) {
    case Event(StartExtraction(file, rate), Stats()) =>
      writer = context.actorOf(Props(new FileWriter()))
      writer ! OpenFile(file)

      fetcher = context.actorOf(Props(new ScheduleFetcher()))
      fetcher ! StartUp()

      throttler = context.actorOf(Props(new TimerBasedThrottler(rate)))
      throttler ! SetTarget(Some(fetcher))

      goto(Active)
  }

  when(Active, stateTimeout = 1.minute) {
    case Event(request: ScheduleRequest, Stats()) =>
      throttler ! request
      stay()

    case Event(response@ScheduleResponse(programs, totalPages, _), Stats()) =>
      programs.foreach { p => throttler ! ProgramAvailabilityRequest(p) }
      response.nextPageRequest.foreach { nextPageRequest =>
        throttler ! nextPageRequest
      }
      stay()

    case Event(ProgramAvailabilityResponse(program, isAvailable), Stats()) =>
      if(isAvailable) writer ! program else log.info(s"$program is not available")
      stay()

    case Event(UnrecoverableError(request, message), Stats()) =>
      log.warning(s"Error: '$message' while processing $request")
      stay()

    case Event(StateTimeout, Stats()) =>
      log.info(s"Looks like we are done. Timing out...")
      writer ! PoisonPill
      throttler ! PoisonPill
      fetcher ! PoisonPill

      goto(Idle)
  }

  onTransition {
    case Idle -> Active ⇒ log.info("Transitioning Idle -> Active")
    case Active -> Idle ⇒ log.info("Transitioning Active -> Idle")
  }

  initialize()
}

