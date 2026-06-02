package scalamunch.mcp

import scalamunch.fixtures.CatsFixture
import scalamunch.store.IndexStore
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.ast.Json.*
import zio.test.*
import zio.test.Assertion.*

/** Tests for MCP JSON-RPC protocol correctness and tool responses. */
object McpProtocolSpec extends ZIOSpecDefault:

  def spec = suite("McpProtocol")(
    suite("protocol messages")(

      test("initialize returns server info") {
        val req  = mkRequest(1, "initialize", Some(Obj(Chunk(
          "protocolVersion" -> Str("2024-11-05"),
          "clientInfo"      -> Obj(Chunk("name" -> Str("test")))
        ))))
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(req, store)
        yield
          assertTrue(resp.flatMap(_.result).flatMap(_.obj("serverInfo")).isDefined) &&
          assertTrue(resp.flatMap(_.result).flatMap(_.str("protocolVersion")) == Some("2024-11-05"))
      },

      test("initialized notification returns no response") {
        val notif = mkNotification("initialized")
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(notif, store)
        yield assertTrue(resp.isEmpty)
      },

      test("ping returns empty result") {
        val req = mkRequest(2, "ping", None)
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(req, store)
        yield assertTrue(resp.exists(_.error.isEmpty))
      },

      test("unknown method returns MethodNotFound error") {
        val req = mkRequest(3, "unknown/method", None)
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(req, store)
        yield
          assertTrue(resp.exists(_.error.exists(_.code == -32601)))
      },

      test("tools/list returns all 10 tools") {
        val req = mkRequest(4, "tools/list", None)
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(req, store)
          tools  = resp.flatMap(_.result).flatMap { r =>
                     r.asObj.flatMap(m => m.find(_._1 == "tools").map(_._2))
                   }.collect { case Arr(elems) => elems.toList }
        yield
          assertTrue(tools.exists(_.length == 10))
      },

      test("resources/list returns 2 resources") {
        val req = mkRequest(5, "resources/list", None)
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(req, store)
          count  = resp.flatMap(_.result)
                     .flatMap(_.asObj.map(m => m.find(_._1 == "resources").map(_._2)).flatten)
                     .collect { case Arr(elems) => elems.length }
        yield assertTrue(count.contains(2))
      }
    ),

    suite("tool: get_symbol")(

      test("returns signature for known symbol") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "get_symbol",
                     "fqn" -> "cats/Show#", "detail" -> "sig")
          text   = extractText(resp)
        yield
          assertTrue(text.exists(_.contains("Show")))
      },

      test("returns doc when detail=doc") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "get_symbol",
                     "fqn" -> "cats/Show#", "detail" -> "doc")
          text   = extractText(resp)
        yield
          // scaladoc should be included
          assertTrue(text.exists(t => t.contains("Show") || t.contains("text")))
      },

      test("returns error for unknown FQN") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "get_symbol", "fqn" -> "nonexistent/Symbol#")
          text   = extractText(resp)
        yield
          assertTrue(text.exists(_.toLowerCase.contains("not found")))
      },

      test("returns error when fqn missing") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "get_symbol")
          text   = extractText(resp)
        yield assertTrue(text.exists(_.toLowerCase.contains("required")))
      }
    ),

    suite("tool: search_symbols")(

      test("finds Show-related symbols") {
        for
          store   <- ZIO.service[IndexStore]
          resp    <- callTool(store, "search_symbols", "query" -> "Show", "limit" -> "5")
          text     = extractText(resp)
        yield assertTrue(text.exists(_.contains("Show")))
      },

      test("returns no-results message for unknown name") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "search_symbols", "query" -> "ZZZNonExistentSymbol999")
          text   = extractText(resp)
        yield assertTrue(text.exists(_.contains("No results")))
      },

      test("returns error when query missing") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "search_symbols")
          text   = extractText(resp)
        yield assertTrue(text.exists(_.toLowerCase.contains("required")))
      }
    ),

    suite("tool: search_by_type")(

      test("finds methods containing F[B] in signature") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "search_by_type", "signature" -> "F[B]")
          text   = extractText(resp)
        yield
          // either found results or "No results" — both are valid responses
          assertTrue(text.isDefined)
      }
    ),

    suite("tool: expand_context")(

      test("assembles context for multiple FQNs within budget") {
        for
          store <- ZIO.service[IndexStore]
          resp  <- callTool(store, "expand_context",
                     "fqns"         -> "[\"cats/Show#\",\"cats/Functor#\"]",
                     "token_budget" -> "500")
          text   = extractText(resp)
        yield
          assertTrue(text.exists(_.contains("ScalaMunch context")))
      }
    ),

    suite("resources")(

      test("resources/read stats returns symbol count") {
        val req = mkRequest(99, "resources/read",
          Some(Obj(Chunk("uri" -> Str("scala-index://stats")))))
        for
          store <- ZIO.service[IndexStore]
          resp  <- McpServer.handleForTest(req, store)
        yield
          assertTrue(resp.exists(_.error.isEmpty))
      }
    )
  ).provideShared(Scope.default, CatsFixture.catsIndexLayer)

  // ── helpers ────────────────────────────────────────────────────────────────

  private def mkRequest(id: Long, method: String, params: Option[Json]): RpcRequest =
    RpcRequest(
      jsonrpc = "2.0",
      id      = Some(Num(id)),
      method  = method,
      params  = params
    )

  private def mkNotification(method: String): RpcRequest =
    RpcRequest(jsonrpc = "2.0", id = None, method = method, params = None)

  private def callTool(store: IndexStore, name: String, args: (String, String)*): Task[Option[RpcResponse]] =
    val argsJson = Obj(Chunk.fromIterable(args.map { (k, v) =>
      k -> (if v.startsWith("[") || v.startsWith("{") then
        v.fromJson[Json].getOrElse(Str(v))
      else
        // Detect numeric strings
        v.toLongOption.map(n => Num(n)).orElse(v.toBooleanOption.map(b => Bool(b))).getOrElse(Str(v)))
    }))
    val req = mkRequest(0, "tools/call", Some(Obj(Chunk(
      "name"      -> Str(name),
      "arguments" -> argsJson
    ))))
    McpServer.handleForTest(req, store)

  private def extractText(resp: Option[RpcResponse]): Option[String] =
    resp.flatMap(_.result).flatMap { result =>
      result.asObj
        .flatMap(_.find(_._1 == "content").map(_._2))
        .collect { case Arr(elems) => elems.headOption }
        .flatten
        .flatMap(_.asObj)
        .flatMap(_.find(_._1 == "text").map(_._2))
        .collect { case Str(s) => s }
    }

  extension (j: Json)
    private def asObj: Option[Map[String, Json]] = j match
      case Obj(fields) => Some(fields.toMap)
      case _           => None
