@echo off
setlocal

cd /d "%~dp0.."

echo [AIDM] Pulling latest logs from GitHub...
git pull --ff-only
if errorlevel 1 exit /b %errorlevel%

echo [AIDM] Running Python AI Dungeon Master...
python ai_dm\run_dm.py --mode mock %*
if errorlevel 1 exit /b %errorlevel%

echo [AIDM] Staging generated content...
set HAS_GENERATED_CONTENT=
if exist content\lore_books\*.json set HAS_GENERATED_CONTENT=1
if exist content\events\*.json set HAS_GENERATED_CONTENT=1
if exist content\structures\*.json set HAS_GENERATED_CONTENT=1

if not defined HAS_GENERATED_CONTENT (
  echo [AIDM] No generated JSON content found to push.
  exit /b 0
)

if exist content\lore_books\*.json git add content\lore_books\*.json
if exist content\events\*.json git add content\events\*.json
if exist content\structures\*.json git add content\structures\*.json

git diff --cached --quiet
if not errorlevel 1 (
  echo [AIDM] No new generated lore to push.
  exit /b 0
)

git commit -m "Generate lore books"
if errorlevel 1 exit /b %errorlevel%

git push
exit /b %errorlevel%
