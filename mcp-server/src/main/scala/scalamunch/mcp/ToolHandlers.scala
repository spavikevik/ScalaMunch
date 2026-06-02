package scalamunch.mcp

import scalamunch.model.{*, given}
import scalamunch.store.IndexStore
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.json.ast.Json.*

/** Executes MCP tool calls against the IndexStore. */
object ToolHandlers:

  def dispatch(name: String, params: Json, store: IndexStore): Task[ToolResult] =
    name match
      case "get_symbol"        => getSymbol(params, store)
      case "search_symbols"    => searchSymbols(params, store)
      case "search_by_type"    => searchByType(params, store)
      case "get_type_context"  => getTypeContext(params, store)
      case "get_implicits_for" => getImplicitsFor(params, store)
      case "find_references"   => findReferences(params, store)
      case "get_call_graph"    => getCallGraph(params, store)
      case "expand_context"      => expandContext(params, store)
      case "list_packages"        => listPackages(params, store)
      case "get_package_overview" => getPackageOverview(params, store)
      case "list_test_symbols"    => listTestSymbols(params, store)
      case "get_coverage_gaps"    => getCoverageGaps(params, store)
      case other                  => ZIO.succeed(errorResult(s"Unknown tool: $other"))

  // ── tool implementations ────────────────────────────────────────────────────

  private def getSymbol(args: Json, store: IndexStore): Task[ToolResult] =
    val fqn    = args.str("fqn").getOrElse("")
    val detail = args.str("detail").getOrElse("sig")
    if fqn.isEmpty then ZIO.succeed(errorResult("fqn is required"))
    else
      store.getSymbol(fqn).map {
        case None      => errorResult(s"Symbol not found: $fqn")
        case Some(sym) => ToolResult(List(TextContent(renderSymbol(sym, detail))))
      }

  private def searchSymbols(args: Json, store: IndexStore): Task[ToolResult] =
    val query = args.str("query").getOrElse("")
    val limit = args.int("limit").getOrElse(10)
    val kind  = args.str("kind")
    if query.isEmpty then ZIO.succeed(errorResult("query is required"))
    else
      store.searchSymbols(query, limit).map { results =>
        val filtered = kind.fold(results)(k => results.filter(_.kind.toString == k))
        if filtered.isEmpty then ToolResult(List(TextContent("No results.")))
        else ToolResult(List(TextContent(filtered.map(renderSymbolBrief).mkString("\n\n"))))
      }

  private def searchByType(args: Json, store: IndexStore): Task[ToolResult] =
    val sig   = args.str("signature").getOrElse("")
    val limit = args.int("limit").getOrElse(8)
    if sig.isEmpty then ZIO.succeed(errorResult("signature is required"))
    else
      store.searchByType(sig, limit).map { results =>
        if results.isEmpty then ToolResult(List(TextContent("No results.")))
        else ToolResult(List(TextContent(results.map(renderSymbolBrief).mkString("\n\n"))))
      }

  private def getTypeContext(args: Json, store: IndexStore): Task[ToolResult] =
    val fqn   = args.str("fqn").getOrElse("")
    if fqn.isEmpty then ZIO.succeed(errorResult("fqn is required"))
    else
      for
        rootOpt    <- store.getSymbol(fqn)
        result     <- rootOpt match
          case None      => ZIO.succeed(errorResult(s"Symbol not found: $fqn"))
          case Some(root) =>
            for
              deps       <- store.getTypeDeps(fqn)
              depSyms    <- ZIO.foreach(deps.take(20))(d => store.getSymbol(d.toFqn).orElse(ZIO.succeed(None)))
              parentSyms <- ZIO.foreach(root.parentFqns.take(5))(p => store.getSymbol(p).orElse(ZIO.succeed(None)))
              combined    = (List(root) ++ depSyms.flatten ++ parentSyms.flatten).distinctBy(_.fqn)
              text        = renderTypeContext(fqn, combined)
            yield ToolResult(List(TextContent(text)))
      yield result

  private def getImplicitsFor(args: Json, store: IndexStore): Task[ToolResult] =
    val typeFqn = args.str("type_fqn").getOrElse("")
    val limit   = args.int("limit").getOrElse(20)
    if typeFqn.isEmpty then ZIO.succeed(errorResult("type_fqn is required"))
    else
      for
        implicits <- store.getImplicitsFor(typeFqn)
        instSyms  <- ZIO.foreach(implicits.take(limit))(e => store.getSymbol(e.instanceFqn).orElse(ZIO.succeed(None)))
        results    = instSyms.flatten
        text       =
          if results.isEmpty then
            s"No given/implicit instances found for $typeFqn.\n" +
            "Note: implicit index is populated from SemanticDB. Run sbt compile first."
          else
            s"Given/implicit instances for ${displayFqn(typeFqn)}:\n\n" +
            results.map(renderSymbolBrief).mkString("\n\n")
      yield ToolResult(List(TextContent(text)))

  private def findReferences(args: Json, store: IndexStore): Task[ToolResult] =
    val fqn   = args.str("fqn").getOrElse("")
    val limit = args.int("limit").getOrElse(20)
    if fqn.isEmpty then ZIO.succeed(errorResult("fqn is required"))
    else
      // Phase 2: return type deps as proxy. Full reference tracking in Phase 3.
      store.getTypeDeps(fqn).map { deps =>
        if deps.isEmpty then
          ToolResult(List(TextContent(
            s"No indexed references for $fqn.\n" +
            "Full reference tracking requires SemanticDB (Phase 3)."
          )))
        else
          val lines = deps.take(limit).map(d => s"  ${d.rel}: ${displayFqn(d.toFqn)}")
          ToolResult(List(TextContent(
            s"Type-level references from ${displayFqn(fqn)}:\n${lines.mkString("\n")}"
          )))
      }

  private def getCallGraph(args: Json, store: IndexStore): Task[ToolResult] =
    val fqn = args.str("fqn").getOrElse("")
    if fqn.isEmpty then ZIO.succeed(errorResult("fqn is required"))
    else
      for
        symOpt <- store.getSymbol(fqn)
        deps   <- store.getTypeDeps(fqn)
        text    = symOpt match
          case None => s"Symbol not found: $fqn"
          case Some(sym) =>
            val header = s"${sym.kind} ${sym.name}: ${sym.signature}"
            if deps.isEmpty then
              s"$header\n  (no type dependencies — compile with SemanticDB for full call graph)"
            else
              s"$header\n" + deps.take(10).map(d => s"  → ${d.rel}: ${displayFqn(d.toFqn)}").mkString("\n")
      yield ToolResult(List(TextContent(text)))

  private def expandContext(args: Json, store: IndexStore): Task[ToolResult] =
    val fqns   = args.arr("fqns").getOrElse(Nil).collect { case Str(s) => s }
    val budget = args.int("token_budget").getOrElse(1000)
    if fqns.isEmpty then ZIO.succeed(errorResult("fqns list is required"))
    else
      for
        syms  <- ZIO.foreach(fqns)(fqn => store.getSymbol(fqn).orElse(ZIO.succeed(None)))
        found  = syms.flatten
        text   = budgetedContext(found, budget)
      yield ToolResult(List(TextContent(text)))

  private def listPackages(args: Json, store: IndexStore): Task[ToolResult] =
    val prefix = args.str("prefix").getOrElse("")
    val limit  = args.int("limit").getOrElse(50)
    store.listPackages.map { pkgs =>
      val filtered = if prefix.isEmpty then pkgs else pkgs.filter(_._1.startsWith(prefix))
      val top      = filtered.take(limit)
      if top.isEmpty then ToolResult(List(TextContent("No packages found.")))
      else
        val lines = top.map { (pkg, cnt) =>
          val display = pkg.stripSuffix("/").replace('/', '.')
          f"  $display%-45s $cnt%4d symbols"
        }
        ToolResult(List(TextContent(
          s"Packages (${top.size} of ${pkgs.size} total):\n\n" + lines.mkString("\n")
        )))
    }

  private def getPackageOverview(args: Json, store: IndexStore): Task[ToolResult] =
    val pkg   = args.str("package_fqn").getOrElse("")
    val limit = args.int("limit").getOrElse(20)
    if pkg.isEmpty then ZIO.succeed(errorResult("package_fqn is required"))
    else
      store.getPackageSymbols(pkg, limit * 8).map { syms =>
        if syms.isEmpty then
          ToolResult(List(TextContent(s"No symbols found in package $pkg. " +
            "Check the FQN format: use 'cats/' not 'cats'.")))
        else
          val byKind = syms.groupBy(_.kind).toList.sortBy(_._1.toString)
          val sb     = StringBuilder()
          val pkgDisplay = pkg.stripSuffix("/").replace('/', '.')
          sb.append(s"// Package $pkgDisplay — ${syms.size} symbols\n\n")
          for (kind, group) <- byKind do
            sb.append(s"// ── $kind (${group.size}) ──────────────────────\n")
            group.take(limit).foreach { sym =>
              sym.doc.foreach(d => sb.append(s"/** ${d.take(80)}${if d.length > 80 then "…" else ""} */\n"))
              sb.append(sym.signature).append("\n")
            }
            if group.size > limit then
              sb.append(s"// … ${group.size - limit} more\n")
            sb.append("\n")
          ToolResult(List(TextContent(sb.toString)))
      }

  private def listTestSymbols(args: Json, store: IndexStore): Task[ToolResult] =
    val prefix = args.str("prefix").getOrElse("")
    val limit  = args.int("limit").getOrElse(300)
    store.getTestSymbols(limit).map { syms =>
      val filtered = if prefix.isEmpty then syms
                     else syms.filter(_.fqn.startsWith(prefix))
      if filtered.isEmpty then ToolResult(List(TextContent("No test symbols found.")))
      else
        val byFile = filtered.groupBy(_.file).toList.sortBy(_._1)
        val sb     = StringBuilder()
        sb.append(s"// Test symbols — ${filtered.size} across ${byFile.size} spec files\n\n")
        for (file, group) <- byFile do
          val fileName = file.split("/").lastOption.getOrElse(file)
          sb.append(s"// ── $fileName ──────────────────────\n")
          val topLevel = group.filter(s => s.kind == SymbolKind.Object || s.kind == SymbolKind.Class)
          val rest     = group.filterNot(s => s.kind == SymbolKind.Object || s.kind == SymbolKind.Class)
          topLevel.foreach(s => sb.append(s"${s.signature}\n"))
          rest.foreach(s => sb.append(s"  ${s.signature}\n"))
          sb.append("\n")
        ToolResult(List(TextContent(sb.toString)))
    }

  private def getCoverageGaps(args: Json, store: IndexStore): Task[ToolResult] =
    val pkg = args.str("package_fqn").getOrElse("")
    if pkg.isEmpty then ZIO.succeed(errorResult("package_fqn is required"))
    else
      for
        prodTypes <- store.getProductionTypesFor(pkg)
        testSyms  <- store.getTestSymbols(500)
        covered   <- ZIO.attempt {
                       // Build set of names that test files cover.
                       // FooSpec / FooTest / FooCheck / FooSuite → covers "Foo"
                       // Also check if the production name appears as substring in any test class name.
                       val testNames = testSyms
                         .filter(s => s.kind == SymbolKind.Object || s.kind == SymbolKind.Class)
                         .flatMap { s =>
                           val n = s.name
                           val stripped = List("Spec", "Test", "Suite", "Check", "Tests")
                             .foldLeft(n)((acc, suffix) => if acc.endsWith(suffix) then acc.dropRight(suffix.length) else acc)
                           List(n, stripped)
                         }.toSet
                       prodTypes.partition(p => testNames.exists(t =>
                         t == p.name || t.contains(p.name) || p.name.contains(t)
                       ))
                     }
        (tested, gaps) = covered
        text = if gaps.isEmpty then
          s"// Full coverage: all ${prodTypes.size} types in ${displayFqn(pkg)} have matching specs.\n" +
          tested.map(s => s"  ✓ ${s.name}").mkString("\n")
        else
          val sb = StringBuilder()
          sb.append(s"// Coverage gaps in ${displayFqn(pkg)}\n")
          sb.append(s"// ${tested.size} covered, ${gaps.size} missing test\n\n")
          if tested.nonEmpty then
            sb.append("// Covered:\n")
            tested.foreach(s => sb.append(s"  ✓ [${s.kind}] ${s.name}  (${s.fqn})\n"))
            sb.append("\n")
          sb.append("// NOT covered — no matching Spec/Test/Suite/Check:\n")
          gaps.foreach(s => sb.append(s"  ✗ [${s.kind}] ${s.name}  (${s.fqn})\n"))
          sb.toString
      yield ToolResult(List(TextContent(text)))

  // ── rendering ────────────────────────────────────────────────────────────────

  private def renderSymbol(sym: ScalaSymbol, detail: String): String =
    val sb = StringBuilder()
    sb.append(s"// ${sym.kind}  ${sym.file}:${sym.lineStart}\n")
    detail match
      case "sig" =>
        sb.append(sym.signature)
      case "doc" =>
        sym.doc.foreach(d => sb.append(s"/** $d */\n"))
        sb.append(sym.signature)
      case _ =>  // type-ctx, full
        sym.doc.foreach(d => sb.append(s"/** $d */\n"))
        sb.append(sym.signature)
        if sym.parentFqns.nonEmpty then
          sb.append(s"\n// parents: ${sym.parentFqns.map(displayFqn).mkString(", ")}")
        if sym.typeParams.nonEmpty then
          sb.append(s"\n// type params: [${sym.typeParams.mkString(", ")}]")
    sb.toString

  private def renderSymbolBrief(sym: ScalaSymbol): String =
    val loc = s"${sym.file.split("/").lastOption.getOrElse(sym.file)}:${sym.lineStart}"
    val doc = sym.doc.map(d => s"\n    // $d").getOrElse("")
    s"[${sym.kind}] ${sym.signature}$doc\n    fqn: ${sym.fqn}  at: $loc"

  private def renderTypeContext(rootFqn: String, syms: List[ScalaSymbol]): String =
    if syms.isEmpty then s"No type context found for $rootFqn"
    else
      val header = s"// Type context for ${displayFqn(rootFqn)} — ${syms.size} symbols\n\n"
      header + syms.map { s =>
        s.doc.map(d => s"/** $d */\n").getOrElse("") + s.signature
      }.mkString("\n\n")

  private def budgetedContext(syms: List[ScalaSymbol], budget: Int): String =
    if syms.isEmpty then "No symbols found."
    else
      var remaining = budget
      val sb = StringBuilder()
      sb.append(s"// ScalaMunch context — ${syms.size} symbols, budget: ~$budget tokens\n\n")
      for sym <- syms do
        val cost = (sym.signature.length / 4).max(5)
        if remaining > cost then
          sym.doc.foreach(d => sb.append(s"/** $d */\n"))
          sb.append(sym.signature).append("\n\n")
          remaining -= cost
        else
          sb.append(s"// [truncated] ${sym.name}\n")
      sb.toString

  private def displayFqn(fqn: String): String =
    fqn.replace("/", ".").stripSuffix("#").stripSuffix(".")

  private def errorResult(msg: String): ToolResult =
    ToolResult(List(TextContent(s"Error: $msg")), isError = Some(true))
