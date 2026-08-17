@echo off
rem
rem Launcher for the mcp-proxy fat jar.
rem
rem Nothing here may write to stdout. When an MCP client spawns the proxy, stdout carries JSON-RPC
rem frames and a single stray line corrupts the session - so every diagnostic goes to stderr, and
rem `@echo off` is unconditional. Gradle's generated start scripts open with
rem `@if "%DEBUG%"=="" @echo off`, which echoes every command to stdout for anyone who happens to
rem have DEBUG set in their environment; that is why these are hand-written.
rem
rem Jar lookup, in order: %MCP_PROXY_JAR%, a jar beside this script (the packaged layout), then
rem ..\build\libs (running straight out of a checkout).

setlocal

rem %ERRORLEVEL% resolves to an *environment variable* of that name when one exists, and only falls
rem back to the dynamic exit code when it does not. Anyone with ERRORLEVEL set in their environment
rem would otherwise get that value back from every run - a --check gating CI would report success on
rem a failed audit. Clearing it here is local to this script and restores the pseudo-variable.
set "ERRORLEVEL="

set "SCRIPT_DIR=%~dp0"

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
if not defined JAVA_CMD set "JAVA_CMD=java"

set "JAR=%MCP_PROXY_JAR%"
if not defined JAR if exist "%SCRIPT_DIR%mcp-proxy.jar" set "JAR=%SCRIPT_DIR%mcp-proxy.jar"
if not defined JAR call :find_checkout_jar

if not defined JAR (
    echo mcp-proxy: could not find mcp-proxy.jar beside this script or in ..\build\libs - set MCP_PROXY_JAR 1>&2
    exit /b 1
)
if not exist "%JAR%" (
    echo mcp-proxy: %JAR% does not exist 1>&2
    exit /b 1
)
if defined AMBIGUOUS (
    echo mcp-proxy: several jars in ..\build\libs - clean the stale ones or set MCP_PROXY_JAR 1>&2
    exit /b 1
)

rem %* forwards the arguments as cmd.exe received them. Note cmd re-parses metacharacters here, so an
rem argument containing an unquoted & or | is split - quote such arguments at the prompt.
rem ERRORLEVEL is read on its own line, so it expands after java has run.
"%JAVA_CMD%" %MCP_PROXY_JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:find_checkout_jar
for %%f in ("%SCRIPT_DIR%..\build\libs\mcp-proxy-*.jar") do call :consider_jar "%%~ff"
exit /b 0

:consider_jar
rem The plain jar task is classified -thin and has no main class, so skipping it is not cosmetic.
set "CANDIDATE_NAME=%~n1"
if /i "%CANDIDATE_NAME:~-5%"=="-thin" exit /b 0
rem shadowJar never cleans, so build/libs accumulates jars across version bumps. Picking whichever
rem the glob yields last would silently run yesterday's build; refuse instead.
if defined JAR set "AMBIGUOUS=1"
set "JAR=%~f1"
exit /b 0
