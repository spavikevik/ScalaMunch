@echo off
setlocal
set "JAR=%~dp0..\mcp-server\target\scala-3.3.3\scala-munch-mcp-assembly.jar"
if not exist "%JAR%" (
  echo [scala-munch] MCP server jar not found. Run bin/install.sh first. 1>&2
  exit /b 1
)
java -jar "%JAR%" %*
