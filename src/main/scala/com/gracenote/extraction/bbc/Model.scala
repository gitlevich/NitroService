package com.gracenote.extraction.bbc

import java.lang.Math
import javax.xml.ws.WebServiceException

import akka.util.Timeout
import play.api.libs.ws.{WSRequest, WSResponse}
import play.mvc.Http.Status

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.xml.Node

case class DateRange(scheduleDayFrom: String, scheduleDayTo: String)

case class ScheduledProgramme(sid: String, pid: String, startTime: String, endTime: String, title: String)

class NitroDao {
  val nitroScheduleBaseUri = "http://programmes.api.bbc.com/nitro/api/schedules"
  val nitroProgrammesBaseUri = "http://programmes.api.bbc.com/nitro/api/programmes"
  val apiKey = "kheF9DxuX0j7lgAleY7Ewp57USjYDsl2"
  val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder()
  val WS = new play.api.libs.ws.ning.NingWSClient(builder.build())
  implicit val timeout = Timeout(1 minute)
  val pause = 600

  def getScheduleNodeWithRetry(scheduleRequest: DateRange, service: String, pageNumber: Int, remainingRetries: Int = 3): Option[Node] =
    getScheduleNode(scheduleRequest, service, pageNumber) match {
      case Success(node) => Some(node)
      case Failure(e) if remainingRetries > 0 =>
        println(s"Received not ok response. Retrying again ${remainingRetries - 1} times")
        Thread.sleep(Math.pow(3, 4 - remainingRetries).toInt * pause)
        getScheduleNodeWithRetry(scheduleRequest, service, pageNumber, remainingRetries - 1)
      case Failure(e) => None
    }

  private def getScheduleNode(scheduleRequest: DateRange, service: String, pageNumber: Int): Try[Node] = Try {
    val request = WS.url(nitroScheduleBaseUri)
      .withQueryString("page" -> pageNumber.toString)
      .withQueryString("sort" -> "start_date")
      .withQueryString("schedule_day_from" -> scheduleRequest.scheduleDayFrom)
      .withQueryString("schedule_day_to" -> scheduleRequest.scheduleDayTo)
      .withQueryString("sid" -> service)
      .withQueryString("mixin" -> "ancestor_titles")
      .withQueryString("api_key" -> apiKey)

    fetchNodeFor(request)
  }

  def getAvailabilityWithRetry(pid: String, remainingRetries: Int = 3): Option[Node] =
    getAvailability(pid) match {
      case Success(node) => Some(node)
      case Failure(e) if remainingRetries > 0 =>
        println(s"Received not ok response. Retrying again ${remainingRetries - 1} times")
        Thread.sleep(Math.pow(3, 4 - remainingRetries).toInt * pause)
        getAvailabilityWithRetry(pid, remainingRetries - 1)
      case Failure(e) => None
    }

  def getAvailability(pid: String): Try[Node] = Try {
    val request: WSRequest = WS.url(nitroProgrammesBaseUri)
      .withQueryString("pid" -> pid)
      .withQueryString("availability" -> "available")
      .withQueryString("availability_entity_type" -> "episode")
      .withQueryString("entity_type" -> "episode")
      .withQueryString("availability" -> "P5D")
      .withQueryString("media_set" -> "stb-all-h264")
      .withQueryString("api_key" -> apiKey)

    fetchNodeFor(request)
  }

  def fetchNodeFor(request: WSRequest): Node = {
    val response = Await.result(request.get(), 120 seconds)
    Thread.sleep(pause)
    toNode(response)
  }


  def toNode(response: WSResponse): Node =
    if (response.status == Status.OK) response.xml
    else if (response.status == Status.INTERNAL_SERVER_ERROR
      || response.status == Status.REQUEST_TIMEOUT
      || response.status == Status.GATEWAY_TIMEOUT)
      throw new WebServiceException("Internal Server Error : " + response.body)
    else
      throw new IllegalArgumentException("Received response status: " + response.status + " with error body: " + response.body)
}