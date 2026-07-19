@echo off
REM Double-click to build + launch the test Paper server.
REM Sets JAVA_HOME to the bundled Adoptium JDK 21 so you don't need Java
REM configured in your terminal. Edit JAVA_HOME below if your JDK path differs.

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo.
    echo [!] JDK not found at:
    echo     %JAVA_HOME%
    echo     Install JDK 21+ or edit JAVA_HOME at the top of run.bat
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0"
call gradlew.bat runServer
pause
