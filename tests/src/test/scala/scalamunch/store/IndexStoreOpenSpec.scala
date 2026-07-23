package scalamunch.store

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** Regression test: multiple processes/fibers opening the same on-disk db concurrently
 *  must not crash. Each IndexStore.open runs initSchema (WAL pragma + DDL + trigger
 *  DROP/CREATE) against the shared file, so concurrent opens contend for the SQLite
 *  write lock — this previously surfaced as an uncaught SQLITE_BUSY (and its extended
 *  SQLITE_BUSY_SNAPSHOT variant) that killed the MCP server on startup whenever a
 *  second client session opened the same project db.
 *
 *  The parallelism is deliberately high enough to actually reproduce the crash on
 *  Linux/macOS, not just Windows: at 32-way this fails reliably against the pre-fix
 *  code (no retry / narrow busy-code match) and passes reliably with the fix, so it
 *  guards the fix on CI instead of passing vacuously.
 */
object IndexStoreOpenSpec extends ZIOSpecDefault:

  def spec = suite("IndexStore.open concurrency")(

    test("many concurrent opens against the same file all succeed") {
      ZIO.scoped {
        for
          tmpDir <- ZIO.attempt(Files.createTempDirectory("index-store-open-spec"))
          dbPath  = tmpDir.resolve("shared.db")
          results <- ZIO.foreachPar(1 to 32) { _ =>
                       ZIO.scoped(IndexStore.open(dbPath).flatMap(_.stats)).either
                     }
        yield assertTrue(results.forall(_.isRight))
      }
    } @@ TestAspect.withLiveClock,

  )
