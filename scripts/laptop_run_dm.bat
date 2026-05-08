@echo off
setlocal

cd /d "%~dp0.."

echo [AIDM] Pulling latest logs from GitHub...
git pull --ff-only
if errorlevel 1 exit /b %errorlevel%

echo [AIDM] Running Python AI Dungeon Master...
python ai_dm\run_dm.py --mode mock %*
if errorlevel 1 exit /b %errorlevel%

echo [AIDM] Staging generated lore books...
if not exist content\lore_books\*.json (
  echo [AIDM] No lore book JSON files found to push.
  exit /b 0
)

git add content\lore_books\*.json

git diff --cached --quiet
if not errorlevel 1 (
  echo [AIDM] No new generated lore to push.
  exit /b 0
)

git commit -m "Generate lore books"
if errorlevel 1 exit /b %errorlevel%

git push
exit /b %errorlevel%
