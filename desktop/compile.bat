@echo off
setlocal enabledelayedexpansion

echo ========================================
echo    Metrolist Desktop Build Script
echo ========================================
echo.

cd /d "%~dp0.."

:menu
echo Choose an option:
echo   1. Run app (development)
echo   2. Build EXE installer
echo   3. Build MSI installer
echo   4. Create portable folder
echo   5. Build all packages
echo   6. Clean build
echo   0. Exit
echo.
set /p choice="Enter choice [0-6]: "

if "%choice%"=="1" goto run
if "%choice%"=="2" goto exe
if "%choice%"=="3" goto msi
if "%choice%"=="4" goto portable
if "%choice%"=="5" goto all
if "%choice%"=="6" goto clean
if "%choice%"=="0" goto end
echo Invalid choice. Try again.
echo.
goto menu

:run
echo.
echo Starting Metrolist Desktop...
call gradlew.bat :desktop:run
goto done

:exe
echo.
echo Building EXE installer...
echo This may take a few minutes...
call gradlew.bat :desktop:packageExe
if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS! EXE installer created at:
    echo   desktop\build\compose\binaries\main\exe\
    explorer "desktop\build\compose\binaries\main\exe"
) else (
    echo.
    echo BUILD FAILED. Check the error messages above.
)
goto done

:msi
echo.
echo Building MSI installer...
echo This may take a few minutes...
call gradlew.bat :desktop:packageMsi
if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS! MSI installer created at:
    echo   desktop\build\compose\binaries\main\msi\
    explorer "desktop\build\compose\binaries\main\msi"
) else (
    echo.
    echo BUILD FAILED. Check the error messages above.
)
goto done

:portable
echo.
echo Creating portable distribution...
echo This may take a few minutes...
call gradlew.bat :desktop:createDistributable
if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS! Portable folder created at:
    echo   desktop\build\compose\binaries\main\app\
    explorer "desktop\build\compose\binaries\main\app"
) else (
    echo.
    echo BUILD FAILED. Check the error messages above.
)
goto done

:all
echo.
echo Building all packages...
echo This will take several minutes...
call gradlew.bat :desktop:packageExe :desktop:packageMsi :desktop:createDistributable
if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS! All packages created at:
    echo   desktop\build\compose\binaries\main\
    explorer "desktop\build\compose\binaries\main"
) else (
    echo.
    echo BUILD FAILED. Check the error messages above.
)
goto done

:clean
echo.
echo Cleaning build files...
call gradlew.bat :desktop:clean
echo Done!
goto done

:done
echo.
echo ----------------------------------------
pause
goto menu

:end
echo.
echo Goodbye!
exit /b 0
