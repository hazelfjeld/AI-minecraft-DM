@echo off
setlocal

cd /d "%~dp0.."

echo [AIDM] Pulling latest generated content and plugin changes...
git pull --ff-only
if errorlevel 1 exit /b %errorlevel%

if not exist content\lore_books mkdir content\lore_books
echo [AIDM] Lore book JSON is available in content\lore_books.
echo [AIDM] In Minecraft, run: /aidm reloadcontent

exit /b 0
