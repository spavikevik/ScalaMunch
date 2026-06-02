---
mode: 'agent'
description: 'Assemble token-budget-aware type context for a Scala symbol using ScalaMunch'
---

# ScalaMunch Context

Assemble a type context for one or more Scala symbols from the ScalaMunch index. Answers: "what do I need to understand to work with X?"

## Input

Provide a symbol name or FQN. Examples:
- Partial name: `Decoder`, `Functor`, `HttpRoutes`
- Full FQN: `io/circe/Decoder#`, `cats/Functor#`
- Type shape: `A => F[B]` (triggers `search_by_type` instead)

Optionally provide a token budget (default: 500).

## Steps

1. **Resolve FQN** — if partial name given, call `search_symbols`. Pick the most relevant result. Confirm if ambiguous.

2. **Primary context** — call `get_type_context` on the FQN. Returns all types the symbol directly depends on.

3. **Implicit/given context** (traits and typeclasses only) — call `get_implicits_for`. Returns instances in the index.

4. **Pack within budget** — collect all FQNs from steps 2–3. Call `expand_context` with:
   ```json
   { "fqns": [...], "token_budget": 500 }
   ```

5. **Present result** grouped:
   - Primary symbol (sig + doc)
   - Direct type dependencies (sig)
   - Implicit/given instances (sig)
   - Symbols cut by budget: list FQNs only, offer to expand

## Token Budgets

| Scenario | Budget |
|----------|--------|
| Quick check | 200 |
| Implementation context (default) | 500 |
| Deep dependency trace | 1000 |
| Never exceed | 2000 |

## Boundaries

- If symbol not found in index, fall back to reading the source file.
- Skip the implicits step for methods, case classes, and objects — it only applies to traits and typeclasses.
- If budget would exceed 2000 tokens from index tools alone, switch to file reads for the remainder.
