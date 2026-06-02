# ScalaMunch Context

Assemble a token-budget-aware type context from the ScalaMunch index for one or more Scala symbols. Answers "what do I need to understand to work with X?".

## Trigger

`/scala-munch-context <name-or-fqn> [budget]`

Also invoke when user says: "get context for X", "what types does X depend on", "show me the type graph for X", "what implicits does X need", "assemble context for X".

## Process

1. **Resolve FQN** — if user gave a partial name (e.g. `Decoder`), run `search_symbols` first. Pick the most relevant result. Confirm with user if ambiguous.

2. **Primary context** — call `get_type_context` on the resolved FQN. This returns all types the symbol directly depends on.

3. **Implicit context** (if trait/typeclass) — call `get_implicits_for` to find `given`/`implicit` instances in scope. Include if user is implementing against this type.

4. **Budget assembly** — collect all FQNs from steps 2–3. Call `expand_context` with the full list and `token_budget` (see below). This packs the highest-priority symbols within budget.

5. **Present result** grouped:
   - Primary symbol (sig+doc)
   - Direct type dependencies (sig)
   - Implicit/given instances (sig)
   - Anything cut by budget: list FQNs only, offer to expand

## Token Budgets

| Scenario | Budget |
|----------|--------|
| Quick signature check | 200 |
| Implementation context (default) | 500 |
| Deep dependency tracing | 1000 |
| Never exceed | 2000 |

Use 500 as default. Ask user only if they specify "quick" or "full".

## Examples

**`/scala-munch-context Decoder`**
→ search `Decoder` → get `io/circe/Decoder#` type context → get implicits → expand at 500 tokens

**`/scala-munch-context io/cats/Monad# 1000`**
→ skip search (FQN given) → type context → implicits (Monad instances) → expand at 1000 tokens

**`/scala-munch-context "A => F[B]"`**
→ interpret as type shape → `search_by_type` instead → proceed as above with top result

## Boundaries

- FQN must exist in index. If `get_symbol` returns nothing, fall back to file read.
- Budget is approximate — `expand_context` may return slightly under.
- Do not exceed 2000 tokens from ScalaMunch tools in one response — switch to file reads beyond that point.
- Implicits step only relevant for traits/typeclasses. Skip for case classes, methods, etc.
