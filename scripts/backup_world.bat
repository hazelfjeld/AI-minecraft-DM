@echo off
setlocal

cd /d "%~dp0.."

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set STAMP=%%I
set BACKUP_ROOT=backups\world_backups\%STAMP%

echo [AIDM] Creating local world backup under %BACKUP_ROOT%
mkdir "%BACKUP_ROOT%" 2>nul

if exist world (
  robocopy world "%BACKUP_ROOT%\world" /MIR
)

if exist world_nether (
  robocopy world_nether "%BACKUP_ROOT%\world_nether" /MIR
)

if exist world_the_end (
  robocopy world_the_end "%BACKUP_ROOT%\world_the_end" /MIR
)

echo [AIDM] Backup complete. Backups are intentionally ignored by Git.
exit /b 0
