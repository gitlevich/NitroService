package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor.FSM.StateTimeout
import akka.actor._
import akka.contrib.throttle.Throttler.Rate
import akka.testkit._
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.gracenote.extraction.bbc.akka.Coordinator._
import org.joda.time.DateTime
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._

class CoordinatorStateTransitionTest extends TestKit(ActorSystem("testSystem")) with WordSpecLike with MockitoSugar with ImplicitSender with Matchers with BeforeAndAfterAll {

  val outputFile = new File("some_file.csv")
  val rate = Rate(100000, 1.second)

  "Coordinator" should {
    val writer = TestProbe()
    val fetcher = TestProbe()
    val coordinator = TestFSMRef(new Coordinator())
    coordinator ! ConfigureForTest(writer.testActor, fetcher.testActor, rate, Coordinator.Idle)
    assert(coordinator.stateName == Coordinator.Idle)

    "start in the Idle state" in {
      assert(coordinator.stateName == Coordinator.Idle)
    }

    "transition to Active state on StartExtraction event" in {
      coordinator ! StartExtraction(outputFile, rate)
      assert(coordinator.stateName == Coordinator.Active)
    }

    "remember its output file name" in {
      assert(coordinator.stateData == Stats(Some(outputFile.getAbsolutePath)))
    }

    "stay in Active state upon receiving ScheduleRequest" in {
      coordinator ! ScheduleRequest("bbc_four", DateTime.parse("2015-12-16"), DateTime.parse("2015-12-17"))
      assert(coordinator.stateName == Coordinator.Active)
    }

    "stay in Active state upon receiving ScheduleResponse" in {
      coordinator ! ScheduleResponse(Seq(makeProgram("1")), 10, scheduleRequest)
      assert(coordinator.stateName == Coordinator.Active)
    }

    "stay in Active state upon receiving ProgramAvailabilityResponse" in {
      coordinator ! ProgramAvailabilityResponse(makeProgram("1"), isAvailable = true)
      assert(coordinator.stateName == Coordinator.Active)
    }

    "stay in Active state upon receiving UnrecoverableError" in {
      coordinator ! UnrecoverableError(scheduleRequest, "Trouble!")
      assert(coordinator.stateName == Coordinator.Active)
    }

    "transition back to Idle on timeout" in {
      coordinator ! StateTimeout
      assert(coordinator.stateName == Coordinator.Idle)
    }

    "transition from Idle to Terminated on timeout" in {
      coordinator ! StateTimeout
      assert(coordinator.stateName == Coordinator.Terminated)
    }
  }
}
