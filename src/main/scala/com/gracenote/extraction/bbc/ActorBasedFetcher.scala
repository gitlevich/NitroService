package com.gracenote.extraction.bbc

import java.io.File

import akka.actor._
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import com.github.tototoshi.csv.CSVWriter
import com.gracenote.extraction.bbc.Protocol._
import com.ning.http.client.AsyncHttpClientConfig.Builder
import org.joda.time.DateTime
import play.api.libs.ws.WSRequest
import play.api.libs.ws.ning.NingWSClient
import play.mvc.Http.Status

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import scala.xml.Node

object ActorBasedFetcher extends App {
  require(args.length == 2, "Start and end dates required in format YYYY-MM-dd")
  val from = DateTime.parse(args(0))
  val to = DateTime.parse(args(1))
  require(from.isBefore(to), "Start date must be before end date")

  startUp()


  def startUp() = {
    val ws = new NingWSClient(new Builder().build())
    val outputFile = new File("schedules.csv")
    val rate = Rate(90, 1 second)

    val system = ActorSystem("Schedules")
    val coordinator = system.actorOf(Props(new Coordinator(ws, outputFile, rate)))

    val serviceIds = Source.fromInputStream(getClass.getResourceAsStream("providers.txt")).getLines()

    serviceIds map (id => ProgramRequest(id, from, to)) foreach (request => coordinator ! request)
  }
}

object Protocol {
  case class ProgramAvailabilityRequest(program: ScheduledProgram)
  case class ProgramAvailabilityResponse(program: ScheduledProgram, isAvailable: Boolean)
  case class ScheduledProgram(sid: String, pid: String, startTime: String, endTime: String, title: String)
  case class Shutdown()
  case class ProgramRequest(serviceId: String, from: DateTime, to: DateTime, pageToFetch: Int = 1)
  case class ProgramResponse(programs: Seq[ScheduledProgram], totalPages: Int, request: ProgramRequest) {
    def nextPageRequest = if (request.pageToFetch < totalPages) Some(request.copy(pageToFetch = request.pageToFetch + 1)) else None
  }
}

class Coordinator(ws: NingWSClient, outputFile: File, rate: Rate) extends Actor with ActorLogging {
  val fetcher = context.actorOf(Props(new Fetcher(ws)))
  val throttler = context.actorOf(Props(new TimerBasedThrottler(rate)))
  val writer = context.actorOf(Props(new FileWriter(outputFile)))
  throttler ! SetTarget(Some(fetcher))

  override def receive: Receive = {
    case request: ProgramRequest => throttler ! request

    case Right(response@ProgramResponse(programs, totalPages, _)) =>
      programs.foreach { p => throttler ! ProgramAvailabilityRequest(p) }
      response.nextPageRequest.foreach { nextPageRequest =>
        throttler ! nextPageRequest
      }

    case ProgramAvailabilityResponse(program, isAvailable) if isAvailable =>
      writer ! program

    case Left(body) =>
      log.warning(s"********************** Error: $body")

    case _: Shutdown =>
      context.stop(throttler)
      context.stop(fetcher)
      context.stop(writer)
      context.stop(self)
  }
}


class Fetcher(ws: NingWSClient) extends Actor {
  require(ws != null)

  override def receive: Receive = {
    case programRequest@ProgramRequest(serviceId, from, to, pageToFetch) =>
      val request = createScheduleRequest(serviceId, from, to, pageToFetch)
      val response = Await.result(request.get(), 120 seconds)

      val result =
        if (response.status == Status.OK) Right(ProgramResponse(
          toProgramList(response.xml),
          toNumberOfPages(response.xml),
          programRequest))
        else Left(response.body)

      sender() ! result

    case ProgramAvailabilityRequest(program) =>
      val request = createAvailabilityRequest(program)
      val response = Await.result(request.get(), 120 seconds)

      val result =
        if (response.status == Status.OK)
          toProgramAvailabilityResponse(program, response.xml)
        else ProgramAvailabilityResponse(program, isAvailable = false)

      sender() ! result

    case _: Shutdown =>
      ws.close()
      context.stop(self)
  }

  def createAvailabilityRequest(program: ScheduledProgram): WSRequest = {
    ws.url("http://programmes.api.bbc.com/nitro/api/programmes")
      .withQueryString("pid" -> program.pid)
      .withQueryString("availability" -> "available")
      .withQueryString("availability_entity_type" -> "episode")
      .withQueryString("entity_type" -> "episode")
      .withQueryString("availability" -> "P5D")
      .withQueryString("media_set" -> "stb-all-h264")
      .withQueryString("api_key" -> "kheF9DxuX0j7lgAleY7Ewp57USjYDsl2")
  }

  def createScheduleRequest(serviceId: String, from: DateTime, to: DateTime, pageToFetch: Int): WSRequest = {
    ws.url("http://programmes.api.bbc.com/nitro/api/schedules")
      .withQueryString("page" -> pageToFetch.toString)
      .withQueryString("sort" -> "start_date")
      .withQueryString("schedule_day_from" -> from.toString("YYYY-MM-dd"))
      .withQueryString("schedule_day_to" -> to.toString("YYYY-MM-dd"))
      .withQueryString("sid" -> serviceId)
      .withQueryString("mixin" -> "ancestor_titles")
      .withQueryString("api_key" -> "kheF9DxuX0j7lgAleY7Ewp57USjYDsl2")
  }

  private def toProgramAvailabilityResponse(program: ScheduledProgram, node: Node) =
    ProgramAvailabilityResponse(program, if ((node \ "results" \ "@total").text == "0") false else true)


  private def toProgramList(page: Node): Seq[ScheduledProgram] =
    (page \\ "broadcast").map(node => toProgram(node))

  private def toProgram(node: Node) = ScheduledProgram(
    (node \ "service" \ "@sid").text,
    toEpisodePid(node),
    (node \ "published_time" \ "@start").text,
    (node \ "published_time" \ "@end").text,
    (node \ "ancestor_titles" \ "brand" \ "title").text + " " +
      (node \ "ancestor_titles" \ "series" \ "title").text + " " +
      (node \ "ancestor_titles" \ "episode" \ "title").text
  )

  private def toEpisodePid(node: Node) = {
    val element = node \ "broadcast_of"
    val episodeElement = element.filter(element => (element \ "@result_type").text == "episode")
    (episodeElement \ "@pid").text
  }

  private def toNumberOfPages(node: Node) = {
    val totalProgrammes = (node \\ "results" \ "@total").text.toInt
    val pageSize = (node \\ "results" \ "@page_size").text.toInt

    if (totalProgrammes % pageSize == 0) totalProgrammes / pageSize else totalProgrammes / pageSize + 1
  }
}


class FileWriter(outputFile: File) extends Actor {
  val csvWriter = CSVWriter.open(outputFile)
  csvWriter.writeRow(Seq("service", "pid", "title", "start_time", "end_time"))

  override def receive: Receive = {
    case program: ScheduledProgram =>
      csvWriter.writeRow(Seq(program.sid, program.pid, program.title, program.startTime, program.endTime))

    case _: Shutdown =>
      csvWriter.close()
      context.stop(self)
  }
}
