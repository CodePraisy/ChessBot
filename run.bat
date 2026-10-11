@echo off
rem Builds and opens Praise's Chess. Double-click, or run: run.bat --dev-engine
cd /d "%~dp0"
set "JAVAC=javac"
set "JAVA=javaw"
if defined JAVA_HOME set "JAVAC=%JAVA_HOME%\bin\javac"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\javaw"
echo Compiling...
"%JAVAC%" -d bin -sourcepath src src\chessUI.java src\ai\strengthTest.java src\ai\selfPlayData.java
if errorlevel 1 (
  echo Compile failed - is JDK 25 installed and on PATH?
  pause
  exit /b 1
)
start "" "%JAVA%" -cp "bin;src" chessUI %*
