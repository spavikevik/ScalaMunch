package scalamunch.store

import scalamunch.model.*
import zio.*

import java.nio.file.Path
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.time.Instant
import zio.Semaphore

/** SQLite-backed index store.
 *  Schema: symbols (FTS5) + type_deps + implicits + files.
 *  Write path serialized via Semaphore(1); reads are lock-free (WAL mode).
 */
trait IndexStore:
  def upsertSymbol(sym: ScalaSymbol): Task[Unit]
  def upsertSymbols(syms: List[ScalaSymbol]): Task[Unit]
  def upsertTypeDep(dep: TypeDep): Task[Unit]
  def upsertTypeDeps(deps: List[TypeDep]): Task[Unit]
  def upsertImplicit(entry: ImplicitEntry): Task[Unit]
  def upsertImplicits(entries: List[ImplicitEntry]): Task[Unit]
  def upsertFile(entry: FileEntry): Task[Unit]
  def getSymbol(fqn: String): Task[Option[ScalaSymbol]]
  def findByName(name: String, limit: Int = 25): Task[List[ScalaSymbol]]
  def searchSymbols(query: String, limit: Int = 20, kind: Option[String] = None): Task[List[ScalaSymbol]]
  def searchByType(typeSig: String, limit: Int = 10): Task[List[ScalaSymbol]]
  def getTypeDeps(fqn: String): Task[List[TypeDep]]
  def getReferences(fqn: String, limit: Int = 50): Task[List[TypeDep]]
  def getImplicitsFor(typeFqn: String): Task[List[ImplicitEntry]]
  def invalidateFile(path: String): Task[Int]
  def getFileEntry(path: String): Task[Option[FileEntry]]
  def listPackages: Task[List[(String, Int)]]
  def getPackageSymbols(pkg: String, limit: Int = 50): Task[List[ScalaSymbol]]
  def getTestSymbols(limit: Int = 300): Task[List[ScalaSymbol]]
  def getProductionTypesFor(pkg: String): Task[List[ScalaSymbol]]
  def stats: Task[IndexStats]
  def close: Task[Unit]

object IndexStore:

  def open(dbPath: Path): ZIO[Scope, Throwable, IndexStore] =
    ZIO.acquireRelease(
      for
        lock <- Semaphore.make(1)
        impl <- ZIO.attempt {
                  Class.forName("org.sqlite.JDBC")
                  val url  = s"jdbc:sqlite:${dbPath.toAbsolutePath}"
                  val conn = DriverManager.getConnection(url)
                  val impl = LiveIndexStore(conn, lock)
                  impl.initSchema()
                  impl
                }
      yield impl
    )(store => store.close.orDie)

  def inMemory: ZIO[Scope, Throwable, IndexStore] =
    ZIO.acquireRelease(
      for
        lock <- Semaphore.make(1)
        impl <- ZIO.attempt {
                  Class.forName("org.sqlite.JDBC")
                  val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
                  val impl = LiveIndexStore(conn, lock)
                  impl.initSchema()
                  impl
                }
      yield impl
    )(store => store.close.orDie)

// ── live implementation ──────────────────────────────────────────────────

