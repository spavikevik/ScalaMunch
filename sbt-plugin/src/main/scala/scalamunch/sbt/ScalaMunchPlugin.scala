package scalamunch.sbt

import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import scala.sys.process.{Process, ProcessLogger}

object ScalaMunchPlugin extends AutoPlugin {

  override def trigger  = noTrigger
  override def requires = JvmPlugin

  object autoImport {
    val scalaMunchEnabled    = settingKey[Boolean]("Enable scala-munch indexing (default: true)")
    val scalaMunchDb         = settingKey[File]("scala-munch index DB path (default: <baseDir>/.scala-munch.db)")
    val scalaMunchExecutable = settingKey[String]("scala-munch CLI name or absolute path (default: scala-munch)")
    val scalaMunchExtraArgs  = settingKey[Seq[String]]("Extra args forwarded to `scala-munch build`")
    val scalaMunchIndex      = taskKey[Unit]("Compile then incrementally index symbols; use `sbt ~scalaMunchIndex` for continuous")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    scalaMunchEnabled    := true,
    scalaMunchDb         := baseDirectory.value / ".scala-munch.db",
    scalaMunchExecutable := "scala-munch",
    scalaMunchExtraArgs  := Nil,
    scalaMunchIndex      := scalaMunchIndexTask.value,
  )

  private lazy val scalaMunchIndexTask: Def.Initialize[Task[Unit]] = Def.task {
    val _           = (Compile / compile).value   // must compile first
    val log         = streams.value.log
    val enabled     = scalaMunchEnabled.value
    val exe         = scalaMunchExecutable.value
    val db          = scalaMunchDb.value
    val srcDirs     = (Compile / unmanagedSourceDirectories).value
    val scOpts      = (Compile / scalacOptions).value
    val extra       = scalaMunchExtraArgs.value

    if (!enabled) {
      log.debug("[scala-munch] disabled")
    } else {
      val hasSemanticDb = scOpts.exists(o => o == "-Xsemanticdb" || o == "-Ysemanticdb")
      val noSdbFlag     = if (hasSemanticDb) Seq.empty[String] else Seq("--no-semanticdb")

      srcDirs.filter(_.exists()).foreach { srcDir =>
        val cmd = Seq(exe, "build", srcDir.toString, "--db", db.toString) ++ noSdbFlag ++ extra
        log.info(s"[scala-munch] indexing ${srcDir.getName}")
        val pl       = ProcessLogger(out => log.debug(s"[scala-munch] $out"), err => log.warn(s"[scala-munch] $err"))
        val exitCode = Process(cmd) ! pl
        if (exitCode != 0) log.warn(s"[scala-munch] scala-munch exited $exitCode")
      }
    }
  }
}
