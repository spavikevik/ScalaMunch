package scalamunch.store

object Schema:
  /** Run these first, in autocommit mode (SQLite WAL change fails inside transactions).
   *  busy_timeout goes first: it must be in effect before journal_mode/DDL run, since
   *  those can themselves contend for the write lock when multiple processes open the
   *  same db concurrently (e.g. several MCP client sessions against one project).
   */
  val pragmas: List[String] = List(
    "PRAGMA busy_timeout = 5000",
    "PRAGMA journal_mode = WAL",
    "PRAGMA foreign_keys = ON"
  )

  val ddl: String = """

    -- Core symbol table
    CREATE TABLE IF NOT EXISTS symbols (
      fqn           TEXT PRIMARY KEY,
      kind          TEXT NOT NULL,
      name          TEXT NOT NULL,
      scala_ver     TEXT NOT NULL,
      signature     TEXT NOT NULL,
      doc           TEXT,
      file          TEXT NOT NULL,
      line_start    INT  NOT NULL,
      line_end      INT  NOT NULL,
      source_hash   TEXT NOT NULL,
      type_params   TEXT NOT NULL DEFAULT '',
      annotations   TEXT NOT NULL DEFAULT '',
      parent_fqns   TEXT NOT NULL DEFAULT '',
      enclosing_fqn TEXT
    );

    -- Full-text search over name + signature + doc (standalone, not external-content)
    CREATE VIRTUAL TABLE IF NOT EXISTS symbols_fts USING fts5(
      fqn UNINDEXED,
      name,
      signature,
      doc
    );

    -- FTS triggers (keep in sync).
    -- DROP first so existing DBs get updated triggers on next open.
    DROP TRIGGER IF EXISTS symbols_ai;
    CREATE TRIGGER symbols_ai AFTER INSERT ON symbols BEGIN
      INSERT INTO symbols_fts(rowid, fqn, name, signature, doc)
        VALUES (new.rowid, new.fqn, new.name, new.signature, COALESCE(new.doc, ''));
    END;
    DROP TRIGGER IF EXISTS symbols_ad;
    CREATE TRIGGER symbols_ad AFTER DELETE ON symbols BEGIN
      DELETE FROM symbols_fts WHERE rowid = old.rowid;
    END;
    DROP TRIGGER IF EXISTS symbols_au;
    CREATE TRIGGER symbols_au AFTER UPDATE ON symbols BEGIN
      DELETE FROM symbols_fts WHERE rowid = old.rowid;
      INSERT INTO symbols_fts(rowid, fqn, name, signature, doc)
        VALUES (new.rowid, new.fqn, new.name, new.signature, COALESCE(new.doc, ''));
    END;

    -- Type dependency graph
    CREATE TABLE IF NOT EXISTS type_deps (
      from_fqn TEXT NOT NULL,
      to_fqn   TEXT NOT NULL,
      rel      TEXT NOT NULL,
      PRIMARY KEY (from_fqn, to_fqn, rel)
    );
    CREATE INDEX IF NOT EXISTS idx_type_deps_from ON type_deps(from_fqn);
    CREATE INDEX IF NOT EXISTS idx_type_deps_to   ON type_deps(to_fqn);

    -- Implicit/given instance index
    CREATE TABLE IF NOT EXISTS implicits (
      type_fqn    TEXT NOT NULL,
      param_fqn   TEXT NOT NULL,
      instance_fqn TEXT NOT NULL,
      scope_fqn   TEXT NOT NULL DEFAULT '',
      PRIMARY KEY (type_fqn, param_fqn, instance_fqn)
    );
    CREATE INDEX IF NOT EXISTS idx_implicits_type  ON implicits(type_fqn);
    CREATE INDEX IF NOT EXISTS idx_implicits_param ON implicits(param_fqn);

    -- Indexed file tracking (for incremental updates)
    CREATE TABLE IF NOT EXISTS files (
      path        TEXT PRIMARY KEY,
      digest      TEXT NOT NULL,
      scala_ver   TEXT NOT NULL,
      symbol_fqns TEXT NOT NULL DEFAULT '',
      indexed_at  INT  NOT NULL
    );
  """
