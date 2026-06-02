# ScalaMunch

Type-driven Scala code index with MCP server. Provides surgical symbol-level lookups instead of full-file context, cutting AI token usage **40–55%** on real Scala codebases.

Built on **Scalameta**, **SemanticDB**, and **TASTy** (Scala 3) — not a generic indexer but one that understands Scala's type system, implicits, typeclasses, and `given`/`using` chains.

---

## Why ScalaMunch?

When an AI agent needs to understand `def decode[A: Decoder](json: String): Either[Error, A]`, it shouldn't have to read the entire circe source tree. It needs:

1. The signature of `Decoder[A]`
2. Which `Decoder` instances are in scope for `A`
3. The `HCursor` API it'll need

ScalaMunch assembles that context from the index in **~80 tokens** instead of the **~4,000** a full-file pass would cost.

### Token Budget Tiers

| Tier | Content | ~Tokens | Reduction vs full file |
|------|---------|---------|------------------------|
| `sig` | `def foo(x: Int): String` | 20–50 | 90–95% |
| `sig+doc` | signature + scaladoc | 50–200 | 75–85% |
| `type-ctx` | sig + all referenced types resolved | 100–500 | 50–70% |
| `full` | body + type-ctx + implicits | 500–2k | 20–40% |

**Weighted realistic average: 40–55% reduction** across a typical agentic coding session.

---

## Releases