private class LiveIndexStore(conn: Connection, writeLock: Semaphore) extends IndexStore:

  def initSchema(): Unit =
    // PRAGMAs (including WAL mode) must run outside any transaction.
    Schema.pragmas.foreach { p =>
      val st = conn.createStatement()
      st.executeUpdate(p)
      st.close()
    }
    conn.setAutoCommit(false)
    val st = conn.createStatement()
    st.executeUpdate(Schema.ddl)
    conn.commit()
    st.close()

  def upsertSymbol(sym: ScalaSymbol): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    val ps = conn.prepareStatement(Sql.upsertSymbol)
    bindSymbol(ps, sym)
    ps.executeUpdate()
    conn.commit()
    ps.close()
  })

  def upsertSymbols(syms: List[ScalaSymbol]): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    val ps = conn.prepareStatement(Sql.upsertSymbol)
    syms.foreach { sym =>
      bindSymbol(ps, sym)
      ps.addBatch()
    }
    ps.executeBatch()
    conn.commit()
    ps.close()
  })

  def upsertTypeDep(dep: TypeDep): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    val ps = conn.prepareStatement(Sql.upsertTypeDep)
    ps.setString(1, dep.fromFqn)
    ps.setString(2, dep.toFqn)
    ps.setString(3, dep.rel.toString)
    ps.executeUpdate()
    conn.commit()
    ps.close()
  })

  def upsertTypeDeps(deps: List[TypeDep]): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    if deps.isEmpty then ()
    else
      val ps = conn.prepareStatement(Sql.upsertTypeDep)
      deps.foreach { dep =>
        ps.setString(1, dep.fromFqn)
        ps.setString(2, dep.toFqn)
        ps.setString(3, dep.rel.toString)
        ps.addBatch()
      }
      ps.executeBatch()
      conn.commit()
      ps.close()
  })

  def upsertImplicits(entries: List[ImplicitEntry]): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    if entries.isEmpty then ()
    else
      val ps = conn.prepareStatement(Sql.upsertImplicit)
      entries.foreach { e =>
        ps.setString(1, e.typeFqn)
        ps.setString(2, e.paramFqn)
        ps.setString(3, e.instanceFqn)
        ps.setString(4, e.scopeFqn)
        ps.addBatch()
      }
      ps.executeBatch()
      conn.commit()
      ps.close()
  })

  def upsertImplicit(entry: ImplicitEntry): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    val ps = conn.prepareStatement(Sql.upsertImplicit)
    ps.setString(1, entry.typeFqn)
    ps.setString(2, entry.paramFqn)
    ps.setString(3, entry.instanceFqn)
    ps.setString(4, entry.scopeFqn)
    ps.executeUpdate()
    conn.commit()
    ps.close()
  })

  def upsertFile(entry: FileEntry): Task[Unit] = writeLock.withPermit(ZIO.attempt {
    val ps = conn.prepareStatement(Sql.upsertFile)
    ps.setString(1, entry.path)
    ps.setString(2, entry.digest)
    ps.setString(3, entry.scalaVersion.toString)
    ps.setString(4, entry.symbolFqns.mkString(","))
    ps.setLong(5, entry.indexedAt.toEpochMilli)
    ps.executeUpdate()
    conn.commit()
    ps.close()
  })

  def getSymbol(fqn: String): Task[Option[ScalaSymbol]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.getByFqn)
    ps.setString(1, fqn)
    val rs  = ps.executeQuery()
    val sym = if rs.next() then Some(rsToSymbol(rs)) else None
    rs.close(); ps.close()
    sym
  }

  def findByName(name: String, limit: Int): Task[List[ScalaSymbol]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.byName)
    ps.setString(1, name)
    ps.setInt(2, limit)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]
    while rs.next() do buf += rsToSymbol(rs)
    rs.close(); ps.close()
    buf.toList
  }

  def searchSymbols(query: String, limit: Int, kind: Option[String]): Task[List[ScalaSymbol]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.ftsSearch)
    // Quote each token and append * for prefix matching (FTS5 tokenizes camelCase as
    // one token). Quoting makes FTS5 operators (* OR ( : ...) literal, so arbitrary
    // user input can't produce an fts5 syntax error. Internal quotes are doubled.
    val ftsQuery = query.trim.split("\\s+").filter(_.nonEmpty)
      .map(w => "\"" + w.replace("\"", "\"\"") + "\"*").mkString(" ")
    ps.setString(1, ftsQuery)
    // kind filter is pushed into SQL so LIMIT applies after filtering, not before.
    ps.setString(2, kind.orNull)
    ps.setString(3, kind.orNull)
    ps.setInt(4, limit)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]
    while rs.next() do buf += rsToSymbol(rs)
    rs.close(); ps.close()
    buf.toList
  }

  def searchByType(typeSig: String, limit: Int): Task[List[ScalaSymbol]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.sigSearch)
    ps.setString(1, s"%$typeSig%")
    ps.setInt(2, limit)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]
    while rs.next() do buf += rsToSymbol(rs)
    rs.close(); ps.close()
    buf.toList
  }

  def getTypeDeps(fqn: String): Task[List[TypeDep]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.typeDepsFor)
    ps.setString(1, fqn)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[TypeDep]
    while rs.next() do
      buf += TypeDep(
        fromFqn = rs.getString("from_fqn"),
        toFqn   = rs.getString("to_fqn"),
        rel     = TypeRel.valueOf(rs.getString("rel"))
      )
    rs.close(); ps.close()
    buf.toList
  }

  def getReferences(fqn: String, limit: Int = 50): Task[List[TypeDep]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.referencesTo)
    ps.setString(1, fqn)
    ps.setInt(2, limit)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[TypeDep]
    while rs.next() do
      buf += TypeDep(
        fromFqn = rs.getString("from_fqn"),
        toFqn   = rs.getString("to_fqn"),
        rel     = TypeRel.valueOf(rs.getString("rel"))
      )
    rs.close(); ps.close()
    buf.toList
  }

  def getImplicitsFor(typeFqn: String): Task[List[ImplicitEntry]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.implicitsFor)
    ps.setString(1, typeFqn)
    ps.setString(2, typeFqn)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ImplicitEntry]
    while rs.next() do
      buf += ImplicitEntry(
        typeFqn     = rs.getString("type_fqn"),
        paramFqn    = rs.getString("param_fqn"),
        instanceFqn = rs.getString("instance_fqn"),
        scopeFqn    = rs.getString("scope_fqn")
      )
    rs.close(); ps.close()
    buf.toList
  }

  def getFileEntry(path: String): Task[Option[FileEntry]] = ZIO.attempt {
    val ps = conn.prepareStatement("SELECT * FROM files WHERE path = ?")
    ps.setString(1, path)
    val rs = ps.executeQuery()
    val entry = if rs.next() then Some(FileEntry(
      path         = rs.getString("path"),
      digest       = rs.getString("digest"),
      scalaVersion = ScalaVersion.valueOf(rs.getString("scala_ver")),
      symbolFqns   = csvList(rs.getString("symbol_fqns")),
      indexedAt    = Instant.ofEpochMilli(rs.getLong("indexed_at"))
    )) else None
    rs.close(); ps.close()
    entry
  }

  def invalidateFile(path: String): Task[Int] = writeLock.withPermit(ZIO.attempt {
    val ps = conn.prepareStatement(Sql.deleteByFile)
    ps.setString(1, path)
    val n = ps.executeUpdate()
    conn.commit()
    ps.close()
    n
  })

  def listPackages: Task[List[(String, Int)]] = ZIO.attempt {
    val ps  = conn.prepareStatement("SELECT fqn FROM symbols WHERE fqn LIKE '%/%'")
    val rs  = ps.executeQuery()
    val buf = collection.mutable.Map.empty[String, Int]
    while rs.next() do
      val fqn  = rs.getString(1)
      val i    = fqn.lastIndexOf('/')
      val pkg  = if i >= 0 then fqn.substring(0, i + 1) else "_root_/"
      buf(pkg) = buf.getOrElse(pkg, 0) + 1
    rs.close(); ps.close()
    buf.toList.sortBy(-_._2)
  }

  def getPackageSymbols(pkg: String, limit: Int = 50): Task[List[ScalaSymbol]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.packageSymbols)
    ps.setString(1, s"$pkg%")
    ps.setInt(2, limit)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]
    while rs.next() do buf += rsToSymbol(rs)
    rs.close(); ps.close()
    buf.toList
  }

  def getTestSymbols(limit: Int = 300): Task[List[ScalaSymbol]] = ZIO.attempt {
    val ps  = conn.prepareStatement(Sql.testSymbols)
    ps.setInt(1, limit)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]
    while rs.next() do buf += rsToSymbol(rs)
    rs.close(); ps.close()
    buf.toList
  }

  def getProductionTypesFor(pkg: String): Task[List[ScalaSymbol]] = ZIO.attempt {
    val ps = conn.prepareStatement(Sql.productionTypes)
    ps.setString(1, s"$pkg%")
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]
    while rs.next() do buf += rsToSymbol(rs)
    rs.close(); ps.close()
    buf.toList
  }

  def stats: Task[IndexStats] = ZIO.attempt {
    def count(table: String): Int =
      val st = conn.createStatement()
      try
        val rs = st.executeQuery(s"SELECT COUNT(*) FROM $table")
        try rs.getInt(1) finally rs.close()
      finally st.close()
    val lastUpdated =
      val st = conn.createStatement()
      try
        val rs = st.executeQuery("SELECT MAX(indexed_at) FROM files")
        try
          if rs.next() then Instant.ofEpochMilli(rs.getLong(1)) else Instant.EPOCH
        finally rs.close()
      finally st.close()
    IndexStats(
      symbolCount   = count("symbols"),
      fileCount     = count("files"),
      implicitCount = count("implicits"),
      typDepCount   = count("type_deps"),
      lastUpdated   = lastUpdated
    )
  }

  def close: Task[Unit] = ZIO.attempt(conn.close())

  // ── binding / mapping ──────────────────────────────────────────────────

  private def bindSymbol(ps: PreparedStatement, sym: ScalaSymbol): Unit =
    ps.setString(1, sym.fqn)
    ps.setString(2, sym.kind.toString)
    ps.setString(3, sym.name)
    ps.setString(4, sym.scalaVersion.toString)
    ps.setString(5, sym.signature)
    ps.setString(6, sym.doc.orNull)
    ps.setString(7, sym.file)
    ps.setInt(8, sym.lineStart)
    ps.setInt(9, sym.lineEnd)
    ps.setString(10, sym.sourceHash)
    ps.setString(11, sym.typeParams.mkString(","))
    ps.setString(12, sym.annotations.mkString(","))
    ps.setString(13, sym.parentFqns.mkString(","))
    ps.setString(14, sym.enclosingFqn.orNull)

  private def rsToSymbol(rs: ResultSet): ScalaSymbol =
    ScalaSymbol(
      fqn          = rs.getString("fqn"),
      kind         = SymbolKind.valueOf(rs.getString("kind")),
      name         = rs.getString("name"),
      scalaVersion = ScalaVersion.valueOf(rs.getString("scala_ver")),
      signature    = rs.getString("signature"),
      doc          = Option(rs.getString("doc")),
      file         = rs.getString("file"),
      lineStart    = rs.getInt("line_start"),
      lineEnd      = rs.getInt("line_end"),
      sourceHash   = rs.getString("source_hash"),
      typeParams   = csvList(rs.getString("type_params")),
      annotations  = csvList(rs.getString("annotations")),
      parentFqns   = csvList(rs.getString("parent_fqns")),
      enclosingFqn = Option(rs.getString("enclosing_fqn"))
    )

  private def csvList(s: String): List[String] =
    if s == null || s.isEmpty then Nil else s.split(",").toList

