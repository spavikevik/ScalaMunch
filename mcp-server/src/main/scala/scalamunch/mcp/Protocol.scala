package scalamunch.mcp

import zio.json.*
import zio.json.ast.Json
import zio.json.ast.Json.*

// ── JSON-RPC 2.0 wire types ───────────────────────────────────────────────────

/** Normalised request id — String or Int-as-String. */
opaque type RpcId = String
object RpcId:
  def fromJson(j: Json): RpcId = j match
    case Num(n) => n.longValue.toString
    case Str(s) => s
    case _      => "0"
  given JsonDecoder[RpcId] = JsonDecoder[Json].map(fromJson)
  given JsonEncoder[RpcId] = JsonEncoder.string.contramap(id => id)

case class RpcRequest(
  jsonrpc: String,
  id: Option[Json],   // may be Str, Num, or absent (notification)
  method: String,
  params: Option[Json]
) derives JsonDecoder

case class RpcResponse(
  jsonrpc: String,
  id: Json,
  result: Option[Json],
  error: Option[RpcError]
) derives JsonEncoder, JsonDecoder

case class RpcError(code: Int, message: String, data: Option[String] = None) derives JsonEncoder, JsonDecoder

object RpcError:
  val MethodNotFound = RpcError(-32601, "Method not found")
  val InvalidParams  = RpcError(-32602, "Invalid params")
  def parseError(detail: String) = RpcError(-32700, s"Parse error: $detail")
  def internalError(msg: String) = RpcError(-32603, s"Internal error: $msg")

// ── MCP-specific response types ───────────────────────────────────────────────

case class McpServerInfo(name: String, version: String)           derives JsonEncoder
case class McpCapabilities(tools: Json, resources: Json)          derives JsonEncoder
case class InitializeResult(
  protocolVersion: String,
  capabilities: McpCapabilities,
  serverInfo: McpServerInfo
) derives JsonEncoder

case class ToolParam(
  @jsonField("type")        typeName: String,
  description: String,
  @jsonField("enum")        enumValues: Option[List[String]] = None
) derives JsonEncoder

case class ToolInputSchema(
  @jsonField("type")        typeName: String,
  properties: Map[String, ToolParam],
  required: Option[List[String]] = None
) derives JsonEncoder

case class ToolDef(
  name: String,
  description: String,
  inputSchema: ToolInputSchema
) derives JsonEncoder

case class TextContent(
  @jsonField("type") typeName: String,
  text: String
) derives JsonEncoder
object TextContent:
  def apply(text: String): TextContent = TextContent("text", text)

case class ToolResult(
  content: List[TextContent],
  isError: Option[Boolean] = None
) derives JsonEncoder

case class ResourceDef(
  uri: String, name: String, description: String, mimeType: String
) derives JsonEncoder

case class ResourceContent(uri: String, mimeType: String, text: String) derives JsonEncoder
case class ReadResourceResult(contents: List[ResourceContent])           derives JsonEncoder

// ── helpers ───────────────────────────────────────────────────────────────────

def reqId(req: RpcRequest): Json = req.id.getOrElse(Json.Num(0))

def ok(id: Json, result: Json): RpcResponse   = RpcResponse("2.0", id, Some(result), None)
def rpcErr(id: Json, e: RpcError): RpcResponse = RpcResponse("2.0", id, None, Some(e))

extension (req: RpcRequest)
  def idJson: Json = req.id.getOrElse(Json.Num(0))

extension (j: Json)
  def str(key: String): Option[String] = j match
    case Obj(fields) => fields.find(_._1 == key).map(_._2).collect { case Str(s) => s }
    case _           => None
  def int(key: String): Option[Int] = j match
    case Obj(fields) => fields.find(_._1 == key).map(_._2).collect { case Num(n) => n.intValue }
    case _           => None
  def obj(key: String): Option[Json] = j match
    case Obj(fields) => fields.find(_._1 == key).map(_._2)
    case _           => None
  def arr(key: String): Option[List[Json]] = j match
    case Obj(fields) => fields.find(_._1 == key).map(_._2).collect { case Arr(elems) => elems.toList }
    case _           => None

extension [A: JsonEncoder](a: A)
  def toJson: String                        = a.toJsonPretty
  def toJsonCompact: String                 = summon[JsonEncoder[A]].encodeJson(a, None).toString
  def asJsonAst: Json                       =
    a.toJsonAST.getOrElse(Json.Null)
