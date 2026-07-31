@echo off
cd /d "%~dp0"
echo Starting Lyrenne...
call gradlew.bat :desktop:run
pause
