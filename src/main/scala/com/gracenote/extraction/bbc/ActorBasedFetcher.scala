package com.gracenote.extraction.bbc

import java.io.File

import akka.actor._
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import com.github.tototoshi.csv.CSVWriter
import com.gracenote.extraction.bbc.Protocol._
import com.ning.http.client.AsyncHttpClientConfig.Builder
import org.joda.time.DateTime
import play.api.libs.ws._
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
  val serviceIds = Source.fromInputStream(getClass.getResourceAsStream("/providers.txt")).getLines()

  val coordinator = startUpCoordinator(Rate(90, 1 second))
  coordinator ! StartExtraction(new File("schedules.csv"), Rate(90, 1 second))

  serviceIds map (id => ScheduleRequest(id, from, to)) foreach (request => coordinator ! request)


  def startUpCoordinator(rate: Rate) =
    ActorSystem("Schedules").actorOf(Props(new Coordinator()))
}

object Protocol {
  trait Retryable { def nextTry: Retryable }
  case class ProgramAvailabilityRequest(program: ScheduledProgram, retryAttempt: Int = 0) extends Retryable {
    def nextTry = copy(retryAttempt = retryAttempt + 1)
  }
  case class ProgramAvailabilityResponse(program: ScheduledProgram, isAvailable: Boolean)
  case class ScheduledProgram(sid: String, pid: String, startTime: String, endTime: String, title: String)
  case class Shutdown()
  case class ScheduleRequest(serviceId: String, from: DateTime, to: DateTime, pageToFetch: Int = 1, retryAttempt: Int = 0) extends Retryable {
    def nextTry = copy(retryAttempt = retryAttempt + 1)
  }
  case class ScheduleResponse(programs: Seq[ScheduledProgram], totalPages: Int, request: ScheduleRequest) {
    def nextPageRequest = if (request.pageToFetch < totalPages) Some(request.copy(pageToFetch = request.pageToFetch + 1)) else None
  }
  case class UnrecoverableError(request: Any, message: String)
  case class Retry(request: Retryable)
  case class StartExtraction(file: File, rate: Rate)
  case class OpenFile(file: File)
  case class StartUp()

  sealed trait State
  case object Idle extends State
  case object Active extends State

  case class Stats()
}

class Coordinator() extends Actor with FSM[State, Stats] {
  startWith(Idle, Stats())

  private var fetcher: ActorRef = null
  private var throttler: ActorRef = null
  private var writer: ActorRef = null

  when(Idle) {
    case Event(StartExtraction(file, rate), Stats()) =>
      writer = context.actorOf(Props(new FileWriter()))
      writer ! OpenFile(file)

      fetcher = context.actorOf(Props(new ScheduleFetcher()))
      fetcher ! StartUp()

      throttler = context.actorOf(Props(new TimerBasedThrottler(rate)))
      throttler ! SetTarget(Some(fetcher))

      goto(Active)
  }

  when(Active, stateTimeout = 1.minute) {
    case Event(request: ScheduleRequest, Stats()) =>
      throttler ! request
      stay()

    case Event(response@ScheduleResponse(programs, totalPages, _), Stats()) =>
      programs.foreach { p => throttler ! ProgramAvailabilityRequest(p) }
      response.nextPageRequest.foreach { nextPageRequest =>
        throttler ! nextPageRequest
      }
      stay()

    case Event(ProgramAvailabilityResponse(program, isAvailable), Stats()) =>
      if(isAvailable) writer ! program else log.info(s"$program is not available")
      stay()

    case Event(UnrecoverableError(request, message), Stats()) =>
      log.warning(s"Error: '$message' while processing $request")
      stay()

    case Event(StateTimeout, Stats()) =>
      log.info(s"Looks like we are done. Timing out...")
      writer ! PoisonPill
      throttler ! PoisonPill
      fetcher ! PoisonPill

      goto(Idle)
  }

  onTransition {
    case Idle -> Active ⇒ log.info("Transitioning Idle -> Active")
    case Active -> Idle ⇒ log.info("Transitioning Active -> Idle")
  }

  initialize()
}


class ScheduleFetcher() extends Actor with ActorLogging {
  private var ws: NingWSClient = null
  val maxRetries = 3

