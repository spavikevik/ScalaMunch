package scalamunch.cli

import scalamunch.model.*
import scalamunch.parser.{ScalametaParser, SemanticDbReader}
import scalamunch.store.IndexStore
import scalamunch.typegraph.{ImplicitExtractor, TypeDepExtractor}
import zio.*
import zio.stream.*

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** Walks a source root, parses every .scala file, and writes to IndexStore.
 *  Optionally augments with SemanticDB if target/ meta files are found.
 */
object Indexer:

  case class IndexConfig(
    sourceRoot: Path,
    dbPath: Path,
    scalaVersion: ScalaVersion,
    useSemanticDb: Boolean,
    force: Boolean
  )

  def build(cfg: IndexConfig): ZIO[IndexStore, Throwable, IndexStats] =
    for
      _       <- ZIO.logInfo(s"Indexing ${cfg.sourceRoot}")
      files   <- ZIO.attempt(findScalaFiles(cfg.sourceRoot))
      _       <- ZIO.logInfo(s"Found ${files.size} Scala files")
      store   <- ZIO.service[IndexStore]
      results <- ZStream
                   .fromIterable(files)
                   .mapZIO(indexFile(_, cfg, store))
                   .runCollect
      ok       = results.count(_ == true)
      skipped  = results.count(_ == false)
      _       <- ZIO.logInfo(s"Indexed $ok files, $skipped skipped (unchanged)")
      stats   <- store.stats
    yield stats

  private def indexFile(
    file: Path,
    cfg: IndexConfig,
    store: IndexStore
  ): Task[Boolean] =
    for
      digest   <- ZIO.attempt(fileDigest(file))
      existing <- store.getFileEntry(file.toString)
      upToDate  = !cfg.force && existing.exists(_.digest == digest)
      indexed  <- if upToDate then ZIO.succeed(false)
                  else doIndex(file, digest, cfg, store).as(true)
    yield indexed

  private def doIndex(
    file: Path,
    digest: String,
    cfg: IndexConfig,
    store: IndexStore
  ): Task[Unit] =
    for
      _       <- store.invalidateFile(file.toString)
      symbols <- ScalametaParser.parseFile(file, cfg.scalaVersion) match
                   case Left(err)   =>
                     ZIO.logWarning(s"Skip ${file.getFileName}: $err") *> ZIO.succeed(Nil)
                   case Right(syms) => ZIO.succeed(syms)
      // Augment with SemanticDB if available
      semSyms  = if cfg.useSemanticDb then loadSemanticDb(file, cfg) else Nil
      merged   = merge(symbols, semSyms)
      _       <- store.upsertSymbols(merged)
      // Phase 3: extract and persist type deps + implicit index from SemanticDB docs
      _       <- if cfg.useSemanticDb then runPhase3(file, cfg, store) else ZIO.unit
      _       <- store.upsertFile(FileEntry(
                   path         = file.toString,
                   digest       = digest,
                   scalaVersion = cfg.scalaVersion,
                   symbolFqns   = merged.map(_.fqn),
                   indexedAt    = Instant.now()
                 ))
      _       <- ZIO.logDebug(s"  ${file.getFileName}: ${merged.size} symbols")
    yield ()

  /** Merge Scalameta symbols with SemanticDB-resolved signatures.
   *  SemanticDB doc + resolved type augments but does not override Scalameta sig.
   */
  private def merge(
    sm: List[ScalaSymbol],
    sdb: List[scalamunch.parser.SemanticSymbol]
  ): List[ScalaSymbol] =
    if sdb.isEmpty then sm
    else
      val sdbByFqn = sdb.map(s => s.fqn -> s).toMap
      sm.map { sym =>
        sdbByFqn.get(sym.fqn) match
          case None    => sym
          case Some(s) =>
            sym.copy(
              doc = sym.doc.orElse(s.docString)
            )
      }

  private def runPhase3(file: Path, cfg: IndexConfig, store: IndexStore): Task[Unit] =
    val scalaVerStr = if cfg.scalaVersion == ScalaVersion.Scala3 then "3" else "2.13"
    val sdbPath     = SemanticDbReader.semanticDbPath(cfg.sourceRoot, file, scalaVerStr)
    SemanticDbReader.readDocs(sdbPath) match
      case Left(_)     => ZIO.unit  // no SemanticDB available
      case Right(docs) =>
        val typeDeps  = TypeDepExtractor.extract(docs)
        val implicits = ImplicitExtractor.extract(docs)
        store.upsertTypeDeps(typeDeps) *> store.upsertImplicits(implicits)

  private def loadSemanticDb(
    file: Path,
    cfg: IndexConfig
  ): List[scalamunch.parser.SemanticSymbol] =
    val scalaVerStr = if cfg.scalaVersion == ScalaVersion.Scala3 then "3" else "2.13"
    val sdbPath = SemanticDbReader.semanticDbPath(cfg.sourceRoot, file, scalaVerStr)
    SemanticDbReader.readFile(sdbPath).getOrElse(Nil)

  def findScalaFiles(root: Path): List[Path] =
    import java.nio.file.FileVisitOption
    Files.walk(root, FileVisitOption.FOLLOW_LINKS)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".scala") && !isGenerated(p))
      .toList

  private def isGenerated(p: Path): Boolean =
    val s = p.toString
    s.contains("/target/") || s.contains("/.bsp/")

  private def fileDigest(file: Path): String =
    val bytes = Files.readAllBytes(file)
    val d     = MessageDigest.getInstance("SHA-256")
    d.digest(bytes).map("%02x".format(_)).mkString