// ── SQL ──────────────────────────────────────────────────────────────────

private object Sql:
  val upsertSymbol: String = """
    INSERT OR REPLACE INTO symbols
      (fqn, kind, name, scala_ver, signature, doc, file,
       line_start, line_end, source_hash, type_params, annotations, parent_fqns, enclosing_fqn)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
  """

  val upsertTypeDep: String = """
    INSERT OR IGNORE INTO type_deps (from_fqn, to_fqn, rel) VALUES (?,?,?)
  """

  val upsertImplicit: String = """
    INSERT OR REPLACE INTO implicits (type_fqn, param_fqn, instance_fqn, scope_fqn)
    VALUES (?,?,?,?)
  """

  val upsertFile: String = """
    INSERT OR REPLACE INTO files (path, digest, scala_ver, symbol_fqns, indexed_at)
    VALUES (?,?,?,?,?)
  """

  val getByFqn: String = "SELECT * FROM symbols WHERE fqn = ?"

  val ftsSearch: String = """
    SELECT s.* FROM symbols s
    JOIN symbols_fts f ON s.rowid = f.rowid
    WHERE symbols_fts MATCH ?
      AND (? IS NULL OR s.kind = ?)
    ORDER BY rank LIMIT ?
  """

  val byName: String =
    "SELECT * FROM symbols WHERE name = ? ORDER BY kind LIMIT ?"

  val sigSearch: String =
    "SELECT * FROM symbols WHERE signature LIKE ? ORDER BY length(signature) LIMIT ?"

  val typeDepsFor: String =
    "SELECT * FROM type_deps WHERE from_fqn = ?"

  val referencesTo: String =
    "SELECT * FROM type_deps WHERE to_fqn = ? LIMIT ?"

  val implicitsFor: String =
    "SELECT * FROM implicits WHERE type_fqn = ? OR param_fqn = ?"

  val deleteByFile: String =
    "DELETE FROM symbols WHERE file = ?"

  val packageSymbols: String =
    "SELECT * FROM symbols WHERE fqn LIKE ? ORDER BY kind, name LIMIT ?"

  val testSymbols: String = """
    SELECT * FROM symbols
    WHERE file LIKE '%Spec.scala'
       OR file LIKE '%Test.scala'
       OR file LIKE '%Suite.scala'
       OR file LIKE '%Check.scala'
    ORDER BY file, kind, name
    LIMIT ?
  """

  val productionTypes: String = """
    SELECT * FROM symbols
    WHERE fqn LIKE ?
      AND kind IN ('Trait', 'Class', 'Object')
      AND file NOT LIKE '%Spec.scala'
      AND file NOT LIKE '%Test.scala'
      AND file NOT LIKE '%Suite.scala'
      AND file NOT LIKE '%Check.scala'
      AND file NOT LIKE '%/test/%'
      AND file NOT LIKE '%/it/%'
    ORDER BY kind, name
  """
