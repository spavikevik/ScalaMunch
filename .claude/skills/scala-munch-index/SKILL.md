# ScalaMunch Index

Build or update the ScalaMunch symbol index for the current Scala project.

## Trigger

`/scala-munch-index [path] [--force] [--scala2] [--watch]`

Also invoke when user says: "index the project", "build the scala-munch index", "update the index", "reindex".

## Process

1. **Identify source root** — use argument if given, else default to `src/main/scala`. Confirm with user if ambiguous.

2. **Identify DB path** — default `.scala-munch.db` at project root. Override with `--db <path>`.

3. **Choose flags:**
   - `--force` if user wants full rebuild (not just changed files)
   - `--scala2` for Scala 2 source trees
   - `--no-semanticdb` if project has no SemanticDB output (uncompiled)

4. **Run** (use Bash tool):
   ```bash
   bin/scala-munch build <root> --db .scala-munch.db [flags]
   ```

5. **Report** stats from output: symbols indexed, files, skipped (unchanged).

6. **SemanticDB note:** if `target/` contains `.semanticdb` files (project was compiled with SemanticDB), resolved types are picked up automatically. Prompt user to compile first for best results.

## Watch Mode

For continuous indexing, prefer a separate terminal:
```bash
bin/scala-munch watch src/main/scala --db .scala-munch.db
```

Or via sbt plugin:
```bash
sbt ~scalaMunchIndex
```

Do NOT run watch mode inline — it blocks indefinitely.

## Prerequisites

`bin/install.sh` must have been run (assembly jars must exist). If jars are missing, instruct user to run `bin/install.sh` first.

## Boundaries

- If source root doesn't exist, ask before proceeding.
- Skip `--force` unless explicitly requested — incremental (digest-based) is correct by default.
- Do not start watch mode unless user explicitly asks for continuous indexing.
