# ScalaMunch

Type-driven Scala symbol index accessible via MCP tools. Use these tools instead of reading source files to get surgical symbol context at a fraction of the token cost.

## Trigger

Auto-apply when:
- User asks about a Scala type, class, trait, method, or implicit
- Implementing code that requires understanding a type's API
- Resolving `given`/`implicit` instances for a typeclass
- Tracing type dependencies across modules

Also invoke explicitly via `/scala-munch-index` and `/scala-munch-context`.

## Tool Selection

| Goal | Tool | Key args |
|------|------|----------|
| Signature of known symbol | `get_symbol` | `fqn`, `detail: "sig"` |
| Signature + scaladoc | `get_symbol` | `fqn`, `detail: "sig+doc"` |
| All types to understand a symbol | `get_type_context` | `fqn` |
| Search by name (partial OK) | `search_symbols` | `query` |
| Find by return type or shape | `search_by_type` | `signature` |
| `given`/`implicit` instances for a type | `get_implicits_for` | `type_fqn` |
| Where is a symbol used? | `find_references` | `fqn` |
| Multiple symbols within token budget | `expand_context` | `fqns[]`, `token_budget` |

## FQN Format

ScalaMunch uses SemanticDB FQN notation:

| Kind | Pattern | Example |
|------|---------|---------|
| Trait/class | `pkg/Name#` | `io/circe/Decoder#` |
| Object | `pkg/Name.` | `io/circe/Decoder.` |
| Method | `pkg/Name#method().` | `io/circe/Decoder#apply().` |
| Package | `pkg/` | `cats/` |

When FQN is unknown, call `search_symbols` first, then use the returned `fqn` field.

## Detail Levels

Choose the cheapest level that answers the question:

| Level | Content | ~Tokens | When |
|-------|---------|---------|------|
| `sig` | Signature only | 20–50 | Default — name + type shape |
| `sig+doc` | Sig + scaladoc | 50–200 | Behavior unclear from sig |
| `type-ctx` | Sig + all resolved deps | 100–500 | Before implementing against a type |
| `full` | Body + type-ctx + implicits | 500–2k | Avoid; use `type-ctx` instead |

## Decision Flow

Before reading any `.scala` source file:

1. Check index is populated: `scala-index://stats` resource
2. `search_symbols` if FQN unknown
3. `get_symbol` with `detail: "sig"` for initial understanding
4. `get_type_context` if dependent types needed
5. `get_implicits_for` if typeclass instances needed
6. `expand_context` to pack multiple FQNs within a budget

Only fall back to file reads if the symbol is not in the index.

## Resources

| URI | Use |
|-----|-----|
| `scala-index://stats` | Verify index is populated before querying |
| `scala-index://symbols` | Paginated full symbol listing |

## Boundaries

- `find_references` requires SemanticDB (project must be compiled). Skip if unavailable.
- `expand_context` token budget is approximate — actual output may be slightly less.
- Index may lag source edits. Use watch mode (`bin/scala-munch watch`) or the sbt plugin for freshness.
- Do not call ScalaMunch tools if `scala-index://stats` shows 0 symbols — index not built yet.
