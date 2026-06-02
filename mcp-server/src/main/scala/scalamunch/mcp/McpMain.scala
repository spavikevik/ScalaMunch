package scalamunch.mcp

import scalamunch.store.IndexStore
import zio.*

import java.nio.file.{Path, Paths}

/** MCP server entry point.
 *
 *  Usage (after assembly):
 *    java -jar scala-munch-mcp.jar [--db <path>]
 *
 *  Stdin:  JSON-RPC 2.0 messages (newline-delimited)
 *  Stdout: JSON-RPC 2.0 responses (newline-delimited)
 *  Stderr: ZIO log output only
 */
object McpMain extends ZIOAppDefault:

  /** Route all ZIO logging to stderr so stdout stays clean for MCP protocol. */
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> Runtime.addLogger(stderrLogger)

  private val stderrLogger: ZLogger[String, Unit] =
    (_, _, level, msg, cause, _, _, _) =>
      val causeStr = if cause.isEmpty then "" else s" | cause=${cause.prettyPrint}"
      java.lang.System.err.println(s"[${level.label}] ${msg()}$causeStr")
      java.lang.System.err.flush()

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args <- ZIOAppArgs.getArgs
      cfg   = parseArgs(args)
      _    <- ZIO.logInfo(s"ScalaMunch MCP server — db: ${cfg.dbPath}")
      _    <- ZIO.scoped {
                IndexStore.open(cfg.dbPath).flatMap { store =>
                  ZIO.logInfo("Index loaded. Listening on stdin.") *>
                  McpServer.run(store)
                }
              }
    yield ()

  private case class Config(dbPath: Path)

  private def parseArgs(args: Chunk[String]): Config =
    var db = Paths.get(".scala-munch.db")
    val it = args.iterator
    while it.hasNext do
      it.next() match
        case "--db" if it.hasNext => db = Paths.get(it.next())
        case _                    => ()
    Config(db)
