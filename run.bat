@echo off
setlocal

REM InsightKB one-click start script.
REM Reads secrets from .env.local (git-ignored) and starts Spring Boot.
REM Requires: java and mvn available on PATH.

if not exist ".env.local" (
    echo [ERROR] .env.local not found.
    echo Copy .env.local.example to .env.local and fill in your keys.
    exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in (".env.local") do (
    if "%%A"=="LLM_API_KEY" set "LLM_API_KEY=%%B"
    if "%%A"=="DB_PASSWORD" set "DB_PASSWORD=%%B"
)

if "%LLM_API_KEY%"=="" (
    echo [WARN] LLM_API_KEY is empty - chat will fall back to mock mode.
)

echo Starting InsightKB on http://localhost:52113 ...
call mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=52113"

endlocal
