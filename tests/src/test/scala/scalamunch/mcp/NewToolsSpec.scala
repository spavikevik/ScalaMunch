package scalamunch.mcp

import scalamunch.fixtures.CatsFixture
import scalamunch.model.SymbolKind
import scalamunch.store.IndexStore
import zio.*
import zio.test.*

/** Tests for list_packages and get_package_overview against real cats-core index. */
object NewToolsSpec extends ZIOSpecDefault:

  private def q[A](f: IndexStore => Task[A]) = ZIO.serviceWithZIO[IndexStore](f)

  def spec = suite("new MCP tools")(

    suite("list_packages")(

      test("returns cats/ package") {
        q(_.listPackages).map(pkgs => assertTrue(pkgs.exists(_._1 == "cats/")))
      },

      test("returns cats/kernel/ package") {
        q(_.listPackages).map(pkgs => assertTrue(pkgs.exists(_._1 == "cats/kernel/")))
      },

      test("sorted descending by count") {
        q(_.listPackages).map { pkgs =>
          val counts = pkgs.map(_._2)
          assertTrue(counts == counts.sorted.reverse)
        }
      },

      test("cats/ has more symbols than cats/kernel/") {
        q(_.listPackages).map { pkgs =>
          val cats   = pkgs.find(_._1 == "cats/").map(_._2).getOrElse(0)
          val kernel = pkgs.find(_._1 == "cats/kernel/").map(_._2).getOrElse(0)
          assertTrue(cats > kernel && kernel > 0)
        }
      },

      test("all package names end with /") {
        q(_.listPackages).map { pkgs =>
          assertTrue(pkgs.forall(_._1.endsWith("/")))
        }
      },

      test("all counts are positive") {
        q(_.listPackages).map { pkgs =>
          assertTrue(pkgs.forall(_._2 > 0))
        }
      },

      test("no duplicate packages") {
        q(_.listPackages).map { pkgs =>
          val names = pkgs.map(_._1)
          assertTrue(names.distinct.size == names.size)
        }
      },

    ),

    suite("get_package_symbols")(

      test("cats/ contains Functor") {
        q(_.getPackageSymbols("cats/", 5000)).map { syms =>
          assertTrue(syms.exists(_.name == "Functor"))
        }
      },

      test("cats/ contains Show") {
        q(_.getPackageSymbols("cats/", 5000)).map { syms =>
          assertTrue(syms.exists(_.name == "Show"))
        }
      },

      test("cats/ contains both Trait and Object kinds") {
        q(_.getPackageSymbols("cats/", 5000)).map { syms =>
          val kinds = syms.map(_.kind).toSet
          assertTrue(kinds.contains(SymbolKind.Trait) && kinds.contains(SymbolKind.Object))
        }
      },

      test("cats/kernel/ symbols all have fqn starting with cats/kernel/") {
        q(_.getPackageSymbols("cats/kernel/", 5000)).map { syms =>
          assertTrue(syms.nonEmpty && syms.forall(_.fqn.startsWith("cats/kernel/")))
        }
      },

      test("cats/ prefix match includes cats/kernel/ symbols") {
        for
          kernelSyms <- q(_.getPackageSymbols("cats/kernel/", 5000))
          allCats    <- q(_.getPackageSymbols("cats/", 10000))
          kernelFqns  = kernelSyms.map(_.fqn).toSet
          allFqns     = allCats.map(_.fqn).toSet
        yield assertTrue(kernelFqns.subsetOf(allFqns))
      },

      test("nonexistent package returns empty list") {
        q(_.getPackageSymbols("nonexistent/pkg/", 50)).map { syms =>
          assertTrue(syms.isEmpty)
        }
      },

      test("limit is respected") {
        q(_.getPackageSymbols("cats/", 5)).map { syms =>
          assertTrue(syms.size <= 5)
        }
      },

      test("results ordered by kind then name") {
        q(_.getPackageSymbols("cats/", 100)).map { syms =>
          val pairs = syms.map(s => (s.kind.toString, s.name))
          assertTrue(pairs == pairs.sorted)
        }
      },

    )

  ).provideShared(Scope.default, CatsFixture.catsIndexLayer)
