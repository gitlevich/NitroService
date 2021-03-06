package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor._
import akka.contrib.throttle.Throttler.Rate
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.io.Source

object NitroService extends App {
  require(args.length == 2, "Start and end dates required in format YYYY-MM-dd")
  val from = DateTime.parse(args(0))
  val to = DateTime.parse(args(1))
  require(from.isBefore(to), "Start date must be before end date")

  val config = ConfigFactory.load()
  val channelsFileName = config.getString("nitro.channels_file")
  val outputFileName = config.getString("nitro.output_file")

  val logger = Logger(LoggerFactory.getLogger(getClass))
  logger.info(s"Reading channels from ${new File(channelsFileName).getAbsolutePath}")

  val serviceIds = Source.fromInputStream(getClass.getResourceAsStream(s"/$channelsFileName")).getLines()

  val coordinator = ActorSystem("Schedules").actorOf(Props(new Coordinator()), "coordinator")

  coordinator ! StartExtraction(new File(outputFileName), Rate(90, 1.second))
  serviceIds map (id => ScheduleRequest(id, from, to)) foreach (request => coordinator ! request)
}




