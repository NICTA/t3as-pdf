import ReleaseTransformations._
import com.typesafe.sbt.license.{DepModuleInfo, LicenseInfo}

// default release process, but without publishArtifacts
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  // publishArtifacts,
  setNextVersion,
  commitNextVersion,
  pushChanges
)

organization := "org.t3as"

name := "t3as-pdf"

// version := "0.2" // see version.sbt maintained bt sbt-release plugin

licenses := Seq("GNU Affero General Public License v3" -> url("http://www.gnu.org/licenses/agpl-3.0.en.html"))

homepage := Some(url("https://github.com/NICTA/t3as-pdf"))

scalaVersion := "2.11.2"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

EclipseKeys.withSource := true

// If Eclipse and sbt are both building to same dirs at same time it takes forever and produces corrupted builds.
// So here we tell Eclipse to build somewhere else (bin is it's default build output folder)
EclipseKeys.eclipseOutput in Compile := Some("bin")   // default is sbt's target/scala-2.11/classes

EclipseKeys.eclipseOutput in Test := Some("test-bin") // default is sbt's target/scala-2.11/test-classes

EclipseKeys.createSrc := EclipseCreateSrc.Default + EclipseCreateSrc.Resource

net.virtualvoid.sbt.graph.Plugin.graphSettings

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
  
def hasPrefix(org: String, prefixes: Seq[String]) = prefixes.exists(x => org.startsWith(x))

licenseOverrides := {
  case DepModuleInfo(org, _, _) if hasPrefix(org, Seq("ch.qos.logback")) => LicenseInfo(LicenseCategory.LGPL, "EPL + GNU Lesser General Public License", "http://logback.qos.ch/license.html")
  case DepModuleInfo(org, _, _) if hasPrefix(org, Seq("org.slf4j")) => LicenseInfo(LicenseCategory.MIT, "MIT License", "http://www.slf4j.org/license.html")
  }
