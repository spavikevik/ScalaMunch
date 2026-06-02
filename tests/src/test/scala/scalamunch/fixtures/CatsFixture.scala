package scalamunch.fixtures

import scalamunch.cli.Indexer
import scalamunch.model.ScalaVersion
import scalamunch.store.IndexStore
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Builds an in-memory index from real cats-core + cats-kernel sources (pinned version).
 *
 *  Jar paths are passed via system properties set in build.sbt:
 *    cats.core.sources.jar   — cats-core-*-sources.jar
 *    cats.kernel.sources.jar — cats-kernel-*-sources.jar
 */
object CatsFixture:

  val CatsVersion = "2.12.0"

  val catsIndexLayer: ZLayer[Scope, Throwable, IndexStore] =
    ZLayer.fromZIO {
      for
        store <- IndexStore.inMemory
        root  <- ZIO.attempt(extractCatsSources())
        cfg    = Indexer.IndexConfig(
                   sourceRoot    = root,
                   dbPath        = Paths.get(":memory:"),
                   scalaVersion  = ScalaVersion.Scala3,
                   useSemanticDb = false,
                   force         = true
                 )
        stats <- Indexer.build(cfg).provide(ZLayer.succeed(store))
        _     <- ZIO.logInfo(
                   s"cats $CatsVersion: ${stats.symbolCount} symbols from ${stats.fileCount} files"
                 )
      yield store
    }

  // ── extraction ─────────────────────────────────────────────────────────────

  private def extractCatsSources(): Path =
    val dest = Files.createTempDirectory("scala-munch-cats-")
    locateJars().foreach(jar => extractScalaFiles(jar, dest))
    dest

  /** Locate both cats-core and cats-kernel sources jars. */
  private def locateJars(): List[Path] =
    List(
      findJar("cats.core.sources.jar",   "cats-core"),
      findJar("cats.kernel.sources.jar", "cats-kernel")
    ).flatten

  private def findJar(sysProp: String, artifactFragment: String): Option[Path] =
    // Primary: system property set by sbt build
    Option(java.lang.System.getProperty(sysProp)).map(Paths.get(_))
      // Fallback: scan java.class.path
      .orElse {
        java.lang.System.getProperty("java.class.path")
          .split(java.io.File.pathSeparator)
          .find(p => p.contains(artifactFragment) && p.contains("sources"))
          .map(Paths.get(_))
      }

  private def extractScalaFiles(jar: Path, dest: Path): Unit =
    val zf = new ZipFile(jar.toFile)
    try
      zf.entries().asScala
        .filter(e => !e.isDirectory && e.getName.endsWith(".scala"))
        .foreach { entry =>
          val target = dest.resolve(entry.getName)
          Files.createDirectories(target.getParent)
          val in = zf.getInputStream(entry)
          try Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
          finally in.close()
        }
    finally zf.close()
