package scalamunch.cli

import scalamunch.model.ScalaVersion
import scalamunch.store.IndexStore
import zio.*
import zio.stream.*

import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*

/** NIO WatchService-based incremental source watcher.
 *
 *  On any .scala file change, waits for the debounce window (default 300ms),
 *  then runs an incremental index update on all files changed in that window.
 *
 *  With SemanticDB: if sbt has recently compiled (fresh .semanticdb files exist),
 *  the reindex picks up resolved types automatically — no explicit coordination needed.
 */
object Watcher:

  case class WatchConfig(
    indexConfig: Indexer.IndexConfig,
    debounceMs: Long = 300L
  )

  def watch(cfg: WatchConfig): ZIO[Scope, Throwable, Unit] =
    for
      store <- IndexStore.open(cfg.indexConfig.dbPath)
      _     <- initialBuild(cfg.indexConfig, store)
      _     <- ZIO.logInfo(s"Watching ${cfg.indexConfig.sourceRoot} — press Ctrl+C to stop")
      _     <- watchLoop(cfg, store)
    yield ()

  // ── initial build ─────────────────────────────────────────────────────────

  private def initialBuild(cfg: Indexer.IndexConfig, store: IndexStore): Task[Unit] =
    store.stats.flatMap { s =>
      if s.symbolCount > 0 && !cfg.force then
        ZIO.logInfo(s"Index exists (${s.symbolCount} symbols). Skipping initial build. Use --force to rebuild.")
      else
        Indexer.build(cfg).provide(ZLayer.succeed(store)).unit
    }

  // ── watch loop ────────────────────────────────────────────────────────────

  private def watchLoop(cfg: WatchConfig, store: IndexStore): ZIO[Scope, Throwable, Unit] =
    for
      watchSvc <- ZIO.acquireRelease(
                    ZIO.attempt(FileSystems.getDefault.newWatchService())
                  )(ws => ZIO.attempt(ws.close()).orDie)
      _        <- registerDirectories(cfg.indexConfig.sourceRoot, watchSvc)
      queue    <- Queue.unbounded[Path]
      // Producer: poll watch service in a background fiber
      _        <- ZStream
                    .repeatZIO(ZIO.attempt(Option(watchSvc.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS))))
                    .collectSome
                    .flatMap { key =>
                      val paths = key.pollEvents().asScala.collect {
                        case e if e.kind != OVERFLOW =>
                          key.watchable.asInstanceOf[Path]
                            .resolve(e.context.asInstanceOf[Path])
                      }.filter(p => p.toString.endsWith(".scala")).toList
                      key.reset()
                      ZStream.fromIterable(paths)
                    }
                    .mapZIO(queue.offer)
                    .runDrain
                    .forkScoped
      // Consumer: debounce + batch reindex
      _        <- debounceReindex(queue, cfg, store)
    yield ()

  /** Drain the queue with a debounce window, then reindex all accumulated paths. */
  private def debounceReindex(
    queue: Queue[Path],
    cfg: WatchConfig,
    store: IndexStore
  ): ZIO[Any, Throwable, Unit] =
    ZStream.fromQueue(queue)
      .groupedWithin(Int.MaxValue, Duration.fromMillis(cfg.debounceMs))
      .filter(_.nonEmpty)
      .mapZIO { paths =>
        val unique = paths.distinct
        ZIO.logInfo(s"Changed: ${unique.map(_.getFileName).mkString(", ")}") *>
        reindexFiles(unique.toList, cfg.indexConfig, store)
      }
      .runDrain

  private def reindexFiles(
    files: List[Path],
    cfg: Indexer.IndexConfig,
    store: IndexStore
  ): Task[Unit] =
    ZIO.foreach(files.filter(Files.exists(_))) { file =>
      for
        digest   <- ZIO.attempt(fileDigest(file))
        existing <- store.getFileEntry(file.toString)
        changed   = existing.forall(_.digest != digest)
        _        <- if changed then {
                      ZIO.logDebug(s"Reindexing ${file.getFileName}") *>
                      doIndexSingle(file, digest, cfg, store)
                    } else ZIO.unit
      yield ()
    }.unit

  /** Exposed for Watcher.reindexFiles — mirrors Indexer.doIndex but callable here. */
  private def doIndexSingle(
    file: Path,
    digest: String,
    cfg: Indexer.IndexConfig,
    store: IndexStore
  ): Task[Unit] =
    import scalamunch.parser.{ScalametaParser, SemanticDbReader}
    import scalamunch.typegraph.{ImplicitExtractor, TypeDepExtractor}
    import java.time.Instant
    for
      _       <- store.invalidateFile(file.toString)
      symbols <- ScalametaParser.parseFile(file, cfg.scalaVersion) match
                   case Left(err)   => ZIO.logWarning(s"Skip ${file.getFileName}: $err") *> ZIO.succeed(Nil)
                   case Right(syms) => ZIO.succeed(syms)
      semSyms  = if !cfg.useSemanticDb then Nil
                 else {
                   val ver     = if cfg.scalaVersion == ScalaVersion.Scala3 then "3" else "2.13"
                   val sdbPath = SemanticDbReader.semanticDbPath(cfg.sourceRoot, file, ver)
                   SemanticDbReader.readFile(sdbPath).getOrElse(Nil)
                 }
      merged   = if semSyms.isEmpty then symbols
                 else
                   val sdbByFqn = semSyms.map(s => s.fqn -> s).toMap
                   symbols.map(sym => sdbByFqn.get(sym.fqn).fold(sym)(s => sym.copy(doc = sym.doc.orElse(s.docString))))
      _       <- store.upsertSymbols(merged)
      _       <- if cfg.useSemanticDb then
                   val ver = if cfg.scalaVersion == ScalaVersion.Scala3 then "3" else "2.13"
                   val sdbPath = SemanticDbReader.semanticDbPath(cfg.sourceRoot, file, ver)
                   SemanticDbReader.readDocs(sdbPath) match
                     case Left(_)     => ZIO.unit
                     case Right(docs) =>
                       store.upsertTypeDeps(TypeDepExtractor.extract(docs)) *>
                       store.upsertImplicits(ImplicitExtractor.extract(docs))
                 else ZIO.unit
      _       <- store.upsertFile(scalamunch.model.FileEntry(
                   path         = file.toString,
                   digest       = digest,
                   scalaVersion = cfg.scalaVersion,
                   symbolFqns   = merged.map(_.fqn),
                   indexedAt    = Instant.now()
                 ))
    yield ()

  // ── helpers ───────────────────────────────────────────────────────────────

  private def registerDirectories(root: Path, ws: WatchService): Task[Unit] =
    ZIO.attempt {
      Files.walkFileTree(root, new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          val s = dir.toString
          if s.contains("/target/") || s.contains("/.bsp/") || s.contains("/.metals/") then
            FileVisitResult.SKIP_SUBTREE
          else
            dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            FileVisitResult.CONTINUE
      )
    }

  // Import fileDigest from Indexer (it's private there; replicate here)
  private def fileDigest(file: Path): String =
    val d = java.security.MessageDigest.getInstance("SHA-256")
    d.digest(Files.readAllBytes(file)).map("%02x".format(_)).mkString
