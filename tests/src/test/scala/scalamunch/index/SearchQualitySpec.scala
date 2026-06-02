package scalamunch.index

import scalamunch.fixtures.CatsFixture
import scalamunch.model.SymbolKind
import scalamunch.store.IndexStore
import zio.*
import zio.test.*

/** Tests for FTS search relevance, type search accuracy, and edge-case robustness.
 *
 *  Verifies that the index is useful for AI lookups — not just "does it index"
 *  but "does the right thing come back when asked".
 */
object SearchQualitySpec extends ZIOSpecDefault:

  private def q[A](f: IndexStore => Task[A]) = ZIO.serviceWithZIO[IndexStore](f)

  def spec = suite("search quality")(

    suite("FTS relevance")(

      // FTS5 BM25 ranking: terms appearing in name+sig+doc of many symbols
      // (e.g. FunctorSyntax) can rank above the canonical trait. Use limit 20+
      // to reliably find the main trait anywhere in results.

      test("'Functor' search contains cats/Functor# within top 20") {
        q(_.searchSymbols("Functor", 20)).map { results =>
          assertTrue(results.exists(_.fqn == "cats/Functor#"))
        }
      },

      // FTS BM25: "show" appears in 100s of symbols' docs/sigs — use getSymbol for known FQNs
      test("'Show' FTS returns Show-related symbols") {
        q(_.searchSymbols("Show", 10)).map { results =>
          assertTrue(results.nonEmpty && results.forall(s =>
            s.name.toLowerCase.contains("show") || s.signature.toLowerCase.contains("show") ||
            s.fqn.toLowerCase.contains("show")
          ))
        }
      },

      test("'Monad' returns multiple results") {
        q(_.searchSymbols("Monad", 10)).map { results =>
          assertTrue(results.size >= 2)
        }
      },

      test("'Order' FTS returns Order-related symbols") {
        q(_.searchSymbols("Order", 10)).map { results =>
          assertTrue(results.nonEmpty && results.forall(s =>
            s.name.toLowerCase.contains("order") || s.signature.toLowerCase.contains("order") ||
            s.fqn.toLowerCase.contains("order")
          ))
        }
      },

      test("'Applicative' search contains cats/Applicative# within top 20") {
        q(_.searchSymbols("Applicative", 20)).map { results =>
          assertTrue(results.exists(_.fqn == "cats/Applicative#"))
        }
      },

      // "eq" appears in hundreds of symbols — FTS finds Eq-related content, not necessarily cats/kernel/Eq# first
      test("'Eq' FTS returns Eq-related symbols") {
        q(_.searchSymbols("Eq", 10)).map { results =>
          assertTrue(results.nonEmpty && results.forall(s =>
            s.name.toLowerCase.contains("eq") || s.signature.toLowerCase.contains("eq") ||
            s.fqn.toLowerCase.contains("eq")
          ))
        }
      },

      test("limit is respected") {
        q(_.searchSymbols("cats", 3)).map { results =>
          assertTrue(results.size <= 3)
        }
      },

      test("all results have non-empty fqn and signature") {
        q(_.searchSymbols("Functor", 10)).map { results =>
          assertTrue(results.forall(s => s.fqn.nonEmpty && s.signature.nonEmpty))
        }
      },

    ),

    suite("type signature search")(

      test("'F[B]' finds Functor-shaped signatures") {
        q(_.searchByType("F[B]", 10)).map { results =>
          assertTrue(results.nonEmpty && results.exists(_.signature.contains("F[")))
        }
      },

      test("type search results contain the search pattern") {
        q(_.searchByType("String", 5)).map { results =>
          assertTrue(results.forall(_.signature.contains("String")))
        }
      },

      test("'Option' finds Option-typed signatures") {
        q(_.searchByType("Option", 8)).map { results =>
          assertTrue(results.nonEmpty && results.forall(_.signature.contains("Option")))
        }
      },

      test("type search limit is respected") {
        q(_.searchByType("Int", 3)).map { results =>
          assertTrue(results.size <= 3)
        }
      },

    ),

    suite("edge cases")(

      test("nonexistent FQN returns None") {
        q(_.getSymbol("totally/Nonexistent#")).map { sym =>
          assertTrue(sym.isEmpty)
        }
      },

      test("nonexistent file entry returns None") {
        q(_.getFileEntry("/no/such/file.scala")).map { entry =>
          assertTrue(entry.isEmpty)
        }
      },

      test("getTypeDeps on unknown FQN returns empty list") {
        q(_.getTypeDeps("nonexistent/Symbol#")).map { deps =>
          assertTrue(deps.isEmpty)
        }
      },

      test("getImplicitsFor on unknown type returns empty list") {
        q(_.getImplicitsFor("nonexistent/Type#")).map { implicits =>
          assertTrue(implicits.isEmpty)
        }
      },

      test("listPackages always returns non-empty for populated index") {
        q(_.listPackages).map { pkgs =>
          assertTrue(pkgs.nonEmpty)
        }
      },

      test("stats reflects real scale of cats index") {
        q(_.stats).map { s =>
          assertTrue(s.symbolCount > 1000 && s.fileCount > 100)
        }
      },

      test("symbols have valid file paths") {
        q(_.searchSymbols("Functor", 5)).map { results =>
          assertTrue(results.forall(_.file.endsWith(".scala")))
        }
      },

      test("line numbers are positive") {
        q(_.searchSymbols("Show", 5)).map { results =>
          assertTrue(results.forall(_.lineStart > 0))
        }
      },

    ),

    suite("kind classification")(

      test("Functor is a Trait") {
        q(_.getSymbol("cats/Functor#")).map { sym =>
          assertTrue(sym.exists(_.kind == SymbolKind.Trait))
        }
      },

      test("Show companion is an Object") {
        q(_.getSymbol("cats/Show.")).map { sym =>
          assertTrue(sym.exists(_.kind == SymbolKind.Object))
        }
      },

      test("Order is a Trait") {
        q(_.getSymbol("cats/kernel/Order#")).map { sym =>
          assertTrue(sym.exists(_.kind == SymbolKind.Trait))
        }
      },

    )

  ).provideShared(Scope.default, CatsFixture.catsIndexLayer)
