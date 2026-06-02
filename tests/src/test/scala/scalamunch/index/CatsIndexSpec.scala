package scalamunch.index

import scalamunch.fixtures.CatsFixture
import scalamunch.model.*
import scalamunch.store.IndexStore
import zio.*
import zio.test.*
import zio.test.Assertion.*

/** Integration tests against a real cats-core 2.x index.
 *
 *  cats-core 2.x typeclass hierarchy:
 *    Eq ← PartialOrder ← Order
 *    Invariant ← Functor ← Apply ← Applicative ─┐
 *                                FlatMap ─────────┴─ Monad
 */
object CatsIndexSpec extends ZIOSpecDefault:

  // Helper: run a query on the IndexStore service
  private def q[A](f: IndexStore => Task[A]): ZIO[IndexStore, Throwable, A] =
    ZIO.serviceWithZIO[IndexStore](f)

  def spec = suite("CatsIndex")(
    suite("core typeclasses indexed")(

      test("Show trait") {
        q(_.getSymbol("cats/Show#")).map(s => assertTrue(s.isDefined))
      },

      test("Functor trait") {
        q(_.getSymbol("cats/Functor#")).map(s => assertTrue(s.isDefined))
      },

      test("Monad trait") {
        q(_.getSymbol("cats/Monad#")).map(s => assertTrue(s.isDefined))
      },

      test("kernel.Eq trait") {
        q(_.getSymbol("cats/kernel/Eq#")).map(s => assertTrue(s.isDefined))
      },

      test("kernel.Order trait") {
        q(_.getSymbol("cats/kernel/Order#")).map(s => assertTrue(s.isDefined))
      },

      test("Apply trait") {
        q(_.getSymbol("cats/Apply#")).map(s => assertTrue(s.isDefined))
      },

      test("Applicative trait") {
        q(_.getSymbol("cats/Applicative#")).map(s => assertTrue(s.isDefined))
      },

      test("FlatMap trait") {
        q(_.getSymbol("cats/FlatMap#")).map(s => assertTrue(s.isDefined))
      },

      test("Show companion object") {
        q(_.getSymbol("cats/Show.")).map(s => assertTrue(s.isDefined))
      },

      test("all core typeclasses are Trait kind") {
        for
          show    <- q(_.getSymbol("cats/Show#"))
          functor <- q(_.getSymbol("cats/Functor#"))
          monad   <- q(_.getSymbol("cats/Monad#"))
        yield
          assertTrue(show.exists(_.kind   == SymbolKind.Trait)) &&
          assertTrue(functor.exists(_.kind == SymbolKind.Trait)) &&
          assertTrue(monad.exists(_.kind  == SymbolKind.Trait))
      }
    ),

    suite("type hierarchy — direct parents")(

      test("Monad extends FlatMap") {
        q(_.getSymbol("cats/Monad#")).map { m =>
          assertTrue(m.exists(_.parentFqns.exists(_.contains("FlatMap"))))
        }
      },

      test("Monad extends Applicative") {
        q(_.getSymbol("cats/Monad#")).map { m =>
          assertTrue(m.exists(_.parentFqns.exists(_.contains("Applicative"))))
        }
      },

      test("Applicative extends Apply") {
        q(_.getSymbol("cats/Applicative#")).map { a =>
          assertTrue(a.exists(_.parentFqns.exists(_.contains("Apply"))))
        }
      },

      test("Functor extends Invariant") {
        q(_.getSymbol("cats/Functor#")).map { f =>
          assertTrue(f.exists(_.parentFqns.exists(_.contains("Invariant"))))
        }
      },

      test("Order extends PartialOrder") {
        q(_.getSymbol("cats/kernel/Order#")).map { o =>
          assertTrue(o.exists(_.parentFqns.exists(_.contains("PartialOrder"))))
        }
      },

      test("PartialOrder extends Eq") {
        q(_.getSymbol("cats/kernel/PartialOrder#")).map { po =>
          assertTrue(po.exists(_.parentFqns.exists(_.contains("Eq"))))
        }
      }
    ),

    suite("core methods")(

      test("Functor.map indexed with correct signature") {
        for
          results      <- q(_.searchSymbols("map", 50))
          mapInFunctor  = results.filter(s =>
                            s.name == "map" && s.kind == SymbolKind.Def &&
                            s.enclosingFqn.exists(_.contains("Functor"))
                          )
        yield
          assertTrue(mapInFunctor.nonEmpty) &&
          assertTrue(mapInFunctor.exists(s => s.signature.contains("F[A]") || s.signature.contains("F[")))
      },

      test("FlatMap.flatMap indexed") {
        for
          results <- q(_.searchSymbols("flatMap", 20))
          fm       = results.filter(s => s.name == "flatMap" && s.kind == SymbolKind.Def)
        yield assertTrue(fm.nonEmpty)
      },

      test("Eq.eqv indexed") {
        for
          results <- q(_.searchSymbols("eqv", 10))
        yield assertTrue(results.exists(s => s.name == "eqv" && s.kind == SymbolKind.Def))
      },

      test("Show.show indexed") {
        for
          results <- q(_.searchSymbols("show", 20))
        yield assertTrue(results.exists(s => s.name == "show" && s.kind == SymbolKind.Def))
      }
    ),

    suite("typeclass instances")(

      test("Show instances use catsShow* naming convention") {
        for
          results <- q(_.searchSymbols("catsShow", 20))
        yield assertTrue(results.nonEmpty)
      },

      test("kernel instances use catsKernelStd* naming convention") {
        for
          results <- q(_.searchSymbols("catsKernelStd", 20))
        yield assertTrue(results.nonEmpty)
      }
    ),

    suite("FTS search")(

      test("'Show' FTS finds Show-related symbols") {
        for
          // Increase limit — FTS BM25 ranks multi-occurrence docs first;
          // cats/Show# is in the index (verified in 'core typeclasses indexed')
          results <- q(_.searchSymbols("Show", 200))
        yield
          assertTrue(results.nonEmpty) &&
          assertTrue(results.exists(s => s.name == "Show" || s.fqn.contains("/Show#")))
      },

      test("'Functor' finds cats/Functor#") {
        for
          results <- q(_.searchSymbols("Functor", 50))
        yield assertTrue(results.exists(_.fqn == "cats/Functor#"))
      },

      test("type search 'F[B]' finds Functor-like signatures") {
        for
          results <- q(_.searchByType("F[B]", 10))
        yield assertTrue(results.nonEmpty)
      },

      test("scaladoc present on at least one core typeclass") {
        for
          show    <- q(_.getSymbol("cats/Show#"))
          functor <- q(_.getSymbol("cats/Functor#"))
          monad   <- q(_.getSymbol("cats/Monad#"))
        yield
          assertTrue(List(show, functor, monad).flatten.exists(_.doc.nonEmpty))
      }
    ),

    suite("scale")(

      test("hundreds of symbols indexed from real cats-core") {
        for stats <- q(_.stats) yield
          assertTrue(stats.symbolCount >= 200) &&
          assertTrue(stats.fileCount   >= 50)
      }
    )
  ).provideShared(Scope.default, CatsFixture.catsIndexLayer)
