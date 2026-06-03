package scalamunch.store

import scalamunch.model.*
import scalamunch.store.IndexStore
import zio.*
import zio.test.*

import java.time.Instant
import java.nio.file.Paths

/** Tests that each Sql string is correct: right param count, correct filtering,
 *  and observable semantics. Tests run through the public IndexStore API so
 *  SQL bugs surface as assertion failures rather than compile errors.
 */
object SqlSpec extends ZIOSpecDefault:

  private def q[A](f: IndexStore => Task[A]) = ZIO.serviceWithZIO[IndexStore](f)

  // ── helpers ────────────────────────────────────────────────────────────────

  private def sym(
    fqn:  String,
    kind: SymbolKind  = SymbolKind.Trait,
    name: String      = "Foo",
    sig:  String      = "trait Foo",
    doc:  Option[String] = None,
    file: String      = "/src/main/scala/Foo.scala"
  ): ScalaSymbol = ScalaSymbol(
    fqn          = fqn,
    kind         = kind,
    name         = name,
    scalaVersion = ScalaVersion.Scala3,
    signature    = sig,
    doc          = doc,
    file         = file,
    lineStart    = 1,
    lineEnd      = 5,
    sourceHash   = "abc123",
    typeParams   = Nil,
    annotations  = Nil,
    parentFqns   = Nil,
    enclosingFqn = None
  )

  private def layer = ZLayer.scoped(IndexStore.inMemory)

  // ── tests ──────────────────────────────────────────────────────────────────

  def spec = suite("Sql strings")(

    suite("upsertSymbol / getByFqn")(

      test("upsertSymbol inserts and getByFqn retrieves") {
        val s = sym("pkg/Alpha#", name = "Alpha", sig = "trait Alpha")
        for
          _   <- q(_.upsertSymbol(s))
          got <- q(_.getSymbol("pkg/Alpha#"))
        yield assertTrue(got.exists(_.name == "Alpha"))
      }.provide(layer),

      test("upsertSymbol with doc = None does not crash") {
        val s = sym("pkg/Beta#", name = "Beta", doc = None)
        for
          _   <- q(_.upsertSymbol(s))
          got <- q(_.getSymbol("pkg/Beta#"))
        yield assertTrue(got.isDefined)
      }.provide(layer),

      test("upsertSymbol with doc = Some stores doc") {
        val s = sym("pkg/Gamma#", name = "Gamma", doc = Some("A useful trait"))
        for
          _   <- q(_.upsertSymbol(s))
          got <- q(_.getSymbol("pkg/Gamma#"))
        yield assertTrue(got.flatMap(_.doc).contains("A useful trait"))
      }.provide(layer),

      test("INSERT OR REPLACE updates existing symbol") {
        val v1 = sym("pkg/Mut#", name = "Mut", sig = "trait Mut")
        val v2 = sym("pkg/Mut#", name = "Mut", sig = "trait Mut[A]")
        for
          _ <- q(_.upsertSymbol(v1))
          _ <- q(_.upsertSymbol(v2))
          g <- q(_.getSymbol("pkg/Mut#"))
        yield assertTrue(g.exists(_.signature == "trait Mut[A]"))
      }.provide(layer),

      test("unknown FQN returns None") {
        q(_.getSymbol("no/Such#")).map(r => assertTrue(r.isEmpty))
      }.provide(layer),

    ),

    suite("upsertSymbols batch")(

      test("all symbols in batch are retrievable") {
        val batch = List(
          sym("pkg/A#", name = "A"),
          sym("pkg/B#", name = "B"),
          sym("pkg/C#", name = "C"),
        )
        for
          _  <- q(_.upsertSymbols(batch))
          a  <- q(_.getSymbol("pkg/A#"))
          b  <- q(_.getSymbol("pkg/B#"))
          c  <- q(_.getSymbol("pkg/C#"))
        yield assertTrue(a.isDefined && b.isDefined && c.isDefined)
      }.provide(layer),

      test("empty batch is a no-op") {
        q(_.upsertSymbols(Nil)).map(_ => assertTrue(true))
      }.provide(layer),

    ),

    suite("ftsSearch")(

      test("FTS finds symbol by name prefix") {
        val s = sym("pkg/Funky#", name = "FunkyTrait", sig = "trait FunkyTrait")
        for
          _ <- q(_.upsertSymbol(s))
          r <- q(_.searchSymbols("FunkyTrait", 5))
        yield assertTrue(r.exists(_.fqn == "pkg/Funky#"))
      }.provide(layer),

      test("FTS prefix wildcard: 'Funky' matches 'FunkyTrait'") {
        val s = sym("pkg/FunkyB#", name = "FunkyBaz", sig = "trait FunkyBaz")
        for
          _ <- q(_.upsertSymbol(s))
          r <- q(_.searchSymbols("Funky", 5))
        yield assertTrue(r.exists(_.name == "FunkyBaz"))
      }.provide(layer),

      test("FTS respects limit") {
        val batch = (1 to 10).map(i => sym(s"pkg/X$i#", name = s"Xray$i")).toList
        for
          _ <- q(_.upsertSymbols(batch))
          r <- q(_.searchSymbols("Xray", 3))
        yield assertTrue(r.size <= 3)
      }.provide(layer),

      test("FTS after delete: deleted symbol not returned") {
        val s = sym("pkg/Deleted#", name = "Deleted", file = "/src/Deleted.scala")
        for
          _ <- q(_.upsertSymbol(s))
          _ <- q(_.invalidateFile("/src/Deleted.scala"))
          r <- q(_.searchSymbols("Deleted", 5))
        yield assertTrue(!r.exists(_.fqn == "pkg/Deleted#"))
      }.provide(layer),

    ),

    suite("sigSearch")(

      test("searchByType finds matching signature") {
        val s = sym("pkg/Enc#", name = "Encoder", sig = "trait Encoder[A] { def encode(a: A): String }")
        for
          _ <- q(_.upsertSymbol(s))
          r <- q(_.searchByType("String", 5))
        yield assertTrue(r.exists(_.fqn == "pkg/Enc#"))
      }.provide(layer),

      test("searchByType LIKE is case-sensitive via %pattern%") {
        val s = sym("pkg/IntE#", name = "IntEncoder", sig = "def encodeInt(n: Int): String")
        for
          _ <- q(_.upsertSymbol(s))
          r <- q(_.searchByType("Int", 5))
        yield assertTrue(r.exists(_.fqn == "pkg/IntE#"))
      }.provide(layer),

    ),

    suite("deleteByFile")(

      test("invalidateFile removes all symbols for that file") {
        val batch = List(
          sym("pkg/F1#", name = "F1", file = "/src/Target.scala"),
          sym("pkg/F2#", name = "F2", file = "/src/Target.scala"),
          sym("pkg/Other#", name = "Other", file = "/src/Other.scala"),
        )
        for
          _   <- q(_.upsertSymbols(batch))
          n   <- q(_.invalidateFile("/src/Target.scala"))
          f1  <- q(_.getSymbol("pkg/F1#"))
          f2  <- q(_.getSymbol("pkg/F2#"))
          oth <- q(_.getSymbol("pkg/Other#"))
        yield assertTrue(n == 2 && f1.isEmpty && f2.isEmpty && oth.isDefined)
      }.provide(layer),

      test("invalidateFile on unknown path returns 0") {
        q(_.invalidateFile("/no/such/file.scala")).map(n => assertTrue(n == 0))
      }.provide(layer),

    ),

    suite("packageSymbols")(

      test("getPackageSymbols filters by FQN prefix") {
        val batch = List(
          sym("cats/Functor#",  name = "Functor",  file = "/src/Functor.scala"),
          sym("cats/Show#",     name = "Show",     file = "/src/Show.scala"),
          sym("scalaz/Monad#",  name = "Monad",    file = "/src/Monad.scala"),
        )
        for
          _    <- q(_.upsertSymbols(batch))
          cats <- q(_.getPackageSymbols("cats/", 10))
        yield assertTrue(
          cats.size == 2 &&
          cats.exists(_.fqn == "cats/Functor#") &&
          cats.exists(_.fqn == "cats/Show#") &&
          !cats.exists(_.fqn == "scalaz/Monad#")
        )
      }.provide(layer),

      test("getPackageSymbols respects limit") {
        val batch = (1 to 10).map(i => sym(s"big/T$i#", name = s"Type$i")).toList
        for
          _  <- q(_.upsertSymbols(batch))
          r  <- q(_.getPackageSymbols("big/", 3))
        yield assertTrue(r.size <= 3)
      }.provide(layer),

    ),

    suite("testSymbols")(

      test("getTestSymbols returns only spec file symbols") {
        val batch = List(
          sym("t/Spec#",  name = "MySpec",  file = "/tests/MySpec.scala"),
          sym("t/Prod#",  name = "Prod",    file = "/src/Prod.scala"),
          sym("t/Suite#", name = "MySuite", file = "/tests/MySuite.scala"),
        )
        for
          _    <- q(_.upsertSymbols(batch))
          test <- q(_.getTestSymbols(10))
        yield assertTrue(
          test.size == 2 &&
          test.exists(_.name == "MySpec") &&
          test.exists(_.name == "MySuite") &&
          !test.exists(_.name == "Prod")
        )
      }.provide(layer),

      test("getTestSymbols respects limit") {
        val batch = (1 to 5).map(i => sym(s"t/S$i#", name = s"Suite$i", file = s"/tests/Suite$i.scala")).toList ++
                    List(sym("t/P#", name = "Prod", file = "/src/Prod.scala"))
        for
          _  <- q(_.upsertSymbols(batch))
          r  <- q(_.getTestSymbols(2))
        yield assertTrue(r.size <= 2)
      }.provide(layer),

    ),

    suite("productionTypes")(

      test("getProductionTypesFor excludes test file symbols") {
        val batch = List(
          sym("p/Foo#",     name = "Foo",     kind = SymbolKind.Trait,  file = "/src/Foo.scala"),
          sym("p/FooSpec#", name = "FooSpec", kind = SymbolKind.Object, file = "/tests/FooSpec.scala"),
        )
        for
          _    <- q(_.upsertSymbols(batch))
          prod <- q(_.getProductionTypesFor("p/"))
        yield assertTrue(
          prod.exists(_.fqn == "p/Foo#") &&
          !prod.exists(_.fqn == "p/FooSpec#")
        )
      }.provide(layer),

      test("getProductionTypesFor returns only Trait/Class/Object") {
        val batch = List(
          sym("q/T#",  name = "T",  kind = SymbolKind.Trait,  file = "/src/T.scala"),
          sym("q/M#",  name = "m",  kind = SymbolKind.Def,    file = "/src/T.scala"),
          sym("q/V#",  name = "v",  kind = SymbolKind.Val,    file = "/src/T.scala"),
        )
        for
          _    <- q(_.upsertSymbols(batch))
          prod <- q(_.getProductionTypesFor("q/"))
        yield assertTrue(
          prod.size == 1 && prod.head.kind == SymbolKind.Trait
        )
      }.provide(layer),

    ),

    suite("type deps and implicits")(

      test("getTypeDeps returns empty for unknown FQN") {
        q(_.getTypeDeps("no/Such#")).map(r => assertTrue(r.isEmpty))
      }.provide(layer),

      test("getImplicitsFor returns empty for unknown type") {
        q(_.getImplicitsFor("no/Such#")).map(r => assertTrue(r.isEmpty))
      }.provide(layer),

      test("upsertTypeDeps and getTypeDeps round-trip") {
        val dep = TypeDep("from/Foo#", "to/Bar#", TypeRel.Extends)
        for
          _ <- q(_.upsertTypeDeps(List(dep)))
          r <- q(_.getTypeDeps("from/Foo#"))
        yield assertTrue(r.exists(d => d.fromFqn == "from/Foo#" && d.toFqn == "to/Bar#"))
      }.provide(layer),

      test("getReferences returns reverse edges (callers of the symbol)") {
        val deps = List(
          TypeDep("pkg/Child1#", "pkg/Base#", TypeRel.Extends),
          TypeDep("pkg/Child2#", "pkg/Base#", TypeRel.Extends),
          TypeDep("pkg/Child1#", "pkg/Other#", TypeRel.Extends),
        )
        for
          _ <- q(_.upsertTypeDeps(deps))
          r <- q(_.getReferences("pkg/Base#", 50))
        yield assertTrue(
          r.size == 2 &&
          r.exists(_.fromFqn == "pkg/Child1#") &&
          r.exists(_.fromFqn == "pkg/Child2#") &&
          r.forall(_.toFqn == "pkg/Base#")
        )
      }.provide(layer),

      test("getReferences returns empty for unreferenced symbol") {
        q(_.getReferences("no/Such#", 50)).map(r => assertTrue(r.isEmpty))
      }.provide(layer),

      test("getReferences does not return outgoing deps of the same symbol") {
        val dep = TypeDep("from/Foo#", "to/Bar#", TypeRel.Extends)
        for
          _ <- q(_.upsertTypeDeps(List(dep)))
          r <- q(_.getReferences("from/Foo#", 50))
        yield assertTrue(r.isEmpty)
      }.provide(layer),

      test("getReferences respects limit") {
        val deps = (1 to 10).map(i => TypeDep(s"pkg/C$i#", "pkg/Root#", TypeRel.Extends)).toList
        for
          _ <- q(_.upsertTypeDeps(deps))
          r <- q(_.getReferences("pkg/Root#", 3))
        yield assertTrue(r.size <= 3)
      }.provide(layer),

    ),

  )
