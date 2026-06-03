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
echo "==> Writing Claude Code MCP tool-preference hook..."
mkdir -p "$ROOT/.claude"
if [ ! -f "$ROOT/.claude/settings.json" ]; then
  cat > "$ROOT/.claude/settings.json" << 'EOF'
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "test -f .scala-munch.db && echo 'ScalaMunch index present. PREFER MCP tools over grep/Read for Scala symbol navigation: search_symbols (not grep -r), find_references (not grep -r), get_symbol (not Read), get_package_overview/list_packages (not ls/find). Use grep only for non-symbol patterns (string literals, comments, config).'"
          }
        ]
      }
    ]
  }
}
EOF
  echo "    Wrote .claude/settings.json"
else
  echo "    .claude/settings.json already exists — skipping"
fi

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
echo "    5. Copy .claude/settings.json to any project where you use ScalaMunch."
echo "       It adds a UserPromptSubmit hook that reminds Claude to use MCP tools"
echo "       (search_symbols, find_references, get_symbol) instead of grep/Read."
echo ""
