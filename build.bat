@echo off
setlocal

cd /d "%~dp0"

echo Building RemoteBox Java...
call mvn clean package
if errorlevel 1 (
    echo.
    echo Build failed.
    exit /b %errorlevel%
)

echo.
echo Build completed successfully.
echo JAR: "%CD%\target\remotebox-java-1.0.0-all.jar"
exit /b 0
