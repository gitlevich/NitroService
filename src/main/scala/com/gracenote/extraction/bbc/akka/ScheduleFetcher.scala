package com.gracenote.extraction.bbc.akka

import akka.actor._
import com.gracenote.extraction.bbc.akka.Coordinator.Protocol._
import com.gracenote.extraction.bbc.akka.Coordinator._
import com.gracenote.extraction.bbc.akka.ScheduleFetcher.Protocol._
import com.gracenote.extraction.bbc.akka.ScheduleFetcher._
import com.ning.http.client.AsyncHttpClientConfig.Builder
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import play.api.libs.ws._
import play.api.libs.ws.ning.NingWSClient
import play.mvc.Http.Status

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.xml.Node

class ScheduleFetcher() extends Actor with ActorLogging {
  private var ws: NingWSClient = null
  import context.dispatcher

  log.info("Started with parameters:\n" +
    s"\t\tmaxRetries = $maxRetries\n" +
    s"\t\tschedulesUrl = $schedulesUrl\n" +
    s"\t\tavailabilityUrl = $availabilityUrl\n" +
    "\t\tapiKey = ******")

  override def receive: Receive = {
    case request@ScheduleRequest(serviceId, from, to, pageToFetch, 0) =>
      val wsRequest = createScheduleRequest(serviceId, from, to, pageToFetch, ws)
      val wsResponse = Await.result(wsRequest.get(), 120.seconds)
      log.info(s"Processing $request")
      sender() ! toScheduleResponse(request, wsResponse)

    case request@ScheduleRequest(_, _, _, _, retry) =>
      if (retry < maxRetries)
        scheduleRetry(request, retry)
      else
        log.warning(s"Giving up trying to retrieve schedule for request $request after $retry tries.")

    case request@ProgramAvailabilityRequest(program, 0) =>
      val wsRequest = createAvailabilityRequest(program, ws)
      val wsResponse = Await.result(wsRequest.get(), 120.seconds)
      log.info(s"Processing $request")
      sender() ! toAvailabilityResponse(request, program, wsResponse)

    case request@ProgramAvailabilityRequest(program, retry) =>
      if (retry < maxRetries)
        scheduleRetry(request, retry)
      else
        log.warning(s"Giving up trying to get availability of $program after $retry tries.")

    case Retry(request) =>
      log.warning(s"Retrying ${request.nextTry}")
      self ! request.nextTry

    case StartUp() =>
      log.info("Starting up NingWSClient")
      ws = new NingWSClient(new Builder().build())

    case StartUpForTest(client) =>
      log.info("Configured for testing with another NingWSClient")
      ws = client
  }

  override def postStop(): Unit = {
    if(ws != null) {
      log.info("Shutting down NingWSClient")
      ws.close()
    }
    super.postStop()
  }

  private def scheduleRetry(request: Retryable, retry: Int): Unit = {
    val delay = calculateDelay(retry)

    log.info(s"Scheduling $retry retry in ${delay.toSeconds} seconds for request $request")
    context.system.scheduler.scheduleOnce(delay, self, Retry(request))
  }
}


object ScheduleFetcher {
  val config = ConfigFactory.load()

  val maxRetries = config.getInt("nitro.fetcher.max_retry_count")
  val apiKey = config.getString("nitro.fetcher.ws.api_key")
  val schedulesUrl = config.getString("nitro.fetcher.ws.url.schedule")
  val availabilityUrl = config.getString("nitro.fetcher.ws.url.availability")

  object Protocol {
    case class Retry(request: Retryable)
    case class StartUp()
    case class StartUpForTest(ws: NingWSClient)
  }

  def toScheduleResponse(request: ScheduleRequest, wsResponse: WSResponse) =
    if (wsResponse.status == Status.OK) ScheduleResponse(
      toProgramList(wsResponse.xml),
      toNumberOfPages(wsResponse.xml),
      request)
    else if (recoverableError(wsResponse))
      request.nextTry
    else UnrecoverableError(request, wsResponse.body)

  def toAvailabilityResponse(request: ProgramAvailabilityRequest, program: ScheduledProgram, wsResponse: WSResponse) =
    if (wsResponse.status == Status.OK)
      toProgramAvailabilityResponse(program, wsResponse.xml)
    else if (recoverableError(wsResponse))
      request.nextTry
    else ProgramAvailabilityResponse(program, isAvailable = false)

  def createAvailabilityRequest(program: ScheduledProgram, ws: NingWSClient): WSRequest = {
    ws.url(availabilityUrl)
      .withQueryString("pid" -> program.pid)
      .withQueryString("availability" -> "available")
      .withQueryString("availability_entity_type" -> "episode")
      .withQueryString("entity_type" -> "episode")
      .withQueryString("availability" -> "P5D")
      .withQueryString("media_set" -> "stb-all-h264")
      .withQueryString("api_key" -> apiKey)
  }

  def createScheduleRequest(serviceId: String, from: DateTime, to: DateTime, pageToFetch: Int, ws: NingWSClient): WSRequest = {
    ws.url(schedulesUrl)
      .withQueryString("page" -> pageToFetch.toString)
      .withQueryString("sort" -> "start_date")
      .withQueryString("schedule_day_from" -> from.toString("YYYY-MM-dd"))
      .withQueryString("schedule_day_to" -> to.toString("YYYY-MM-dd"))
      .withQueryString("sid" -> serviceId)
      .withQueryString("mixin" -> "ancestor_titles")
      .withQueryString("api_key" -> apiKey)
  }

  def recoverableError(wsResponse: WSResponse): Boolean =
    wsResponse.status == Status.INTERNAL_SERVER_ERROR ||
      wsResponse.status == Status.REQUEST_TIMEOUT ||
      wsResponse.status == Status.GATEWAY_TIMEOUT

  def toProgramAvailabilityResponse(program: ScheduledProgram, node: Node) =
    ProgramAvailabilityResponse(program, if ((node \ "results" \ "@total").text == "0") false else true)


  def toProgramList(page: Node): Seq[ScheduledProgram] =
    (page \\ "broadcast").map(node => toProgram(node))

  def toProgram(node: Node) = {
    val brandTitle = (node \ "ancestor_titles" \ "brand" \ "title").text
    val seriesTitle = (node \ "ancestor_titles" \ "series" \ "title").text
    val episodeTitle = (node \ "ancestor_titles" \ "episode" \ "title").text
    val titles = Seq(brandTitle, seriesTitle, episodeTitle).filter(!_.isEmpty).mkString(" ")
    ScheduledProgram(
      (node \ "service" \ "@sid").text,
      toEpisodePid(node),
      (node \ "published_time" \ "@start").text,
      (node \ "published_time" \ "@end").text,
      titles
    )
  }

  def toEpisodePid(node: Node) = {
    val element = node \ "broadcast_of"
    val episodeElement = element.filter(element => (element \ "@result_type").text == "episode")
    (episodeElement \ "@pid").text
  }

  def toNumberOfPages(node: Node) = {
    val totalProgrammes = (node \\ "results" \ "@total").text.toInt
    val pageSize = (node \\ "results" \ "@page_size").text.toInt

    if (totalProgrammes % pageSize == 0) totalProgrammes / pageSize else totalProgrammes / pageSize + 1
  }

  def calculateDelay(retry: Int): FiniteDuration = Math.pow(3, retry).seconds
}

