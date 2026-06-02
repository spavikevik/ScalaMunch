# ScalaMunch Refactor

Assemble the full blast radius of a refactoring target — forward deps AND reverse callers — in one batch before touching any code. Prevents the sequential "discover → look up → discover…" inference loop that makes symbol-level retrieval expensive.

## Trigger

`/scala-munch-refactor <name-or-fqn>`

Also invoke when user says: "refactor X", "rename X", "change the signature of X", "remove X", "move X to another package", "what will break if I change X?".

## CRITICAL RULE

**Do not make any code changes until steps 1–4 are complete and the impact surface is presented to the user.**

Every code-change session that skips this step risks missing callers and producing a broken refactor. Output tokens spent on sequential lookups are more expensive than the 3 parallel tool calls here.

## Process

All three steps are independent — run 1, 2, 3 in parallel.

**Step 1 — Resolve FQN**
If partial name given, call `search_symbols` first. Use the most specific match. Confirm with user if ambiguous.

**Step 2 — Forward deps (parallel)**
`get_type_context(<fqn>)` — all types this symbol depends on. These must be consistent after the refactor.

**Step 3 — Reverse callers (parallel)**
`find_references(<fqn>)` — all symbols that call or reference this symbol. These will need updating.

> Note: `find_references` requires SemanticDB (compiled project). If it returns empty, warn the user: "compile with `sbt compile` (Scala 3: `-Xsemanticdb`) for complete caller data. Proceeding with partial data."

**Step 4 — Batch context assembly**
Collect all FQNs from steps 2 and 3. Call:
```
expand_context(fqns=[<all collected>], token_budget=2000)
```
This is the single most important call — it packs the complete blast radius into one response without further reasoning steps.

**Step 5 — Present impact surface**
Before any edits, output:

```
## Refactor impact: <symbol>

### Forward dependencies (<N> symbols)
<list from get_type_context — must stay consistent>

### Reverse callers (<N> symbols)
<list from find_references — will need updating>

### Packed context
<expand_context output>

---
Proceed? (y/n)
```

**Step 6 — Only then, make changes**
Work through callers systematically. Update forward deps if the signature changes. Run `sbt compile` after to catch anything SemanticDB missed.

## Token Budget

Default `expand_context` budget: 2000 tokens.
- Small refactor (< 5 callers): 500 tokens
- Medium (5–20 callers): 1000 tokens
- Large (20+ callers): 2000 tokens, warn user

If budget would exceed 2000, list cut FQNs explicitly — do not silently truncate.

## Boundaries

- Never start editing before step 5 is shown to the user.
- If `find_references` returns nothing AND project is uncompiled, say so — the caller list is incomplete.
- If the symbol has 50+ callers, stop and ask the user whether to proceed. This is a large refactor.
- Do not use `get_call_graph` for refactoring — it only shows forward edges and triggers sequential traversal. Use `find_references` + `get_type_context` instead.
