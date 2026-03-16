@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
cd /d %USERPROFILE%\git\simpledit
call %USERPROFILE%\git\simpledit\gradlew.bat assembleDebug
