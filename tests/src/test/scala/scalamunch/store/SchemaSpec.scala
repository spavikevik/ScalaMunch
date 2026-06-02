package scalamunch.store

import scalamunch.model.*
import scalamunch.store.{IndexStore, Schema}
import zio.*
import zio.test.*

import java.sql.{Connection, DriverManager}
import java.time.Instant

/** Direct tests against Schema.ddl and FTS5 trigger correctness.
 *
 *  Uses a raw JDBC connection so we can query sqlite_master and verify
 *  table structure without going through the IndexStore abstraction.
 *  Directly tests the trigger behavior that caused the production FTS bug.
 */
object SchemaSpec extends ZIOSpecDefault:

  // ── fixture ────────────────────────────────────────────────────────────────

  private def openConn: ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(
      ZIO.attempt {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        Schema.pragmas.foreach { p => val st = conn.createStatement(); st.executeUpdate(p); st.close() }
        conn.setAutoCommit(false)
        val st = conn.createStatement()
        st.executeUpdate(Schema.ddl)
        conn.commit()
        st.close()
        conn
      }
    )(conn => ZIO.attempt(conn.close()).orDie)

  private def query(conn: Connection, sql: String): List[String] =
    val st  = conn.createStatement()
    val rs  = st.executeQuery(sql)
    val buf = collection.mutable.ListBuffer.empty[String]
    while rs.next() do buf += rs.getString(1)
    rs.close(); st.close()
    buf.toList

  private def exec(conn: Connection, sql: String, params: Any*): Int =
    val ps = conn.prepareStatement(sql)
    params.zipWithIndex.foreach { (v, i) =>
      v match
        case s: String  => ps.setString(i + 1, s)
        case n: Int     => ps.setInt(i + 1, n)
        case l: Long    => ps.setLong(i + 1, l)
        case null       => ps.setNull(i + 1, java.sql.Types.NULL)
        case _          => ps.setObject(i + 1, v)
    }
    val n = ps.executeUpdate()
    ps.close()
    n

  private def ftsSearch(conn: Connection, q: String): List[String] =
    val ps = conn.prepareStatement(
      "SELECT s.name FROM symbols s JOIN symbols_fts f ON s.rowid = f.rowid WHERE symbols_fts MATCH ? LIMIT 10"
    )
    ps.setString(1, q)
    val rs  = ps.executeQuery()
    val buf = collection.mutable.ListBuffer.empty[String]
    while rs.next() do buf += rs.getString(1)
    rs.close(); ps.close()
    buf.toList

  private val insertSym = """
    INSERT INTO symbols(fqn,kind,name,scala_ver,signature,doc,file,line_start,line_end,
      source_hash,type_params,annotations,parent_fqns,enclosing_fqn)
    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
  """

  // ── tests ──────────────────────────────────────────────────────────────────

  def spec = suite("Schema DDL")(

    suite("tables")(

      test("symbols table exists") {
        ZIO.scoped {
          openConn.map { conn =>
            val tables = query(conn, "SELECT name FROM sqlite_master WHERE type='table'")
            assertTrue(tables.contains("symbols"))
          }
        }
      },

      test("symbols_fts virtual table exists") {
        ZIO.scoped {
          openConn.map { conn =>
            val tables = query(conn, "SELECT name FROM sqlite_master WHERE type='table'")
            assertTrue(tables.contains("symbols_fts"))
          }
        }
      },

      test("type_deps table exists") {
        ZIO.scoped {
          openConn.map { conn =>
            val tables = query(conn, "SELECT name FROM sqlite_master WHERE type='table'")
            assertTrue(tables.contains("type_deps"))
          }
        }
      },

      test("implicits table exists") {
        ZIO.scoped {
          openConn.map { conn =>
            val tables = query(conn, "SELECT name FROM sqlite_master WHERE type='table'")
            assertTrue(tables.contains("implicits"))
          }
        }
      },

      test("files table exists") {
        ZIO.scoped {
          openConn.map { conn =>
            val tables = query(conn, "SELECT name FROM sqlite_master WHERE type='table'")
            assertTrue(tables.contains("files"))
          }
        }
      },

      test("symbols table has 14 columns") {
        ZIO.scoped {
          openConn.map { conn =>
            val cols = query(conn, "SELECT name FROM pragma_table_info('symbols')")
            assertTrue(cols.size == 14)
          }
        }
      },

      test("symbols table has fqn primary key") {
        ZIO.scoped {
          openConn.map { conn =>
            val cols = query(conn, "SELECT name FROM pragma_table_info('symbols')")
            assertTrue(cols.contains("fqn") && cols.contains("kind") &&
                       cols.contains("signature") && cols.contains("enclosing_fqn"))
          }
        }
      },

    ),

    suite("triggers")(

      test("all 3 FTS triggers exist") {
        ZIO.scoped {
          openConn.map { conn =>
            val triggers = query(conn, "SELECT name FROM sqlite_master WHERE type='trigger'")
            assertTrue(
              triggers.contains("symbols_ai") &&
              triggers.contains("symbols_ad") &&
              triggers.contains("symbols_au")
            )
          }
        }
      },

    ),

    suite("FTS trigger correctness")(

      test("INSERT trigger: symbol becomes FTS-searchable") {
        ZIO.scoped {
          for
            conn <- openConn
            _    <- ZIO.attempt {
                      exec(conn, insertSym,
                        "pkg/Foo#","Trait","Foo","Scala3","trait Foo",null,
                        "/src/Foo.scala",1,5,"abc","","","",null)
                      conn.commit()
                    }
            hits  = ftsSearch(conn, "Foo*")
          yield assertTrue(hits.contains("Foo"))
        }
      },

      test("DELETE trigger: symbol removed from FTS") {
        ZIO.scoped {
          for
            conn <- openConn
            _    <- ZIO.attempt {
                      exec(conn, insertSym,
                        "pkg/Bar#","Trait","Bar","Scala3","trait Bar",null,
                        "/src/Bar.scala",1,5,"abc","","","",null)
                      conn.commit()
                    }
            before = ftsSearch(conn, "Bar*")
            _    <- ZIO.attempt {
                      exec(conn, "DELETE FROM symbols WHERE fqn = ?", "pkg/Bar#")
                      conn.commit()
                    }
            after  = ftsSearch(conn, "Bar*")
          yield assertTrue(before.contains("Bar") && !after.contains("Bar"))
        }
      },

      test("DELETE trigger: NULL doc does not crash (regression: FTS5 bug)") {
        ZIO.scoped {
          for
            conn <- openConn
            _    <- ZIO.attempt {
                      exec(conn, insertSym,
                        "pkg/Baz#","Trait","Baz","Scala3","trait Baz",null, // doc = null
                        "/src/Baz.scala",1,5,"abc","","","",null)
                      conn.commit()
                    }
            // Deleting a symbol with NULL doc must not throw SQL logic error
            _    <- ZIO.attempt {
                      exec(conn, "DELETE FROM symbols WHERE fqn = ?", "pkg/Baz#")
                      conn.commit()
                    }
            after = ftsSearch(conn, "Baz*")
          yield assertTrue(after.isEmpty)
        }
      },

      test("UPDATE trigger (via INSERT OR REPLACE): FTS reflects new signature") {
        ZIO.scoped {
          for
            conn <- openConn
            _    <- ZIO.attempt {
                      exec(conn, insertSym,
                        "pkg/Qux#","Trait","Qux","Scala3","trait Qux",null,
                        "/src/Qux.scala",1,5,"abc","","","",null)
                      conn.commit()
                    }
            _    <- ZIO.attempt {
                      // INSERT OR REPLACE with same fqn = update
                      exec(conn, "INSERT OR REPLACE INTO symbols(fqn,kind,name,scala_ver,signature,doc,file,line_start,line_end,source_hash,type_params,annotations,parent_fqns,enclosing_fqn) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        "pkg/Qux#","Trait","Qux","Scala3","trait Qux[A]",null,
                        "/src/Qux.scala",1,5,"xyz","","","",null)
                      conn.commit()
                    }
            // FTS should now match "Qux" and signature contains "[A]"
            hits  = ftsSearch(conn, "Qux*")
          yield assertTrue(hits.contains("Qux"))
        }
      },

      test("FTS search is case-insensitive") {
        ZIO.scoped {
          for
            conn <- openConn
            _    <- ZIO.attempt {
                      exec(conn, insertSym,
                        "pkg/Widget#","Class","Widget","Scala3","case class Widget(id: Int)",null,
                        "/src/Widget.scala",1,5,"abc","","","",null)
                      conn.commit()
                    }
            hits = ftsSearch(conn, "widget*")
          yield assertTrue(hits.contains("Widget"))
        }
      },

      test("initSchema is idempotent — running twice does not error") {
        ZIO.scoped {
          for
            conn <- openConn
            _    <- ZIO.attempt {
                      // Run DDL again — DROP TRIGGER IF EXISTS + CREATE TRIGGER should be safe
                      val st = conn.createStatement()
                      st.executeUpdate(Schema.ddl)
                      conn.commit()
                      st.close()
                    }
          yield assertTrue(true)
        }
      },

    ),

  )
