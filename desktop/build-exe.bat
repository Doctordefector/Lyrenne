@echo off
echo Building Metrolist Desktop EXE...
echo.

cd /d "%~dp0.."
call gradlew.bat :desktop:packageExe

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo   BUILD SUCCESSFUL!
    echo ========================================
    echo.
    echo EXE installer location:
    echo   desktop\build\compose\binaries\main\exe\
    echo.
    explorer "desktop\build\compose\binaries\main\exe"
) else (
    echo.
    echo BUILD FAILED!
)

pause
