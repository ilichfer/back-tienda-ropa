@echo off
setlocal enabledelayedexpansion

set MVNW_REPOURL=https://repo1.maven.org/maven2
set MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9
set WRAPPER_JAR=%~dp0\.mvn\wrapper\maven-wrapper.jar

if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    "%MAVEN_HOME%\bin\mvn.cmd" %*
    exit /b %ERRORLEVEL%
)

if not exist "%WRAPPER_JAR%" (
    echo Maven wrapper jar not found at %WRAPPER_JAR%
    exit /b 1
)

"%JAVA_HOME%\bin\java.exe" -jar "%WRAPPER_JAR%" %* 2>nul
if errorlevel 1 (
    echo Wrapper failed, falling back to IntelliJ Maven...
    "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2024.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" %*
)
endlocal
