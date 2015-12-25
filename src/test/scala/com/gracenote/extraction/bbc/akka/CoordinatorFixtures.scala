package com.gracenote.extraction.bbc.akka

import com.gracenote.extraction.bbc.akka.Coordinator.Protocol.ScheduleRequest
import com.gracenote.extraction.bbc.akka.Coordinator.ScheduledProgram
import org.joda.time.DateTime

trait CoordinatorFixtures {
  val scheduleRequest = ScheduleRequest("bbc_four", DateTime.parse("2015-12-16"), DateTime.parse("2015-12-17"))

  def makeProgram(pid: String) =
    ScheduledProgram("serviceId", pid, "2015-12-16T09:00:00", "2015-12-16T10:00:00", "The Devil Wears Prada")
}
