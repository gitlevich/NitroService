package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor.FSM.StateTimeout
import akka.actor._
import akka.contrib.throttle.Throttler.Rate
import akka.testkit._
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.gracenote.extraction.bbc.akka.Coordinator.Stats
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.concurrent.duration._

class CoordinatorTest extends TestKit(ActorSystem("testSystem")) with WordSpecLike with MockitoSugar with ImplicitSender with Matchers with BeforeAndAfterAll {

  val outputFile = new File("some_file.csv")
  val rate = Rate(90, 1.second)

  val coordinator = TestFSMRef(new Coordinator())

  "Coordinator" should {
    "start in the Idle state" in {
      assert(coordinator.stateName == Coordinator.Idle)
    }

    "should transition to Active state on StartExtraction event" in {
      coordinator ! StartExtraction(outputFile, rate)
      assert(coordinator.stateName == Coordinator.Active)
    }

    "should remember its output file name" in {
      assert(coordinator.stateData == Stats(Some(outputFile.getAbsolutePath)))
    }

    "should transition back to Idle on timeout" in {
      coordinator ! StateTimeout
      assert(coordinator.stateName == Coordinator.Idle)
    }

    "should transition from Idle to Terminated on timeout" in {
      coordinator ! StateTimeout
      assert(coordinator.stateName == Coordinator.Terminated)
    }
  }
}
