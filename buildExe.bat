@echo off
REM Kill any running PhotonLab instances so they don't lock the exe
taskkill /IM "photonlab-1.2.2.exe" /F >nul 2>&1

REM Stop the Gradle daemon so it releases any directory handles
set JAVA_HOME=C:\Program Files\Zulu\zulu-21
call gradlew.bat --stop >nul 2>&1

REM Delete the previous exe output dir BEFORE Gradle opens it
rmdir /s /q "desktop\build\compose-out\main\exe" 2>nul

REM Build — exe dir no longer exists so Gradle's stale-output cleanup is a no-op
call gradlew.bat :desktop:packageExe
