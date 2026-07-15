@echo off
setlocal
set "JAR=%~dp0..\cli\target\scala-3.3.3\scala-munch-cli-assembly.jar"
if not exist "%JAR%" (
  echo [scala-munch] CLI jar not found. Run bin/install.sh first. 1>&2
  exit /b 1
)
java -jar "%JAR%" %*
