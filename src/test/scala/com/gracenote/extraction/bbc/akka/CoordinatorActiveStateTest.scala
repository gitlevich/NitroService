package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor._
import akka.contrib.throttle.Throttler.Rate
import akka.testkit._
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import org.scalatest._

import scala.concurrent.duration._

class CoordinatorActiveStateTest extends TestKit(ActorSystem("testSystem"))
                                         with WordSpecLike
                                         with CoordinatorFixtures
                                         with Matchers with BeforeAndAfterAll {

  val outputFile = new File("some_file.csv")
  val rate = Rate(100000, 1.second)

  "Coordinator in Active state" should {
    val writer = TestProbe()
    val fetcher = TestProbe()
    val coordinator = TestFSMRef(new Coordinator())
    coordinator ! ConfigureForTest(writer.testActor, fetcher.testActor, rate, Coordinator.Active)
    assert(coordinator.stateName == Coordinator.Active)

    val program1 = makeProgram("111")
    val program2 = makeProgram("222")

    "forward ScheduleRequest to fetcher" in {
      coordinator ! scheduleRequest
      fetcher.expectMsg(scheduleRequest)
    }

    "fire ProgramAvailabilityRequest at fetcher for each program in ScheduleResponse" in {
      val scheduleResponse = ScheduleResponse(Seq(program1, program2), 10, scheduleRequest)
      coordinator ! scheduleResponse
      val received = fetcher.receiveN(2)

      received.size shouldBe 2
      received should contain(ProgramAvailabilityRequest(program1))
      received should contain(ProgramAvailabilityRequest(program2))
    }

    "fire at fetcher a ScheduleRequest for the next page if any remain" in {
      val scheduleResponse = ScheduleResponse(Seq(program1, program2), 10, scheduleRequest)
      scheduleResponse.nextPageRequest shouldNot be(None)

      coordinator ! scheduleResponse
      fetcher.expectMsg(scheduleResponse.nextPageRequest.get)
    }

    "not fire at fetcher a ScheduleRequest for the next page if none remain" in {
      val scheduleResponse = ScheduleResponse(Seq(program1, program2), 1, scheduleRequest)
      scheduleResponse.nextPageRequest shouldBe None

      coordinator ! scheduleResponse

      val received = fetcher.receiveN(1)
      assert(received.head.getClass != classOf[ScheduleRequest])
    }

    "forward program to writer on ProgramAvailabilityResponse if program is available" in {
      val availabilityResponse = ProgramAvailabilityResponse(makeProgram("1"), isAvailable = true)

      coordinator ! availabilityResponse

      writer.expectMsg(availabilityResponse.program)
    }

    "not forward program to writer on ProgramAvailabilityResponse if program is unavailable" in {
      val availabilityResponse = ProgramAvailabilityResponse(makeProgram("1"), isAvailable = false)

      coordinator ! availabilityResponse

      writer.expectNoMsg
    }
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
