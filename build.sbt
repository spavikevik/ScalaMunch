val scalaV      = "3.3.3"
val scalametaV  = "4.17.0"
val zioV        = "2.1.26"
val sqliteV     = "3.53.1.0"
val zstdV       = "1.5.6-3"
val declineV    = "2.6.2"
val zioJsonV    = "0.7.45"

ThisBuild / scalaVersion  := scalaV
ThisBuild / version       := "0.1.0-alpha.2"
ThisBuild / organization  := "io.scalamunch"
ThisBuild / versionScheme := Some("semver-spec")

// Emit SemanticDB so ScalaMunch can populate type_deps + implicits.
// Scala 3 built-in flag; the Scala 2.12 sbt plugin clears it below.
ThisBuild / scalacOptions += "-Xsemanticdb"

// ── publish settings (used by sbt plugin; other modules are apps not libraries) ──
ThisBuild / homepage := Some(url("https://github.com/spavikevik/ScalaMunch"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / scmInfo  := Some(ScmInfo(
  url("https://github.com/spavikevik/ScalaMunch"),
  "scm:git@github.com:spavikevik/ScalaMunch.git"
))
ThisBuild / developers := List(Developer(
  id    = "spavikevik",
  name  = "Stefan Pavikjevikj",
  email = "stefan.pavikjevikj@gmail.com",
  url   = url("https://github.com/spavikevik")
))
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := Some("GitHub Package Registry" at
  "https://maven.pkg.github.com/spavikevik/ScalaMunch")
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "spavikevik",
  sys.env.getOrElse("GITHUB_TOKEN", "")
)


// Only the sbt plugin is published. All other modules are applications.
val noPublish = Seq(publish / skip := true, publishLocal / skip := true)

lazy val root = project
  .in(file("."))
  .aggregate(core, store, cli, mcpServer, tests)
  .settings(name := "scala-munch", noPublish)

lazy val core = project
  .in(file("core"))
  .settings(
    name := "scala-munch-core",
    noPublish,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta"        % scalametaV,
      "org.scalameta" %% "semanticdb-shared" % scalametaV,
      "dev.zio"       %% "zio"              % zioV,
      "dev.zio"       %% "zio-streams"      % zioV,
    )
  )

lazy val store = project
  .in(file("store"))
  .dependsOn(core)
  .settings(
    name := "scala-munch-store",
    noPublish,
    libraryDependencies ++= Seq(
      "org.xerial"        % "sqlite-jdbc" % sqliteV,
      "com.github.luben" %  "zstd-jni"   % zstdV,
      "dev.zio"          %% "zio"         % zioV,
    )
  )

lazy val cli = project
  .in(file("cli"))
  .dependsOn(store)
  .settings(
    name := "scala-munch-cli",
    noPublish,
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline" % declineV,
      "dev.zio"      %% "zio"     % zioV,
    ),
    assembly / mainClass       := Some("scalamunch.cli.Main"),
    assembly / assemblyJarName := "scala-munch-cli-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "module-info.class"                  => MergeStrategy.discard
      case "NOTICE"                             => MergeStrategy.concat
      case x                                    => (assembly / assemblyMergeStrategy).value(x)
    },
  )

// ── integration test module ───────────────────────────────────────────────────
val catsV = "2.12.0"

lazy val tests = project
  .in(file("tests"))
  .dependsOn(cli, mcpServer)
  .settings(
    name := "scala-munch-tests",
    noPublish,
    libraryDependencies ++= Seq(
      "dev.zio"       %% "zio-test"     % zioV  % Test,
      "dev.zio"       %% "zio-test-sbt" % zioV  % Test,
      // Sources jars — cats-core + cats-kernel (separate artifact)
      "org.typelevel" %% "cats-core"   % catsV % Test classifier "sources",
      "org.typelevel" %% "cats-kernel" % catsV % Test classifier "sources",
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true,
    // Pass sources jar paths to the forked JVM as system properties
    Test / javaOptions ++= {
      val cp = (Test / managedClasspath).value.map(_.data)
      def jar(name: String) = cp.find(f => f.getName.contains(name) && f.getName.contains("sources"))
      jar("cats-core").map(f => s"-Dcats.core.sources.jar=${f.getAbsolutePath}").toSeq ++
      jar("cats-kernel").map(f => s"-Dcats.kernel.sources.jar=${f.getAbsolutePath}").toSeq
    }
  )

lazy val sbtScalaMunch = project
  .in(file("sbt-plugin"))
  .settings(
    name         := "sbt-scala-munch",
    organization := "io.scalamunch",
    scalaVersion := "2.12.19",
    sbtPlugin    := true,
    scalacOptions := Nil,  // -Xsemanticdb is Scala 3-only; drop the inherited flag
  )

lazy val mcpServer = project
  .in(file("mcp-server"))
  .dependsOn(store)
  .settings(
    name := "scala-munch-mcp",
    run / fork := true,                  // required: MCP reads/writes stdin/stdout directly
    run / connectInput := true,
    // Publish the fat jar so users can reference it directly without cloning
    Compile / packageBin := assembly.value,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioV,
      "dev.zio" %% "zio-streams" % zioV,
      "dev.zio" %% "zio-json"    % zioJsonV,
    ),
    assembly / mainClass       := Some("scalamunch.mcp.McpMain"),
    assembly / assemblyJarName := "scala-munch-mcp-assembly.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case "module-info.class"                  => MergeStrategy.discard
      case "NOTICE"                             => MergeStrategy.concat
      case x                                    => (assembly / assemblyMergeStrategy).value(x)
    },
  )
