package com.gracenote.extraction.bbc.akka

import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.gracenote.extraction.bbc.akka.Coordinator._
import org.joda.time.DateTime
import org.scalatest.{Matchers, WordSpecLike}

class CoordinatorProtocolSpec extends WordSpecLike with Matchers with CoordinatorFixtures {

  "ScheduleResponse" should {
    "calculate just next page request" in {
      val request1 = ScheduleRequest("abc", DateTime.parse("2015-12-01"), DateTime.parse("2015-12-02"))
      val response = ScheduleResponse(Seq.empty[ScheduledProgram], totalPages = 4, request1)

      val request2 = request1.copy(pageToFetch = 2)
      assert(Some(request1.copy(pageToFetch = 2)) === response.nextPageRequest)

      val request3 = response.copy(request = request2).nextPageRequest
      assert(Some(request1.copy(pageToFetch = 3)) === request3)

      val request4 = response.copy(request = request3.get).nextPageRequest
      assert(Some(request1.copy(pageToFetch = 4)) === request4)

      val request5 = response.copy(request = request4.get).nextPageRequest
      assert(None === request5)
    }
  }

  "ProgramAvailabilityRequest" should {
    "create next try with incremented retry attempt" in {
      val request = ProgramAvailabilityRequest(makeProgram("1"), retryAttempt = 0)

      request.nextTry.retryAttempt shouldBe request.retryAttempt + 1
    }
  }

  "ScheduleRequest" should {
    "create next try with incremented retry attempt" in {
      val request = ScheduleRequest("bbc_four", DateTime.parse("2015-12-16"), DateTime.parse("2015-12-17"), retryAttempt = 0)

      request.nextTry.retryAttempt shouldBe request.retryAttempt + 1
    }
  }
}
