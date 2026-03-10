@echo off
cd /d "%~dp0"
echo Starting Metrolist Desktop...
call gradlew.bat :desktop:run
pause
