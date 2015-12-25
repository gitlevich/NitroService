name := "nitro-scala"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "org.ccil.cowan.tagsoup" % "tagsoup" % "1.2.1",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.3",
  "com.typesafe.play" %% "play-ws" % "2.4.4",
  "com.github.tototoshi" %% "scala-csv" % "1.2.2",
  "com.typesafe.akka" % "akka-contrib_2.11" % "2.4.1",
  "com.github.nscala-time" %% "nscala-time" % "2.6.0",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.1" % "test",
  "org.scalamock" %% "scalamock-scalatest-support" % "3.2" % "test",
  "org.mockito" % "mockito-all" % "1.9.5" % "test"
)

    