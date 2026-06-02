---
mode: 'agent'
description: 'Assemble full refactor blast radius (forward deps + reverse callers) before touching any code'
---

# ScalaMunch Refactor

Collect the complete impact surface of a refactoring target in one batch. Prevents the sequential "discover → look up → discover…" inference loop that makes symbol-level retrieval expensive on large refactors.

## Input

Provide a symbol name or FQN to refactor. Examples:
- Partial name: `AuthHandler`, `SessionManager`
- Full FQN: `com/example/auth/AuthHandler#`
- Method: `com/example/auth/AuthHandler#login().`

## CRITICAL: No code changes until step 5

Every edit session that skips impact analysis risks a broken refactor. The 3 parallel tool calls below cost far less than discovering broken callers mid-refactor.

## Steps

**1. Resolve FQN** (if partial name given)
Call `search_symbols`. Pick the most specific match. Confirm if ambiguous.

**2–3. Parallel — run both at once:**

- `get_type_context(<fqn>)` → forward dependencies (must stay consistent after refactor)
- `find_references(<fqn>)` → reverse callers (will need updating)

> If `find_references` returns empty: warn the user — compile with SemanticDB first for complete data:
> ```
> sbt compile   # Scala 3: scalacOptions += "-Xsemanticdb"
> ```
> Proceed with partial data only if user agrees.

**4. Batch context assembly**

Collect all FQNs from steps 2–3. Call once:
```
expand_context(
  fqns = [<all forward deps> + <all callers>],
  token_budget = 2000
)
```

**5. Present impact surface — required before any edits**

```
## Refactor impact: <symbol>

### Forward dependencies (N symbols)
[from get_type_context — must stay consistent]

### Reverse callers (N symbols)
[from find_references — will need updating]

### Packed context
[expand_context output]

---
Ready to proceed?
```

**6. Make changes**

Work through callers systematically. Update forward deps if signature changes. Run `sbt compile` after to catch anything the index missed.

## Token Budgets

| Caller count | Budget |
|-------------|--------|
| < 5 | 500 |
| 5–20 | 1000 |
| 20+ | 2000 — warn user first |

If 50+ callers: stop and ask the user whether to continue. This is a large refactor.

## Why not `get_call_graph`?

`get_call_graph` only shows forward edges and requires sequential traversal to build the full picture — exactly the inference loop this skill avoids. Use `find_references` (reverse) + `get_type_context` (forward) together instead.