Pre-built JARs are available on the [GitHub Releases page](https://github.com/spavikevik/ScalaMunch/releases).

**Prerequisites:** Java 21+

### Option A — Download JARs (no build required)

Download `scala-munch-cli-assembly.jar` and `scala-munch-mcp-assembly.jar` from the latest release assets.

```bash
java -jar scala-munch-cli-assembly.jar build src/main/scala --db .scala-munch.db
java -jar scala-munch-mcp-assembly.jar --db .scala-munch.db   # MCP stdio server
```

### Option B — GitHub Packages (MCP server + sbt plugin)

Add credentials to `~/.sbt/1.0/credentials.sbt` (requires a [GitHub PAT](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages) with `read:packages`):

```scala
credentials += Credentials("GitHub Package Registry", "maven.pkg.github.com",
  "<your-github-username>", "<your-PAT>")
```

In `project/plugins.sbt`:

```scala
resolvers += "GitHub Packages" at "https://maven.pkg.github.com/spavikevik/ScalaMunch"
addSbtPlugin("io.scalamunch" % "sbt-scala-munch" % "0.1.0-alpha.1")
```

### Option C — Build from source

```bash
git clone https://github.com/spavikevik/ScalaMunch
cd ScalaMunch
bin/install.sh      # builds assembly jars (~2 min first run)
```

---

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                        ScalaMunch                             │
├─────────────────┬─────────────────────┬───────────────────────┤
│   INDEXER       │    INDEX STORE      │   MCP SERVER          │
│                 │                     │                       │
│ Scalameta AST   │  SQLite + FTS5      │  stdio / JSON-RPC 2.0 │
│ SemanticDB      │  Symbol graph       │  8 tools              │
│ TASTy (Sc3)     │  Type dep DAG       │  2 resources          │
│ BSP client      │  Implicit index     │  Claude Code compat   │
├─────────────────┤  Content-addressed  ├───────────────────────┤
│   WATCHER       │  source blobs       │  TYPE-SKILL           │
│ BSP events      │  Incremental:       │  Type resolver        │
│ NIO WatchService│  digest invalidate  │  Context budgeter     │
│                 │  + reindex touched  │  Implicit tracer      │
└─────────────────┴─────────────────────┴───────────────────────┘
```

### Modules

| Module | Purpose |
|--------|---------|
| `core` | Scalameta AST parser, SemanticDB protobuf reader, signature extractor |
| `store` | SQLite + FTS5 storage, type dependency graph, implicit index |
| `cli` | `build / query / search-type / stats / watch` CLI commands |
| `mcp-server` | MCP stdio server exposing 8 tools to AI assistants |
| `sbt-plugin` | `sbt-scala-munch` — `scalaMunchIndex` task, auto-index after compile |

---

## Quick Start

### Index Your Codebase

```bash
# Index current directory (Scala 3 project with SemanticDB)
bin/scala-munch build src/main/scala --db .scala-munch.db

# Scala 2 project
bin/scala-munch build src/main/scala --db .scala-munch.db --scala2

# Force full re-index
bin/scala-munch build src/main/scala --db .scala-munch.db --force

# Watch mode — reindex on every save
bin/scala-munch watch src/main/scala --db .scala-munch.db
```

> **Tip:** Add `.scala-munch.db` to your `.gitignore`.

### sbt Plugin (auto-index after compile)

In your project's `project/plugins.sbt`:

```scala
resolvers += "GitHub Packages" at "https://maven.pkg.github.com/spavikevik/ScalaMunch"
addSbtPlugin("io.scalamunch" % "sbt-scala-munch" % "0.1.0-alpha.1")
```

In `build.sbt`:

```scala
.enablePlugins(ScalaMunchPlugin)
// optional overrides:
// scalaMunchDb := file(".scala-munch.db")
// scalaMunchEnabled := true
```

Run compile + index in one step:

```bash
sbt scalaMunchIndex          # compile then index
sbt ~scalaMunchIndex         # continuous: compile + index on every save
```

---

## CLI Reference

### `build` — Index a source tree

```
scala-munch build <root> [options]

Options:
  --db <path>          Index DB path (default: .scala-munch.db)
  --scala2             Parse as Scala 2 (default: Scala 3)
  --no-semanticdb      Skip SemanticDB augmentation
  --force              Force re-index all files
```

### `query` — Full-text search by name

```
scala-munch query <query> [options]

Options:
  --db <path>          Index DB path (default: .scala-munch.db)
  --limit <n>          Max results (default: 20)
```

Example:

```
$ scala-munch query Decoder --limit 5

[Trait] trait Decoder[A]
  fqn : io/circe/Decoder#
  at  : Decoder.scala:12
  doc : A type class that provides a way to produce a value of type A from a JSON value.

[Object] object Decoder
  fqn : io/circe/Decoder.
  at  : Decoder.scala:198

[Def] def decodeJson[A](implicit d: Decoder[A]): Decoder[Json] =
  fqn : io/circe/Decoder.decodeJson().
  at  : Decoder.scala:201
```

### `search-type` — Hoogle-style type signature search

Find symbols whose signature matches a type pattern:

```
scala-munch search-type "Option[Foo]" --db .scala-munch.db

[Def] def findById(id: Int): F[Option[Foo]] =
  fqn : com/example/FooRepository.findById().
  at  : FooRepository.scala:5
```

### `stats` — Index statistics

```
$ bin/scala-munch stats

Symbols   : 4,821
Files     : 312
Implicits : 147
Type deps : 2,103
Updated   : 2026-06-02T13:40:08Z
```

### `watch` — Incremental watch mode

```
scala-munch watch <root> [options]

Options:
  --db <path>          Index DB path (default: .scala-munch.db)
  --scala2             Parse as Scala 2
  --no-semanticdb      Skip SemanticDB augmentation
  --force              Rebuild index on start
  --debounce <ms>      Debounce window in ms (default: 300)
```

Watches the source tree with NIO WatchService. On any `.scala` change, waits for the debounce window then reindexes only changed files. With SemanticDB enabled, picks up freshly compiled `.semanticdb` files automatically.

---

## MCP Server

ScalaMunch exposes an MCP (Model Context Protocol) server over stdio, compatible with **Claude Code**, **GitHub Copilot**, **Cursor**, and any MCP-capable client.

### Claude Code

After running `bin/install.sh`, the project-level `.mcp.json` is already configured:

```json
{
  "mcpServers": {
    "scala-munch": {
      "type": "stdio",
      "command": "bin/scala-munch-mcp",
      "args": ["--db", ".scala-munch.db"]
    }
  }
}
```

Reload: **Claude Code → Reload MCP Servers** (or reopen the workspace).

For a **global** setup (all projects), add to `~/.claude/mcp.json`:

```json
{
  "mcpServers": {
    "scala-munch": {
      "type": "stdio",
      "command": "/absolute/path/to/scala-munch/bin/scala-munch-mcp",
      "args": ["--db", ".scala-munch.db"]
    }
  }
}
```

### GitHub Copilot (VS Code)

The project-level `.vscode/mcp.json` is included:

```json
{
  "servers": {
    "scala-munch": {
      "type": "stdio",
      "command": "${workspaceFolder}/bin/scala-munch-mcp",
      "args": ["--db", "${workspaceFolder}/.scala-munch.db"]
    }
  }
}
```

VS Code picks this up automatically when the **MCP: Enable MCP Servers** setting is on (VS Code 1.99+). Copilot will list `scala-munch` tools in the chat tool picker.

### Cursor

Add to `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "scala-munch": {
      "type": "stdio",
      "command": "bin/scala-munch-mcp",
      "args": ["--db", ".scala-munch.db"]
    }
  }
}
```

### Available Tools

| Tool | Description |
|------|-------------|
| `get_symbol` | Get a symbol at a specific detail level (`sig`, `doc`, `type-ctx`, `full`) |
| `search_symbols` | Full-text search by name with optional kind filter |
| `search_by_type` | Hoogle-style: find symbols matching a type signature pattern |
| `get_type_context` | All types needed to understand a symbol (resolved deps) |
| `get_implicits_for` | Find `given`/`implicit` instances for a type |
| `find_references` | Usages of a symbol (requires SemanticDB) |
| `get_call_graph` | Call graph for a function up to N levels deep |
| `expand_context` | Assemble minimal context for a list of FQNs within a token budget |

### Tool Examples

**`get_symbol`** — Get compressed signature (20–50 tokens instead of full source):
```json
{
  "name": "get_symbol",
  "arguments": { "fqn": "io/circe/Decoder#", "detail": "sig" }
}
// → "trait Decoder[A]"
```

**`search_by_type`** — Hoogle-style search:
```json
{
  "name": "search_by_type",
  "arguments": { "signature": "A => F[B]" }
}
// → symbols with signatures matching that shape
```

**`expand_context`** — Budget-aware context assembly:
```json
{
  "name": "expand_context",
  "arguments": {
    "fqns": ["com/example/Foo#", "io/circe/Decoder#"],
    "token_budget": 500
  }
}
// → packed context within budget, highest-priority symbols first
```

### Resources

| Resource URI | Description |
|-------------|-------------|
| `scala-index://symbols` | Paginated full symbol listing |
| `scala-index://stats` | Current index statistics |

