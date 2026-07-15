package scalamunch.store

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** Regression test: multiple processes/fibers opening the same on-disk db concurrently
 *  must not crash. Each IndexStore.open runs initSchema (WAL pragma + DDL + trigger
 *  DROP/CREATE) against the shared file, so concurrent opens contend for the SQLite
 *  write lock — this previously surfaced as an uncaught SQLITE_BUSY that killed the
 *  MCP server on startup whenever a second client session opened the same project db.
 */
object IndexStoreOpenSpec extends ZIOSpecDefault:

  def spec = suite("IndexStore.open concurrency")(

    test("many concurrent opens against the same file all succeed") {
      ZIO.scoped {
        for
          tmpDir <- ZIO.attempt(Files.createTempDirectory("index-store-open-spec"))
          dbPath  = tmpDir.resolve("shared.db")
          results <- ZIO.foreachPar(1 to 12) { _ =>
                       ZIO.scoped(IndexStore.open(dbPath).flatMap(_.stats)).either
                     }
        yield assertTrue(results.forall(_.isRight))
      }
    } @@ TestAspect.withLiveClock,

  )
