package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor.{Actor, ActorRef, FSM, PoisonPill, Props}
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.gracenote.extraction.bbc.akka.Coordinator._
import com.gracenote.extraction.bbc.akka.FileWriter.OpenFile
import com.gracenote.extraction.bbc.akka.ScheduleFetcher.Protocol.StartUp
import org.joda.time.DateTime

import scala.concurrent.duration._

class Coordinator() extends Actor with FSM[State, Stats] {
  startWith(Idle, Stats(None))

  private var fetcher: ActorRef = null
  private var throttler: ActorRef = null
  private var writer: ActorRef = null

  when(Idle, stateTimeout = timeoutToTerminate) {
    case Event(StartExtraction(file, rate), stats) =>
      writer = context.actorOf(Props(new FileWriter()), "writer")
      writer ! OpenFile(file)

      fetcher = context.actorOf(Props(new ScheduleFetcher()), "fetcher")
      fetcher ! StartUp()

      throttler = context.actorOf(Props(new TimerBasedThrottler(rate)), "throttler")
      throttler ! SetTarget(Some(fetcher))

      goto(Active) using stats.copy(fileName = Some(file.getAbsolutePath))

    case Event(StateTimeout, stats) =>
      log.info(s"Shutting down the actor system after $timeoutToTerminate of inactivity.")
      context.system.terminate()
      goto(Terminated) using stats

    // This is for testing only, to inject the test probes
    case Event(ConfigureForTest(w, f, rate, state), stats) =>
      writer = w
      fetcher = f
      throttler = context.actorOf(Props(new TimerBasedThrottler(rate)), "throttlerForTest")
      throttler ! SetTarget(Some(fetcher))

      goto(state) using stats
  }

  when(Active, stateTimeout = timeoutToEndSession) {
    case Event(request: ScheduleRequest, stats) =>
      throttler ! request
      stay() using stats

    case Event(response@ScheduleResponse(programs, totalPages, _), stats) =>
      programs.foreach { p => throttler ! ProgramAvailabilityRequest(p) }
      response.nextPageRequest.foreach { nextPageRequest =>
        throttler ! nextPageRequest
      }
      stay() using stats

    case Event(ProgramAvailabilityResponse(program, isAvailable), stats) =>
      if (isAvailable) writer ! program else log.info(s"$program is not available")
      stay() using stats

    case Event(UnrecoverableError(request, message), stats) =>
      log.warning(s"Error: '$message' while processing $request")
      stay() using stats

    case Event(StateTimeout, stats) =>
      log.info(s"The ingest session seems to have finished: no activity for $timeoutToEndSession.")
      stats.fileName.foreach(fileName => log.info(s"The result will be saved in '$fileName'"))

      writer ! PoisonPill
      throttler ! PoisonPill
      fetcher ! PoisonPill

      goto(Idle) using stats
  }

  when(Terminated)(FSM.NullFunction)

  onTransition {
    case Idle -> Terminated ⇒ log.info("Transitioning Idle -> Terminated")
    case Idle -> Active ⇒ log.info("Transitioning Idle -> Active")
    case Active -> Idle ⇒ log.info("Transitioning Active -> Idle")
  }

  initialize()
}


private[bbc] object Coordinator {
  val timeoutToTerminate = 1.minute
  val timeoutToEndSession = 1.minute

  sealed trait State
  case object Idle extends State
  case object Active extends State
  case object Terminated extends State

  case class Stats(fileName: Option[String])

  case class ScheduledProgram(sid: String, pid: String, startTime: String, endTime: String, title: String)

  trait Retryable {def nextTry: Retryable}

  object Protocol {
    case class StartExtraction(file: File, rate: Rate)
    case class ConfigureForTest(writer: ActorRef, fetcher: ActorRef, rate: Rate, state: State)
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

