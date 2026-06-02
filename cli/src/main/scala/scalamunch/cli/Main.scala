package scalamunch.cli

import cats.implicits.*
import com.monovore.decline.*
import scalamunch.model.*
import scalamunch.store.IndexStore
import zio.*
import zio.Runtime.default as runtime

import java.nio.file.Path

// ── Argument typeclass for java.nio.file.Path ────────────────────────────────
given Argument[Path] with
  def read(s: String) =
    cats.data.Validated
      .catchNonFatal(Path.of(s))
      .leftMap(e => cats.data.NonEmptyList.one(e.getMessage.nn))
  def defaultMetavar = "path"

// ── Reusable options ─────────────────────────────────────────────────────────
private val defaultDb = Path.of(".scala-munch.db")

private val dbOpt =
  Opts.option[Path]("db", "Index DB path", short = "d").withDefault(defaultDb)

private val limitOpt =
  Opts.option[Int]("limit", "Max results", short = "l")

// ── Sub-commands ─────────────────────────────────────────────────────────────
val buildCmd: Command[Action] = Command("build", "Index a Scala source tree") {
  (
    Opts.argument[Path]("root"),
    dbOpt,
    Opts.flag("scala2", "Parse as Scala 2 (default: Scala 3)").orFalse,
    Opts.flag("no-semanticdb", "Skip SemanticDB augmentation").orFalse,
    Opts.flag("force", "Force re-index all files").orFalse,
  ).mapN { (root, db, sc2, noSdb, force) =>
    val sv = if sc2 then ScalaVersion.Scala2 else ScalaVersion.Scala3
    Action.Build(Indexer.IndexConfig(root, db, sv, !noSdb, force))
  }
}

val queryCmd: Command[Action] = Command("query", "Search symbols by name") {
  (Opts.argument[String]("query"), dbOpt, limitOpt.withDefault(20))
    .mapN(Action.Query.apply)
}

val searchTypeCmd: Command[Action] = Command("search-type", "Hoogle-style type signature search") {
  (Opts.argument[String]("signature"), dbOpt, limitOpt.withDefault(10))
    .mapN(Action.SearchType.apply)
}

val statsCmd: Command[Action] = Command("stats", "Show index statistics") {
  dbOpt.map(Action.Stats.apply)
}

val watchCmd: Command[Action] = Command("watch", "Watch source tree and incrementally reindex on changes") {
  (
    Opts.argument[Path]("root"),
    dbOpt,
    Opts.flag("scala2", "Parse as Scala 2 (default: Scala 3)").orFalse,
    Opts.flag("no-semanticdb", "Skip SemanticDB augmentation").orFalse,
    Opts.flag("force", "Force rebuild on start").orFalse,
    Opts.option[Long]("debounce", "Debounce window in ms").withDefault(300L),
  ).mapN { (root, db, sc2, noSdb, force, debounce) =>
    val sv = if sc2 then ScalaVersion.Scala2 else ScalaVersion.Scala3
    Action.Watch(Watcher.WatchConfig(Indexer.IndexConfig(root, db, sv, !noSdb, force), debounce))
  }
}

// ── App entry point ───────────────────────────────────────────────────────────
object Main
    extends CommandApp(
      name    = "scala-munch",
      header  = "Type-driven Scala code index",
      main    = Opts.subcommands(buildCmd, queryCmd, searchTypeCmd, statsCmd, watchCmd).map(runAction)
    )

private def runAction(action: Action): Unit =
  Unsafe.unsafe { implicit u =>
    runtime.unsafe
      .run(
        ZIO
          .scoped(dispatch(action))
          .tapError(e => ZIO.logError(e.getMessage.nn))
      )
      .getOrElse(_ => ())
  }

private def dispatch(action: Action): ZIO[Scope, Throwable, Unit] =
  action match
    case Action.Build(cfg) =>
      for
        store  <- IndexStore.open(cfg.dbPath)
        stats  <- Indexer.build(cfg).provide(ZLayer.succeed(store))
        _      <- ZIO.succeed {
                    println(s"Done. ${stats.symbolCount} symbols in ${stats.fileCount} files.")
                    println(s"Updated: ${stats.lastUpdated}")
                  }
      yield ()

    case Action.Query(q, db, limit) =>
      for
        store   <- IndexStore.open(db)
        results <- store.searchSymbols(q, limit)
        _       <- ZIO.succeed(
                     if results.isEmpty then println("No results.")
                     else results.foreach(printSymbol)
                   )
      yield ()

    case Action.SearchType(sig, db, limit) =>
      for
        store   <- IndexStore.open(db)
        results <- store.searchByType(sig, limit)
        _       <- ZIO.succeed(
                     if results.isEmpty then println("No results.")
                     else results.foreach(printSymbol)
                   )
      yield ()

    case Action.Stats(db) =>
      for
        store <- IndexStore.open(db)
        s     <- store.stats
        _     <- ZIO.succeed {
                   println(s"Symbols   : ${s.symbolCount}")
                   println(s"Files     : ${s.fileCount}")
                   println(s"Implicits : ${s.implicitCount}")
                   println(s"Type deps : ${s.typDepCount}")
                   println(s"Updated   : ${s.lastUpdated}")
                 }
      yield ()

    case Action.Watch(cfg) =>
      Watcher.watch(cfg)

private def printSymbol(sym: ScalaSymbol): Unit =
  println(s"[${sym.kind}] ${sym.signature}")
  println(s"  fqn : ${sym.fqn}")
  println(s"  at  : ${sym.file}:${sym.lineStart}")
  sym.doc.foreach(d => println(s"  doc : $d"))
  println()

// ── Action ADT ────────────────────────────────────────────────────────────────
enum Action:
  case Build(cfg: Indexer.IndexConfig)
  case Query(query: String, db: Path, limit: Int)
  case SearchType(sig: String, db: Path, limit: Int)
  case Stats(db: Path)
  case Watch(cfg: Watcher.WatchConfig)
