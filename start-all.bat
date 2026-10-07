@echo off
setlocal
cd /d D:\dev\projects\insightkb

REM ============================================================
REM InsightKB one-click starter: Redis first, then Spring Boot.
REM Run this after a reboot. Redis path has no Chinese chars,
REM so it is safe here. The app relies on `mvn` being on PATH
REM (same assumption as run.bat); if `mvn` is missing, add Maven
REM to PATH or use the bash commands from the chat instead.
REM ============================================================

set REDIS_EXE=D:\dev\redis\bin\redis-server.exe
set REDIS_CONF=D:\dev\redis\redis.conf
set REDIS_CLI=D:\dev\redis\bin\redis-cli.exe

REM 1) Start Redis in a minimized window (skip if already running)
tasklist /fi "imagename eq redis-server.exe" | find /i "redis-server" >nul
if errorlevel 1 (
    echo [INFO] Starting Redis...
    start "" /min "%REDIS_EXE%" "%REDIS_CONF%"
) else (
    echo [INFO] Redis already running, skip.
)

REM 2) Wait for Redis to be ready (up to ~15s)
set /a tries=0
:waitredis
"%REDIS_CLI%" -h 127.0.0.1 -p 6379 ping 2>nul | find "PONG" >nul
if not errorlevel 1 goto redisok
set /a tries+=1
if %tries% geq 15 (
    echo [WARN] Redis not ready after 15s, continuing anyway.
    goto redisok
)
timeout /t 1 >nul
goto waitredis
:redisok

REM 3) Load secrets from .env.local and start InsightKB
if not exist ".env.local" (
    echo [ERROR] .env.local not found. Copy .env.local.example to .env.local first.
    pause
    exit /b 1
)
for /f "usebackq tokens=1,* delims==" %%A in (".env.local") do (
    if "%%A"=="LLM_API_KEY" set "LLM_API_KEY=%%B"
    if "%%A"=="DB_PASSWORD" set "DB_PASSWORD=%%B"
)
echo [INFO] Starting InsightKB on http://localhost:52113 ...
call mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=52113"
endlocal
