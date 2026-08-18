@echo off
setlocal

cd /d "%~dp0"

set "JAR=target\remotebox-java-1.0.0-all.jar"

if not exist "%JAR%" (
    echo No built JAR was found. Building RemoteBox Java first...
    call build.bat
    if errorlevel 1 (
        echo.
        echo The application could not be started because the build failed.
        exit /b 1
    )
)

echo Starting RemoteBox Java...
java -jar "%JAR%"
