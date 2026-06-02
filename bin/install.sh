#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."

echo "==> ScalaMunch install"

# Prerequisites
if ! command -v java &>/dev/null; then
  echo "ERROR: java not found. Install Java 11+." >&2; exit 1
fi
JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
if [[ "${JAVA_VER}" -lt 11 ]]; then
  echo "ERROR: Java 11+ required (found $JAVA_VER)." >&2; exit 1
fi
if ! command -v sbt &>/dev/null; then
  echo "ERROR: sbt not found. Install sbt 1.9+." >&2; exit 1
fi

echo "==> Building assembly jars (this takes ~2 min on first run)..."
cd "$ROOT"
sbt "cli/assembly" "mcpServer/assembly"

CLI_JAR="cli/target/scala-3.3.3/scala-munch-cli-assembly.jar"
MCP_JAR="mcp-server/target/scala-3.3.3/scala-munch-mcp-assembly.jar"

echo ""
echo "==> Done."
echo ""
echo "    CLI jar : $ROOT/$CLI_JAR"
echo "    MCP jar : $ROOT/$MCP_JAR"
echo ""
echo "==> Next steps:"
echo ""
echo "    1. Index your project:"
echo "       bin/scala-munch build <src-root> --db .scala-munch.db"
echo ""
echo "    2. Add .scala-munch.db to .gitignore"
echo ""
echo "    3. Claude Code: .mcp.json is already configured."
echo "       Reload the window: Claude Code > Reload MCP Servers"
echo ""
echo "    4. Copilot (VS Code): create .vscode/mcp.json — see README.md"
echo ""
