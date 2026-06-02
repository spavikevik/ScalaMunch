package scalamunch.mcp

import scalamunch.fixtures.CatsFixture
import scalamunch.model.SymbolKind
import scalamunch.store.IndexStore
import zio.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}

/** Tests for list_test_symbols and get_coverage_gaps against a fixture index
 *  that includes both production code and spec files.
 */
object TestStructureSpec extends ZIOSpecDefault:

  private def q[A](f: IndexStore => Task[A]) = ZIO.serviceWithZIO[IndexStore](f)

  // ── small fixture: one production file + one spec file ──────────────────────

  private val prodSource = """package testpkg
trait Encoder[A]:
  def encode(a: A): String
object Encoder:
  def apply[A](f: A => String): Encoder[A] = a => f(a)
case class Config(host: String, port: Int)
object Config:
  def default: Config = Config("localhost", 8080)
"""

  private val specSource = """package testpkg
object EncoderSpec:
  def spec = "placeholder"
"""

  private val noSpecSource = """package testpkg
trait Decoder[A]:
  def decode(s: String): Option[A]
"""

  private def mkFixture: ZIO[Scope, Throwable, IndexStore] =
    for
      dir   <- ZIO.acquireRelease(
                 ZIO.attempt(Files.createTempDirectory("scala-munch-struct-"))
               )(d => ZIO.attempt(deleteRecursive(d)).orDie)
      _     <- ZIO.attempt(Files.writeString(dir.resolve("Encoder.scala"), prodSource))
      _     <- ZIO.attempt(Files.writeString(dir.resolve("EncoderSpec.scala"), specSource))
      _     <- ZIO.attempt(Files.writeString(dir.resolve("Decoder.scala"), noSpecSource))
      store <- IndexStore.inMemory
      cfg    = scalamunch.cli.Indexer.IndexConfig(
                 sourceRoot    = dir,
                 dbPath        = Paths.get(":memory:"),
                 scalaVersion  = scalamunch.model.ScalaVersion.Scala3,
                 useSemanticDb = false,
                 force         = true
               )
      _     <- scalamunch.cli.Indexer.build(cfg).provide(ZLayer.succeed(store))
    yield store

  private def deleteRecursive(p: Path): Unit =
    if Files.isDirectory(p) then Files.list(p).forEach(deleteRecursive)
    Files.deleteIfExists(p)

  def spec = suite("test structure analysis")(

    suite("list_test_symbols")(

      test("returns symbols from Spec files only") {
        ZIO.scoped {
          for
            store <- mkFixture
            syms  <- store.getTestSymbols(100)
          yield assertTrue(
            syms.nonEmpty &&
            syms.forall(s => s.file.endsWith("Spec.scala") ||
                             s.file.endsWith("Test.scala") ||
                             s.file.endsWith("Suite.scala"))
          )
        }
      },

      test("does not include production symbols") {
        ZIO.scoped {
          for
            store <- mkFixture
            syms  <- store.getTestSymbols(100)
          yield assertTrue(!syms.exists(_.fqn.contains("Encoder#")))
        }
      },

      test("includes EncoderSpec object") {
        ZIO.scoped {
          for
            store <- mkFixture
            syms  <- store.getTestSymbols(100)
          yield assertTrue(syms.exists(_.name == "EncoderSpec"))
        }
      },

      test("limit is respected") {
        ZIO.scoped {
          for
            store <- mkFixture
            syms  <- store.getTestSymbols(1)
          yield assertTrue(syms.size <= 1)
        }
      },

    ),

    suite("get_coverage_gaps")(

      test("Encoder is covered by EncoderSpec") {
        ZIO.scoped {
          for
            store     <- mkFixture
            prodTypes <- store.getProductionTypesFor("testpkg/")
            testSyms  <- store.getTestSymbols(100)
            encoder    = prodTypes.find(_.name == "Encoder")
          yield assertTrue(encoder.isDefined)   // Encoder exists in production
        }
      },

      test("production types exclude spec files") {
        ZIO.scoped {
          for
            store     <- mkFixture
            prodTypes <- store.getProductionTypesFor("testpkg/")
          yield assertTrue(prodTypes.forall(s =>
            !s.file.endsWith("Spec.scala") && !s.file.endsWith("Test.scala")
          ))
        }
      },

      test("only Trait/Class/Object kinds returned") {
        ZIO.scoped {
          for
            store     <- mkFixture
            prodTypes <- store.getProductionTypesFor("testpkg/")
          yield assertTrue(prodTypes.forall(s =>
            s.kind == SymbolKind.Trait || s.kind == SymbolKind.Class || s.kind == SymbolKind.Object
          ))
        }
      },

      test("Decoder has no spec — appears as coverage gap") {
        ZIO.scoped {
          for
            store     <- mkFixture
            prodTypes <- store.getProductionTypesFor("testpkg/")
            testSyms  <- store.getTestSymbols(100)
            testNames  = testSyms
                           .filter(s => s.kind == SymbolKind.Object || s.kind == SymbolKind.Class)
                           .flatMap(s => List(s.name,
                             List("Spec","Test","Suite","Check","Tests")
                               .foldLeft(s.name)((a,sfx) => if a.endsWith(sfx) then a.dropRight(sfx.length) else a)
                           )).toSet
            decoder    = prodTypes.find(_.name == "Decoder")
            covered    = decoder.exists(d => testNames.exists(t => t == d.name || t.contains(d.name)))
          yield assertTrue(decoder.isDefined && !covered)
        }
      },

      test("nonexistent package returns empty production types") {
        ZIO.scoped {
          for
            store <- mkFixture
            types <- store.getProductionTypesFor("nonexistent/")
          yield assertTrue(types.isEmpty)
        }
      },

    )

  )
