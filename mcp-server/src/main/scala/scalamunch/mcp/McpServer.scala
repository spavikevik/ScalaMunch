package scalamunch.mcp

import scalamunch.store.IndexStore
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.ast.Json.*
import zio.stream.*

/** MCP stdio server — reads JSON-RPC 2.0 messages from stdin, writes to stdout.
 *  Stderr: diagnostic output only (never contaminates the protocol on stdout).
 *
 *  Protocol version: 2024-11-05
 *  Transport: newline-delimited JSON on stdio
 */
object McpServer:

  val ProtocolVersion = "2024-11-05"
  val ServerName      = "scala-munch"
  val ServerVersion   = "0.1.0"

  /** Exposed for testing: handle a single parsed request without stdio. */
  def handleForTest(req: RpcRequest, store: IndexStore): Task[Option[RpcResponse]] =
    handleRequest(req, store).map(_.map(json => json.fromJson[RpcResponse].getOrElse(
      RpcResponse("2.0", Num(0), None, Some(RpcError.internalError("Response parse failed")))
    )))

  def run(store: IndexStore): ZIO[Any, Throwable, Unit] =
    ZStream
      .fromInputStream(java.lang.System.in, chunkSize = 4096)
      .via(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
      .filter(_.trim.nonEmpty)
      .mapZIO(handleLine(_, store))
      .collectSome
      .foreach(writeResponse)

  // ── request dispatch ──────────────────────────────────────────────────────

  private def handleLine(line: String, store: IndexStore): Task[Option[String]] =
    ZIO.logDebug(s"← $line") *> {
      line.fromJson[RpcRequest] match
        case Left(parseErr) =>
          ZIO.some(rpcErr(Num(0), RpcError.parseError(parseErr)).toJsonCompact)
        case Right(req) =>
          handleRequest(req, store)
    }

  private def handleRequest(req: RpcRequest, store: IndexStore): Task[Option[String]] =
    req.method match
      case "initialize"     => handleInitialize(req)
      case "initialized"    => ZIO.none  // notification — no response
      case "ping"           => req.id.fold(ZIO.none)(id => ZIO.some(ok(id, Obj(Chunk.empty)).toJsonCompact))
      case "tools/list"     => handleToolsList(req)
      case "tools/call"     => handleToolsCall(req, store)
      case "resources/list" => handleResourcesList(req)
      case "resources/read" => handleResourcesRead(req, store)
      case _                =>
        req.id.fold(ZIO.none)(id => ZIO.some(rpcErr(id, RpcError.MethodNotFound).toJsonCompact))

  // ── method handlers ───────────────────────────────────────────────────────

  private def handleInitialize(req: RpcRequest): Task[Option[String]] =
    val result = InitializeResult(
      protocolVersion = ProtocolVersion,
      capabilities    = McpCapabilities(
        tools     = Obj(Chunk("listChanged" -> Bool(false))),
        resources = Obj(Chunk("listChanged" -> Bool(false)))
      ),
      serverInfo = McpServerInfo(ServerName, ServerVersion)
    ).asJsonAst
    ZIO.some(ok(req.idJson, result).toJsonCompact)

  private def handleToolsList(req: RpcRequest): Task[Option[String]] =
    req.id.fold(ZIO.none) { id =>
      val toolsJson = Arr(Chunk.fromIterable(ToolDefs.allTools.map(_.asJsonAst)))
      val result    = Obj(Chunk("tools" -> toolsJson))
      ZIO.some(ok(id, result).toJsonCompact)
    }

  private def handleToolsCall(req: RpcRequest, store: IndexStore): Task[Option[String]] =
    req.id.fold(ZIO.none) { id =>
      val params = req.params.getOrElse(Null)
      val name   = params.str("name").getOrElse("")
      val args   = params.obj("arguments").getOrElse(Obj(Chunk.empty))

      if name.isEmpty then
        ZIO.some(rpcErr(id, RpcError.InvalidParams).toJsonCompact)
      else
        ToolHandlers.dispatch(name, args, store)
          .map(result => ok(id, result.asJsonAst).toJsonCompact)
          .map(Some(_))
          .catchAll { ex =>
            ZIO.some(rpcErr(id, RpcError.internalError(
              Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
            )).toJsonCompact)
          }
    }

  private def handleResourcesList(req: RpcRequest): Task[Option[String]] =
    req.id.fold(ZIO.none) { id =>
      val resources = List(
        ResourceDef("scala-index://symbols", "Symbols",    "All indexed Scala symbols", "text/plain"),
        ResourceDef("scala-index://stats",   "Statistics", "Index statistics",           "text/plain")
      )
      val result = Obj(Chunk("resources" -> Arr(Chunk.fromIterable(resources.map(_.asJsonAst)))))
      ZIO.some(ok(id, result).toJsonCompact)
    }

  private def handleResourcesRead(req: RpcRequest, store: IndexStore): Task[Option[String]] =
    req.id.fold(ZIO.none) { id =>
      val uri = req.params.flatMap(_.str("uri")).getOrElse("")
      uri match
        case "scala-index://stats" =>
          store.stats.map { s =>
            val text = s"Symbols: ${s.symbolCount}\nFiles: ${s.fileCount}\n" +
                       s"Implicits: ${s.implicitCount}\nType deps: ${s.typDepCount}\n" +
                       s"Updated: ${s.lastUpdated}"
            val result = ReadResourceResult(List(ResourceContent(uri, "text/plain", text))).asJsonAst
            ok(id, result).toJsonCompact
          }.map(Some(_))

        case "scala-index://symbols" =>
          store.searchSymbols("", 200).map { syms =>
            val text   = syms.map(s => s"${s.fqn}\t${s.signature}").mkString("\n")
            val result = ReadResourceResult(List(ResourceContent(uri, "text/plain", text))).asJsonAst
            ok(id, result).toJsonCompact
          }.map(Some(_))

        case _ =>
          ZIO.some(rpcErr(id, RpcError(-32002, s"Resource not found: $uri")).toJsonCompact)
    }

  // ── I/O ───────────────────────────────────────────────────────────────────

  private def writeResponse(line: String): Task[Unit] =
    ZIO.attempt {
      java.lang.System.out.println(line)
      java.lang.System.out.flush()
    } *> ZIO.logDebug(s"→ $line")
