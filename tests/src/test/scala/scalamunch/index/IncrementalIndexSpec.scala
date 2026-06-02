package scalamunch.index

import scalamunch.cli.Indexer
import scalamunch.model.ScalaVersion
import scalamunch.store.IndexStore
import zio.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}

/** Tests for digest-based incremental indexing (unchanged files skipped, modified reindexed). */
object IncrementalIndexSpec extends ZIOSpecDefault:

  // ── fixtures ──────────────────────────────────────────────────────────────

  private def mkTempDir: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("scala-munch-incr-"))
    )(dir => ZIO.attempt(deleteRecursive(dir)).orDie)

  private def deleteRecursive(p: Path): Unit =
    if Files.isDirectory(p) then Files.list(p).forEach(deleteRecursive)
    Files.deleteIfExists(p)

  private def writeFile(dir: Path, name: String, content: String): Task[Path] =
    ZIO.attempt { val f = dir.resolve(name); Files.writeString(f, content); f }

  private def cfg(root: Path) = Indexer.IndexConfig(
    sourceRoot    = root,
    dbPath        = Paths.get(":memory:"),
    scalaVersion  = ScalaVersion.Scala3,
    useSemanticDb = false,
    force         = false
  )

  private def build(cfg: Indexer.IndexConfig, store: IndexStore) =
    Indexer.build(cfg).provide(ZLayer.succeed(store))

  // ── source variants ───────────────────────────────────────────────────────

  private val srcV1 = """package incr
case class Widget(id: Int)
object Widget:
  def empty: Widget = Widget(0)
"""

  private val srcV2 = """package incr
case class Widget(id: Int, label: String)
object Widget:
  def empty: Widget = Widget(0, "")
  def fromId(n: Int): Widget = Widget(n, n.toString)
"""

  private val srcBar = """package incr
trait Bar[F[_]]:
  def run(f: F[Int]): Int
"""

  // ── tests ─────────────────────────────────────────────────────────────────

  def spec = suite("incremental indexing")(

    test("initial build indexes all files") {
      ZIO.scoped {
        for
          dir   <- mkTempDir
          _     <- writeFile(dir, "Widget.scala", srcV1)
          _     <- writeFile(dir, "Bar.scala", srcBar)
          store <- IndexStore.inMemory
          stats <- build(cfg(dir), store)
        yield assertTrue(stats.fileCount == 2 && stats.symbolCount > 0)
      }
    },

    test("unchanged files skipped on re-index") {
      ZIO.scoped {
        for
          dir    <- mkTempDir
          _      <- writeFile(dir, "Widget.scala", srcV1)
          store  <- IndexStore.inMemory
          stats1 <- build(cfg(dir), store)
          stats2 <- build(cfg(dir), store)   // identical content — digest hit
        yield
          // symbol count must not grow: skipped means no double-insertion
          assertTrue(stats2.symbolCount == stats1.symbolCount)
      }
    },

    test("modified file is reindexed") {
      ZIO.scoped {
        for
          dir    <- mkTempDir
          file   <- writeFile(dir, "Widget.scala", srcV1)
          store  <- IndexStore.inMemory
          _      <- build(cfg(dir), store)
          before <- store.searchSymbols("fromId", 5)
          _      <- ZIO.attempt(Files.writeString(file, srcV2))
          _      <- build(cfg(dir), store)
          after  <- store.searchSymbols("fromId", 5)
        yield assertTrue(before.isEmpty && after.nonEmpty)
      }
    },

    test("old symbols removed when file content changes") {
      ZIO.scoped {
        for
          dir    <- mkTempDir
          file   <- writeFile(dir, "Widget.scala", srcV1)
          store  <- IndexStore.inMemory
          _      <- build(cfg(dir), store)
          // srcV2 renames field — old sig disappears
          _      <- ZIO.attempt(Files.writeString(file, srcV2))
          _      <- build(cfg(dir), store)
          widget <- store.getSymbol("incr/Widget#")
        yield
          // Widget still exists but signature reflects v2 (has label field)
          assertTrue(widget.exists(_.signature.contains("label")))
      }
    },

    test("invalidateFile removes symbols from that file") {
      ZIO.scoped {
        for
          dir   <- mkTempDir
          file  <- writeFile(dir, "Widget.scala", srcV1)
          store <- IndexStore.inMemory
          _     <- build(cfg(dir), store)
          fqn    = "incr/Widget#"
          before <- store.getSymbol(fqn)
          n      <- store.invalidateFile(file.toString)
          after  <- store.getSymbol(fqn)
        yield assertTrue(before.isDefined && n > 0 && after.isEmpty)
      }
    },

    test("force=true reindexes despite matching digest") {
      ZIO.scoped {
        for
          dir    <- mkTempDir
          _      <- writeFile(dir, "Widget.scala", srcV1)
          store  <- IndexStore.inMemory
          stats1 <- build(cfg(dir), store)
          // Force: even though content is identical
          stats2 <- build(cfg(dir).copy(force = true), store)
        yield assertTrue(stats1.symbolCount == stats2.symbolCount)
      }
    },

    test("multiple files: only changed file reindexed") {
      ZIO.scoped {
        for
          dir    <- mkTempDir
          widget <- writeFile(dir, "Widget.scala", srcV1)
          _      <- writeFile(dir, "Bar.scala", srcBar)
          store  <- IndexStore.inMemory
          _      <- build(cfg(dir), store)
          barBefore <- store.getSymbol("incr/Bar#")
          // Modify only Widget
          _     <- ZIO.attempt(Files.writeString(widget, srcV2))
          _     <- build(cfg(dir), store)
          barAfter <- store.getSymbol("incr/Bar#")
        yield
          // Bar unchanged — still present, same fqn
          assertTrue(barBefore.isDefined && barAfter.isDefined &&
                     barBefore.map(_.fqn) == barAfter.map(_.fqn))
      }
    },

  )
