---
applyTo: "**/*.scala"
---

# ScalaMunch

This project has a ScalaMunch MCP server running. When working with Scala files, use ScalaMunch tools instead of reading source files to retrieve symbol context at lower token cost.

## When to Use ScalaMunch

Use ScalaMunch tools instead of reading `.scala` files when:
- Understanding a Scala type, class, trait, method, or implicit
- Implementing code against an unfamiliar API
- Resolving `given`/`implicit` instances for a typeclass
- Tracing type dependencies across modules

First check `scala-index://stats` — if symbolCount is 0, the index has not been built yet. Fall back to file reads.

## Tool Selection

| Goal | Tool | Key args |
|------|------|----------|
| Signature of known symbol | `get_symbol` | `fqn`, `detail: "sig"` |
| Signature + scaladoc | `get_symbol` | `fqn`, `detail: "sig+doc"` |
| All types to understand a symbol | `get_type_context` | `fqn` |
| Search by name (partial OK) | `search_symbols` | `query` |
| Find by return/param type shape | `search_by_type` | `signature` |
| `given`/`implicit` instances for a type | `get_implicits_for` | `type_fqn` |
| Usages of a symbol | `find_references` | `fqn` |
| Multiple symbols within token budget | `expand_context` | `fqns[]`, `token_budget` |

## FQN Format

| Kind | Pattern | Example |
|------|---------|---------|
| Trait/class | `pkg/Name#` | `io/circe/Decoder#` |
| Object | `pkg/Name.` | `io/circe/Decoder.` |
| Method | `pkg/Name#method().` | `io/circe/Decoder#apply().` |

When FQN is unknown, call `search_symbols` first, then use the returned `fqn` field.

## Detail Levels

| Level | Content | ~Tokens | When to use |
|-------|---------|---------|-------------|
| `sig` | Signature only | 20–50 | Default — name and type shape |
| `sig+doc` | Sig + scaladoc | 50–200 | Behavior unclear from sig alone |
| `type-ctx` | Sig + all resolved deps | 100–500 | Before implementing against a type |
| `full` | Body + deps + implicits | 500–2k | Avoid; prefer `type-ctx` |

## Decision Flow

Before reading any `.scala` source file:
1. `search_symbols` if FQN unknown
2. `get_symbol` with `detail: "sig"` for initial understanding
3. `get_type_context` if dependent types are needed
4. `get_implicits_for` if typeclass instances are needed
5. `expand_context` to pack multiple FQNs within a token budget (default: 500)

## Boundaries

- `find_references` requires SemanticDB (project must be compiled with `-Xsemanticdb`). Skip if unavailable.
- Do not call ScalaMunch tools if `scala-index://stats` shows 0 symbols.
- Index may lag source edits. Prompt user to run `bin/scala-munch build` or use watch mode for freshness.
