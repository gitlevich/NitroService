package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor._
import akka.contrib.throttle.Throttler.Rate
import com.gracenote.extraction.bbc.akka.Coordinator.Message._
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.io.Source

object ActorBasedFetcher extends App {
  require(args.length == 2, "Start and end dates required in format YYYY-MM-dd")
  val from = DateTime.parse(args(0))
  val to = DateTime.parse(args(1))
  require(from.isBefore(to), "Start date must be before end date")
  val serviceIds = Source.fromInputStream(getClass.getResourceAsStream("/providers.txt")).getLines()

  val coordinator = new ActorBasedFetcher().startUpCoordinator(Rate(90, 1 second))
  coordinator ! StartExtraction(new File("schedules.csv"), Rate(90, 1 second))

  serviceIds map (id => ScheduleRequest(id, from, to)) foreach (request => coordinator ! request)
}

class ActorBasedFetcher {
  def startUpCoordinator(rate: Rate) =
    ActorSystem("Schedules").actorOf(Props(new Coordinator()))
}