---

## Scala-Specific Features

ScalaMunch understands features that generic indexers miss:

### SemanticDB Integration

After `sbt compile` (with `semanticdb-scalac` plugin), ScalaMunch reads `.semanticdb` files for **resolved type signatures**. Without compilation, it falls back to Scalameta AST-based signatures (still very accurate).

```scala
// sbt — enable SemanticDB
addCompilerPlugin("org.scalameta" % "semanticdb-scalac" % "4.17.0" cross CrossVersion.full)
scalacOptions += "-Yrangepos"

// Scala 3 — built-in
scalacOptions += "-Xsemanticdb"
```

### TASTy Reader (Scala 3)

For Scala 3 projects, ScalaMunch reads `.tasty` files which carry full typed ASTs — more precise than SemanticDB for union/intersection types, opaque types, and `inline` definitions.

### Implicit / Given Index

ScalaMunch tracks typeclass instances:

```
get_implicits_for("cats/Show#") →
  given Show[Foo] at com/example/Foo.showInstance
  given Show[Bar] at com/example/Bar.showInstance
```

When implementing `show` for a new type, the AI gets all existing instances automatically.

### Type Dependency Graph

Every symbol's type dependencies are indexed. `get_type_context("com/example/Foo#")` returns the minimal set of types needed to understand `Foo` — no import chasing required.

### Extension Methods (Scala 3)

`extension` methods are linked to their target type. `search_symbols("Int")` with kind `Extension` finds all extension methods on `Int`.

---

## Token Reduction — How It Works

### Scenario: Implement a new `Decoder` for a case class

