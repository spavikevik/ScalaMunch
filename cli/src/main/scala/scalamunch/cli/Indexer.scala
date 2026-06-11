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
      // Scan once for .semanticdb files. This is layout-agnostic: it handles the
      // Scala 2 (target/scala-2.13/meta/...) and Scala 3
      // (target/scala-3.x.y/classes/META-INF/semanticdb/...) output dirs without
      // guessing version strings or subdir names.
      sdbMap  <- ZIO.attempt {
                   if cfg.useSemanticDb then
                     SemanticDbReader.buildSemanticDbMapping(metaSearchRoot(cfg.sourceRoot))
                   else Map.empty[String, Path]
                 }
      _       <- ZIO.when(cfg.useSemanticDb)(
                   ZIO.logInfo(s"Found ${sdbMap.size} .semanticdb files"))
      store   <- ZIO.service[IndexStore]
      results <- ZStream
                   .fromIterable(files)
                   .mapZIO(indexFile(_, cfg, sdbMap, store))
                   .runCollect
      ok       = results.count(_ == true)
      skipped  = results.count(_ == false)
      _       <- ZIO.logInfo(s"Indexed $ok files, $skipped skipped (unchanged)")
      stats   <- store.stats
    yield stats

  private def indexFile(
    file: Path,
    cfg: IndexConfig,
    sdbMap: Map[String, Path],
    store: IndexStore
  ): Task[Boolean] =
    for
      digest   <- ZIO.attempt(fileDigest(file))
      existing <- store.getFileEntry(file.toString)
      upToDate  = !cfg.force && existing.exists(_.digest == digest)
      indexed  <- if upToDate then ZIO.succeed(false)
                  else doIndex(file, digest, cfg, sdbMap, store).as(true)
    yield indexed

  private def doIndex(
    file: Path,
    digest: String,
    cfg: IndexConfig,
    sdbMap: Map[String, Path],
    store: IndexStore
  ): Task[Unit] =
    val sdbPath = if cfg.useSemanticDb then lookupSemanticDb(file, sdbMap) else None
    for
      _       <- store.invalidateFile(file.toString)
      symbols <- ScalametaParser.parseFile(file, cfg.scalaVersion) match
                   case Left(err)   =>
                     ZIO.logWarning(s"Skip ${file.getFileName}: $err") *> ZIO.succeed(Nil)
                   case Right(syms) => ZIO.succeed(syms)
      // Augment with SemanticDB if available
      semSyms  = sdbPath.flatMap(p => SemanticDbReader.readFile(p).toOption).getOrElse(Nil)
      merged   = merge(symbols, semSyms)
      _       <- store.upsertSymbols(merged)
      // Phase 3: extract and persist type deps + implicit index from SemanticDB docs
      _       <- sdbPath match
                   case Some(p) => runPhase3(p, store)
                   case None    => ZIO.unit
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

  private def runPhase3(sdbPath: Path, store: IndexStore): Task[Unit] =
    SemanticDbReader.readDocs(sdbPath) match
      case Left(_)     => ZIO.unit  // no SemanticDB available
      case Right(docs) =>
        val typeDeps  = TypeDepExtractor.extract(docs)
        val implicits = ImplicitExtractor.extract(docs)
        store.upsertTypeDeps(typeDeps) *> store.upsertImplicits(implicits)

  /** Resolve the .semanticdb for a single source file. Rebuilds the suffix
   *  mapping on each call — intended for the low-frequency watch path. */
  def resolveSemanticDb(sourceRoot: Path, file: Path): Option[Path] =
    lookupSemanticDb(file, SemanticDbReader.buildSemanticDbMapping(metaSearchRoot(sourceRoot)))

  /** Match a source file to its .semanticdb via the suffix mapping. The map is
   *  keyed by source path suffix (e.g. "store/src/main/scala/.../Foo.scala");
   *  an absolute source path ends with that suffix. */
  private def lookupSemanticDb(file: Path, sdbMap: Map[String, Path]): Option[Path] =
    val fp = file.toString.replace('\\', '/')
    sdbMap.collectFirst { case (suffix, p) if fp.endsWith(suffix) => p }

  /** Walk up from the source root to the project root (nearest ancestor with a
   *  build.sbt) so the .semanticdb scan reaches sibling target/ dirs even when
   *  indexing a single module's src tree. Falls back to the source root. */
  private def metaSearchRoot(sourceRoot: Path): Path =
    var cur = sourceRoot.toAbsolutePath.normalize
    while cur != null && !Files.exists(cur.resolve("build.sbt")) do cur = cur.getParent
    if cur == null then sourceRoot.toAbsolutePath.normalize else cur

  def findScalaFiles(root: Path): List[Path] =
    // No FOLLOW_LINKS: symlink cycles would recurse infinitely with no depth bound.
    Files.walk(root)
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
