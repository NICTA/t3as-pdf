organization := "org.t3as"

name := "pdf"

version := "0.1"

licenses := Seq("GNU Affero General Public License v3" -> url("http://www.gnu.org/licenses/agpl-3.0.en.html"))

homepage := Some(url("https://github.com/NICTA/t3as-pdf"))

net.virtualvoid.sbt.graph.Plugin.graphSettings

scalaVersion := "2.11.2"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

com.github.retronym.SbtOneJar.oneJarSettings

mainClass in (Compile, run) := Some("org.t3as.pdf.Pdf")

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "3.2.0",
  "com.jsuereth" %% "scala-arm" % "1.4",
  "org.scalatest" %% "scalatest" % "2.2.1-M1" % Test,
  "com.itextpdf" % "itextpdf" % "5.5.2",
  "org.slf4j" % "slf4j-api" % "1.7.6",
  "ch.qos.logback" % "logback-classic" % "1.1.1" % "runtime"
  )
