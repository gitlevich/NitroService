package com.gracenote.extraction.bbc.original

import scala.util.Try
import scala.xml.Node

class NitroService(dao: NitroDao) {

  def fetchSchedule(service: String, scheduleRequest: DateRange): Try[Seq[ScheduledProgramme]] =
    Try(fetchScheduledProgrammes(scheduleRequest, service))

  def fetchScheduledProgrammes(dateRange: DateRange, service: String): Seq[ScheduledProgramme] = {
    println(s"Start getting data for channel $service")
    dao.getScheduleNodeWithRetry(dateRange, service, 1) match {
      case Some(node) =>
        val firstPageProgrammeList = toProgrammeList(node)
        val totalProgrammes = (node \\ "results" \ "@total").text.toInt
        val pageSize = (node \\ "results" \ "@page_size").text.toInt

        val noOfPages = if (totalProgrammes % pageSize == 0) totalProgrammes / pageSize else totalProgrammes / pageSize + 1 //or Round Up?

        val subsequentPagesProgrammeList = 2 to noOfPages flatMap { pageNumber =>
          dao.getScheduleNodeWithRetry(dateRange, service, pageNumber) match {
            case Some(subsequentNode) => toProgrammeList(subsequentNode)
            case None => Seq.empty[ScheduledProgramme]
          }
        }

        firstPageProgrammeList ++ subsequentPagesProgrammeList

      case None => Seq.empty[ScheduledProgramme]
    }
  }

  def toProgrammeList(page: Node): Seq[ScheduledProgramme] =
    (page \\ "broadcast").map(node => toProgramme(node)).filter(programme => fetchAvailabilityFor(programme).isDefined)

  def toProgramme(node: Node): ScheduledProgramme = ScheduledProgramme(
    (node \ "service" \ "@sid").text,
    toEpisodePid(node),
    (node \ "published_time" \ "@start").text,
    (node \ "published_time" \ "@end").text,
    (node \ "ancestor_titles" \ "brand" \ "title").text + " " + (node \ "ancestor_titles" \ "series" \ "title").text + " " + (node \ "ancestor_titles" \ "episode" \ "title").text
  )

  def toEpisodePid(node: Node): String = {
    val element = node \ "broadcast_of"
    val episodeElement = element.filter(element => (element \ "@result_type").text == "episode")
    (episodeElement \ "@pid").text
  }

  def fetchAvailabilityFor(programme: ScheduledProgramme): Option[ScheduledProgramme] = {
    dao.getAvailabilityWithRetry(programme.pid) match {
      case Some(availabilityNode) if (availabilityNode \ "results" \ "@total").text == "0" => None
      case _ => Some(programme)
    }
  }
}
