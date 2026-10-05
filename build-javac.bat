@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =====================================================================
rem  YinwuVillagerDiscount builder   (Canvas 26.3 / Folia fork)
rem
rem  This module lives in the Van tree (Canvas plugins) and follows the Van
rem  convention: no Maven, just javac + jar.
rem
rem  API jar : the Canvas API jar if found under ..\libraries\ ,
rem            otherwise ..\..\.buildcache\paper-api.jar
rem            (this plugin only uses the Paper API, which Canvas implements)
rem  Deps    : ..\..\.buildcache\*.jar
rem            ..\..\Sur\YinwuPluginLib*\target\YinwuPluginLib-1.0.3.jar
rem  Output  : yinwu-villagerdiscount-1.0.0.jar  (module root)
rem =====================================================================

set "OUT_JAR=yinwu-villagerdiscount-1.0.0.jar"
set "CACHE=%~dp0..\..\.buildcache"
set "LIBDIR="
for /d %%D in ("%~dp0..\..\Sur\YinwuPluginLib*") do set "LIBDIR=%%~fD"
set "LIB=%LIBDIR%\target\YinwuPluginLib-1.0.3.jar"

rem ---------- 1. API jar ----------
set "API_JAR="
for /f "delims=" %%F in ('dir /b /s /o-d "%~dp0..\libraries\io\canvasmc\canvas\canvas-api\canvas-api-*.jar" 2^>nul') do (
  if not defined API_JAR set "API_JAR=%%~fF"
)
if not defined API_JAR (
  for /f "delims=" %%F in ('dir /b /s /o-d "%~dp0..\libraries\canvas-api-*.jar" 2^>nul') do (
    if not defined API_JAR set "API_JAR=%%~fF"
  )
)
if not defined API_JAR if exist "%CACHE%\paper-api.jar" set "API_JAR=%CACHE%\paper-api.jar"
if not defined API_JAR (
  echo [ERR] no API jar: put canvas-api-*.jar under Van\libraries\ or run
  echo       node ???\dl-paperapi.mjs  to fill .buildcache\
  pause
  exit /b 1
)

rem ---------- 2. javac ----------
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do if not defined JAVAC if exist "%%~fD\bin\javac.exe" set "JAVAC=%%~fD\bin\javac.exe"
if not defined JAVAC if exist "C:\Program Files\Java\jdk-25\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-25\bin\javac.exe"
if not defined JAVAC where javac.exe >nul 2>nul && set "JAVAC=javac.exe"
if not defined JAVAC ( echo [ERR] javac.exe not found. Install JDK 21+ or set JAVA_HOME. & pause & exit /b 1 )

set "JAR=jar.exe"
for %%D in ("%JAVAC%") do if not "%%~dpD"=="" set "JAR=%%~dpDjar.exe"

echo API jar : %API_JAR%
echo lib     : %LIB%
"%JAVAC%" -version
echo.

rem ---------- 3. classpath ----------
if not exist "%LIB%" ( echo [ERR] missing %LIB% & echo       build YinwuPluginLib first. & pause & exit /b 1 )
set "CP=%API_JAR%;%LIB%"
for %%F in ("%CACHE%\*.jar") do set "CP=!CP!;%%~fF"

rem ---------- 4. compile ----------
if exist out rmdir /s /q out
mkdir out
echo [1/2] compiling ...
dir /s /b "src\*.java" > "%TEMP%\yvd-srcs.txt"
"%JAVAC%" -encoding UTF-8 --release 21 -proc:none -cp "!CP!" -d out "@%TEMP%\yvd-srcs.txt"
if errorlevel 1 goto fail

rem ---------- 5. shade YinwuPluginLib ----------
rem  This plugin extends net.yinwu.lib.plugin.YinwuPlugin, so the library
rem  classes must be bundled into the jar (same effect as Sur's maven-shade).
echo [2/3] shading YinwuPluginLib ...
pushd out
"%JAR%" xf "%LIB%"
if exist "META-INF\MANIFEST.MF" del /q "META-INF\MANIFEST.MF"
popd
if errorlevel 1 goto fail

rem ---------- 6. package (classes + shaded lib + resources) ----------
echo [3/3] packaging ...
if exist "%OUT_JAR%" del /q "%OUT_JAR%"
"%JAR%" --create --file "%OUT_JAR%" -C out . -C src\main\resources .
if errorlevel 1 goto fail

echo.
echo [OK] %CD%\%OUT_JAR%
echo      Copy it to the Canvas server plugins\ folder, then restart.
pause
exit /b 0

:fail
echo.
echo [ERR] build failed.
pause
exit /b 1
