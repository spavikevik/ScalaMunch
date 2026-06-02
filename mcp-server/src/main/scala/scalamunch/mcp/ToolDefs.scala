package scalamunch.mcp

/** MCP tool schema definitions for all 8 ScalaMunch tools. */
object ToolDefs:

  val getSymbol = ToolDef(
    name        = "get_symbol",
    description = "Get a Scala symbol by fully-qualified name at a specific detail level. " +
      "Use 'sig' (20–50 tokens) for signatures, 'doc' to include scaladoc, " +
      "'type-ctx' to include resolved type dependencies, 'full' for the complete declaration.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "fqn"    -> ToolParam("string", "Fully-qualified name, e.g. com/example/Foo# or io/circe/Decoder#"),
        "detail" -> ToolParam("string", "Detail level", Some(List("sig", "doc", "type-ctx", "full")))
      ),
      required   = Some(List("fqn"))
    )
  )

  val searchSymbols = ToolDef(
    name        = "search_symbols",
    description = "Full-text search for Scala symbols by name. Returns signatures and locations. " +
      "Optionally filter by kind (class/trait/object/def/val/type/given).",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "query" -> ToolParam("string", "Search query (name, partial name, or keyword)"),
        "kind"  -> ToolParam("string", "Filter by symbol kind",
                    Some(List("Class", "Trait", "Object", "Def", "Val", "Type", "Given", "Extension"))),
        "limit" -> ToolParam("integer", "Maximum results (default: 10)")
      ),
      required   = Some(List("query"))
    )
  )

  val searchByType = ToolDef(
    name        = "search_by_type",
    description = "Hoogle-style search: find symbols whose type signature matches a pattern. " +
      "Useful for finding functions that return or accept a specific type.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "signature" -> ToolParam("string", "Type signature pattern, e.g. 'Option[Foo]' or 'A => F[B]'"),
        "limit"     -> ToolParam("integer", "Maximum results (default: 8)")
      ),
      required   = Some(List("signature"))
    )
  )

  val getTypeContext = ToolDef(
    name        = "get_type_context",
    description = "Get all types needed to understand a symbol — parents, type parameters, " +
      "referenced types in method signatures. Returns compressed signatures only. " +
      "Essential before implementing or modifying a symbol.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "fqn"   -> ToolParam("string", "Fully-qualified name of the symbol"),
        "depth" -> ToolParam("integer", "Dependency resolution depth (default: 2, max: 4)")
      ),
      required   = Some(List("fqn"))
    )
  )

  val getImplicitsFor = ToolDef(
    name        = "get_implicits_for",
    description = "Find all given/implicit instances for a typeclass or type. " +
      "E.g. get_implicits_for('cats/Show#') returns every Show[X] instance in the index.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "type_fqn" -> ToolParam("string", "FQN of the typeclass or type, e.g. cats/Show#"),
        "limit"    -> ToolParam("integer", "Maximum results (default: 20)")
      ),
      required   = Some(List("type_fqn"))
    )
  )

  val findReferences = ToolDef(
    name        = "find_references",
    description = "Find all usages of a symbol across the indexed codebase. " +
      "Requires SemanticDB (.semanticdb files) for full accuracy.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "fqn"   -> ToolParam("string", "FQN of the symbol to find usages of"),
        "limit" -> ToolParam("integer", "Maximum results (default: 20)")
      ),
      required   = Some(List("fqn"))
    )
  )

  val getCallGraph = ToolDef(
    name        = "get_call_graph",
    description = "Get the call graph for a function — which functions it calls and which call it. " +
      "Requires SemanticDB for full accuracy; returns partial results from the type index otherwise.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "fqn"   -> ToolParam("string", "FQN of the function"),
        "depth" -> ToolParam("integer", "Call graph depth (default: 2, max: 4)")
      ),
      required   = Some(List("fqn"))
    )
  )

  val expandContext = ToolDef(
    name        = "expand_context",
    description = "Assemble minimal context for a list of FQNs within a token budget. " +
      "Prioritizes by relevance: signatures first, then docs, then type deps, then bodies. " +
      "Returns a compact multi-symbol context block.",
    inputSchema = ToolInputSchema(
      typeName   = "object",
      properties = Map(
        "fqns"         -> ToolParam("array",   "List of fully-qualified names to include"),
        "token_budget" -> ToolParam("integer", "Approximate token budget (default: 1000)")
      ),
      required   = Some(List("fqns"))
    )
  )

  val allTools: List[ToolDef] = List(
    getSymbol, searchSymbols, searchByType, getTypeContext,
    getImplicitsFor, findReferences, getCallGraph, expandContext
  )