  override def receive: Receive = {
    case request@ScheduleRequest(serviceId, from, to, pageToFetch, 0) =>
      val wsRequest = createScheduleRequest(serviceId, from, to, pageToFetch)
      val wsResponse = Await.result(wsRequest.get(), 120 seconds)
      sender() ! toScheduleResponse(request, wsResponse)

    case request@ScheduleRequest(_, _, _, _, retry) if retry < maxRetries =>
      scheduleRetry(request, retry)

    case request@ProgramAvailabilityRequest(program, 0) =>
      val wsRequest = createAvailabilityRequest(program)
      val wsResponse = Await.result(wsRequest.get(), 120 seconds)
      sender() ! toAvailabilityResponse(request, program, wsResponse)

    case request@ProgramAvailabilityRequest(_, retry) if retry < maxRetries =>
      scheduleRetry(request, retry)

    case Retry(request) =>
      log.warning(s"Retrying request ${request.nextTry}")
      self ! request.nextTry

    case StartUp() =>
      log.info("Starting up NingWSClient")
      ws = new NingWSClient(new Builder().build())
  }

  override def postStop(): Unit = {
    log.info("Shutting down NingWSClient")
    ws.close()
    super.postStop()
  }

  private def scheduleRetry(request: Retryable, retry: Int): Unit = {
    val delay = Math.pow(3, retry).seconds
    log.warning(s"Scheduling $retry retry in ${delay.toSeconds} seconds for request $request")

    import context.dispatcher
    context.system.scheduler.scheduleOnce(delay, self, Retry(request))
  }

  private def toScheduleResponse(request: ScheduleRequest, wsResponse: WSResponse) =
    if (wsResponse.status == Status.OK) ScheduleResponse(
      toProgramList(wsResponse.xml),
      toNumberOfPages(wsResponse.xml),
      request)
    else if (recoverableError(wsResponse))
      request.nextTry
    else UnrecoverableError(request, wsResponse.body)

  private def toAvailabilityResponse(request: ProgramAvailabilityRequest, program: ScheduledProgram, wsResponse: WSResponse) =
    if (wsResponse.status == Status.OK)
      toProgramAvailabilityResponse(program, wsResponse.xml)
    else if (recoverableError(wsResponse))
      request.nextTry
    else ProgramAvailabilityResponse(program, isAvailable = false)

  private def createAvailabilityRequest(program: ScheduledProgram): WSRequest = {
    ws.url("http://programmes.api.bbc.com/nitro/api/programmes")
      .withQueryString("pid" -> program.pid)
      .withQueryString("availability" -> "available")
      .withQueryString("availability_entity_type" -> "episode")
      .withQueryString("entity_type" -> "episode")
      .withQueryString("availability" -> "P5D")
      .withQueryString("media_set" -> "stb-all-h264")
      .withQueryString("api_key" -> "kheF9DxuX0j7lgAleY7Ewp57USjYDsl2")
  }

  private def createScheduleRequest(serviceId: String, from: DateTime, to: DateTime, pageToFetch: Int): WSRequest =
    ws.url("http://programmes.api.bbc.com/nitro/api/schedules")
    .withQueryString("page" -> pageToFetch.toString)
    .withQueryString("sort" -> "start_date")
    .withQueryString("schedule_day_from" -> from.toString("YYYY-MM-dd"))
    .withQueryString("schedule_day_to" -> to.toString("YYYY-MM-dd"))
    .withQueryString("sid" -> serviceId)
    .withQueryString("mixin" -> "ancestor_titles")
    .withQueryString("api_key" -> "kheF9DxuX0j7lgAleY7Ewp57USjYDsl2")

  private def recoverableError(wsResponse: WSResponse): Boolean =
    wsResponse.status == Status.INTERNAL_SERVER_ERROR ||
      wsResponse.status == Status.REQUEST_TIMEOUT ||
      wsResponse.status == Status.GATEWAY_TIMEOUT

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


class FileWriter() extends Actor with ActorLogging {
  private var csvWriter: CSVWriter = null

  override def receive: Receive = {
    case program: ScheduledProgram =>
      csvWriter.writeRow(Seq(program.sid, program.pid, program.title, program.startTime, program.endTime))

    case OpenFile(file) =>
      csvWriter = CSVWriter.open(file)
      csvWriter.writeRow(Seq("service", "pid", "title", "start_time", "end_time"))
      log.info(s"Created new file $file")
  }

  override def postStop() {
    log.info("Closing output file")
    csvWriter.close()
    super.postStop()
  }
}
