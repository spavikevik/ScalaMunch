---
mode: 'agent'
description: 'Build or update the ScalaMunch symbol index for this project'
---

# ScalaMunch Index

Build or update the ScalaMunch symbol index. Run this after adding new files or when the index is stale.

## Steps

1. Identify the source root — default `src/main/scala`. Ask if ambiguous.
2. Identify DB path — default `.scala-munch.db` at project root.
3. Choose flags based on context:
   - `--force` only if user explicitly wants full rebuild
   - `--scala2` for Scala 2 source trees
   - `--no-semanticdb` if project has no compiled `.semanticdb` output
4. Run:
   ```bash
   bin/scala-munch build <root> --db .scala-munch.db [flags]
   ```
5. Report: symbols indexed, files processed, files skipped (unchanged).

## SemanticDB

If `target/` contains `.semanticdb` files (project compiled with `-Xsemanticdb` or `semanticdb-scalac`), resolved type signatures are included automatically. For best results, compile first:

```bash
sbt compile   # then reindex
bin/scala-munch build src/main/scala --db .scala-munch.db
```

## Watch Mode

For continuous indexing, run in a separate terminal — do not block the session:

```bash
# Option A: CLI watcher
bin/scala-munch watch src/main/scala --db .scala-munch.db

# Option B: sbt plugin (compile + index together)
sbt ~scalaMunchIndex
```

## Prerequisites

`bin/install.sh` must have been run. If jars are missing, run it first:

```bash
bin/install.sh
```
