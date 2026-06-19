@echo off
setlocal

echo === SCRIPT START ===

REM === Delete old APK ===
echo Deleting old APK if it exists...
del "app\build\outputs\apk\debug\app-debug.apk" 2>nul

REM === Build APK ===
echo Running Gradle assembleDebug...
call gradle assembleDebug --console=plain > gradle.log 2>&1
IF ERRORLEVEL 1 (
    echo Build failed! See gradle_log.txt for details.
    exit /b 1
)
echo Build step completed successfully.

REM === Copy APK ===
set APK_SRC=app\build\outputs\apk\debug\app-debug.apk
set APK_DST=C:\dev\general\upload\app-debug.apk

echo Checking for APK at "%APK_SRC%"...
if exist "%APK_SRC%" (
    echo APK exists. Attempting to copy to "%APK_DST%"...
    copy "%APK_SRC%" "%APK_DST%"
    echo Copy return code: %errorlevel%
    if errorlevel 1 (
        echo ERROR: Failed to copy APK. Check permissions and path validity.
        exit /b 1
    )
    echo APK copied successfully.
) else (
    echo ERROR: APK not found at "%APK_SRC%"
    exit /b 1
)

echo === SCRIPT END ===

endlocal