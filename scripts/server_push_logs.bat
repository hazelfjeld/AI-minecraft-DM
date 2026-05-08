@echo off
setlocal

cd /d "%~dp0.."

echo [AIDM] Staging server event logs...
if not exist data\events\*.jsonl (
  echo [AIDM] No JSONL event logs found yet.
  exit /b 0
)

git add data\events\*.jsonl

git diff --cached --quiet
if not errorlevel 1 (
  echo [AIDM] No new event log changes to push.
  exit /b 0
)

git commit -m "Update Minecraft event logs"
if errorlevel 1 exit /b %errorlevel%

git push
exit /b %errorlevel%
