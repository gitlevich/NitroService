package com.gracenote.extraction.bbc

import com.gracenote.extraction.bbc.Protocol._
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, WordSpecLike}

class ProgramResponseTest extends WordSpecLike with Matchers with MockitoSugar {

  "ProgramResponse" should {

    "calculate just next page request" in {
      val request1 = ProgramRequest("abc", DateTime.parse("2015-12-01"), DateTime.parse("2015-12-02"))
      val response = ProgramResponse(Seq.empty[ScheduledProgram], totalPages = 4, request1)

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
}