**Without ScalaMunch:** Agent reads `Decoder.scala` (~500 lines), `HCursor.scala` (~300 lines), your model file (~50 lines) = ~2,800 tokens.

**With ScalaMunch (`type-ctx` tier):**
```
get_type_context("io/circe/Decoder#") →
  trait Decoder[A] { def apply(c: HCursor): Result[A] }
  type Result[A] = Either[DecodingFailure, A]
  case class HCursor(...)  // sig only
  given Decoder[Int]       // sig
  given Decoder[String]    // sig
  given Decoder[List[A]]   // sig
```
~180 tokens. **94% reduction** on this specific query.

Realistic across a full session (mix of reads and writes): **40–55%** reduction.

---

## Development

### Project Structure

```
scala-munch/
├── build.sbt
├── bin/
│   ├── install.sh                 # build jars + first-time setup
│   ├── scala-munch                # CLI wrapper (java -jar)
│   └── scala-munch-mcp            # MCP server wrapper (java -jar)
├── .mcp.json                      # Claude Code MCP config
├── .vscode/mcp.json               # VS Code / Copilot MCP config
├── core/                          # Scalameta + SemanticDB parsing
│   └── src/main/scala/scalamunch/
│       ├── model/Symbol.scala     # Data model
│       ├── extract/               # Signature extractor
│       └── parser/                # AST + SemanticDB readers
├── store/                         # SQLite storage
│   └── src/main/scala/scalamunch/store/
│       ├── Schema.scala           # DDL
│       └── IndexStore.scala       # ZIO-wrapped JDBC (Semaphore write lock)
├── cli/                           # CLI entry point
│   └── src/main/scala/scalamunch/cli/
│       ├── Indexer.scala          # File walker + digest-based skip
│       ├── Watcher.scala          # NIO incremental watch + debounce
│       └── Main.scala             # Decline CLI (build/query/search-type/stats/watch)
├── mcp-server/                    # MCP stdio server
│   └── src/main/scala/scalamunch/mcp/
│       ├── Protocol.scala         # JSON-RPC 2.0 types
│       ├── ToolDefs.scala         # Tool schemas
│       ├── ToolHandlers.scala     # Tool execution
│       └── McpServer.scala        # Stdio loop + dispatch
└── sbt-plugin/                    # sbt plugin (Scala 2.12)
    └── src/main/scala/scalamunch/sbt/
        └── ScalaMunchPlugin.scala # scalaMunchIndex task
```

### Running Tests

```bash
sbt test
```

### Build Fat JAR

```bash
sbt assembly
# Produces: cli/target/scala-3.3.3/scala-munch-cli-assembly.jar
```

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `org.scalameta:scalameta_3` | 4.17.0 | AST parsing |
| `org.scalameta:semanticdb-shared_3` | 4.17.0 | SemanticDB protobuf |
| `dev.zio:zio_3` | 2.1.26 | Effects + streaming |
| `org.xerial:sqlite-jdbc` | 3.53.1.0 | Storage |
| `com.monovore:decline_3` | 2.6.2 | CLI parsing |
| `dev.zio:zio-json_3` | 0.7.3 | JSON for MCP protocol |

---

## Comparison

| Feature | ScalaMunch | jCodeMunch | Generic LSP |
|---------|-----------|------------|-------------|
| Symbol-level retrieval | ✅ | ✅ | ✅ |
| Scala type resolution | ✅ (SemanticDB/TASTy) | ❌ | Partial |
| Implicit/given index | ✅ | ❌ | ❌ |
| Type dependency graph | ✅ | ❌ | ❌ |
| Hoogle-style search | ✅ | ❌ | ❌ |
| Token budget assembly | ✅ | ❌ | ❌ |
| Scala 2 support | ✅ | ❌ | ✅ |
| Scala 3 TASTy | ✅ | ❌ | Partial |
| MCP server | ✅ | ✅ | ❌ |
| Incremental updates | ✅ | ✅ | ✅ |

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

## Acknowledgements

Bootstrapped with [Claude Code](https://claude.ai/code) (Anthropic). Architecture, design decisions, and code review by Stefan Pavikjevikj.
