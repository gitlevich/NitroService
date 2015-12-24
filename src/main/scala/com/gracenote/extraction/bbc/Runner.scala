package com.gracenote.extraction.bbc

import java.io.File

import com.github.tototoshi.csv.CSVWriter

import scala.util.{Failure, Success}

object Runner extends App{
  require(args.length == 2, "StartDate and EndDate must be given as two arguments")
  val startDate = args(0)
  val endDate= args(1)

  val serviceList = Seq(
      "bbc_alba"
        , "bbc_four"
        , "bbc_four_hd"
        , "bbc_news_channel_hd"
        , "bbc_news24"
        , "bbc_one_cambridge"
        , "bbc_one_channel_islands"
        , "bbc_one_east"
        , "bbc_one_east_midlands"
        , "bbc_one_east_yorkshire"
        , "bbc_one_hd"
        , "bbc_one_london"
        , "bbc_one_north_east"
        , "bbc_one_north_west"
        , "bbc_one_northern_ireland"
        , "bbc_one_northern_ireland_hd"
        , "bbc_one_oxford"
        , "bbc_one_scotland"
        , "bbc_one_scotland_hd"
        , "bbc_one_south"
        , "bbc_one_south_east"
        , "bbc_one_south_west"
        , "bbc_one_wales"
        , "bbc_one_wales_hd"
        , "bbc_one_west"
        , "bbc_one_west_midlands"
        , "bbc_one_yorks"
        , "bbc_parliament"
        , "bbc_television_service"
        , "bbc_television_service_northern_ireland"
        , "bbc_television_service_scotland"
        , "bbc_television_service_wales"
        , "bbc_three"
        , "bbc_three_hd"
        , "bbc_two_england"
        , "bbc_two_hd"
        , "bbc_two_northern_ireland"
        , "bbc_two_scotland"
        , "bbc_two_wales"
        , "cbbc"
        , "cbbc_hd"
        , "cbeebies"
        , "cbeebies_hd"
    )
  val startTime = System.currentTimeMillis()

    val nitroService = new NitroService(new NitroDao)

    val writer = CSVWriter.open(new File("out.csv"))
    writer.writeRow(Seq("service", "pid", "title", "start_time", "end_time"))
    serviceList.foreach(service =>
        nitroService.fetchSchedule(service, DateRange(scheduleDayFrom = startDate, scheduleDayTo = endDate)) match {
            case Success(programs) => programs
              .sortBy(p => (p.sid, p.startTime))
              .foreach(program => writer.writeRow(Seq(program.sid, program.pid, program.title, program.startTime, program.endTime)))
            case Failure(e) => println(e)
        }
    )

    writer.close()
    println(s"Runner finished in ${System.currentTimeMillis() - startTime}")

}