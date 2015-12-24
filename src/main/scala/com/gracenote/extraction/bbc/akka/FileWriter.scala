package com.gracenote.extraction.bbc.akka

import java.io.File

import akka.actor._
import com.github.tototoshi.csv.CSVWriter
import com.gracenote.extraction.bbc.akka.Coordinator.ScheduledProgram
import com.gracenote.extraction.bbc.akka.FileWriter.OpenFile

object FileWriter {
  case class OpenFile(file: File)
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
