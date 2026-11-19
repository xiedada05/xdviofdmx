@echo off
rem xdviofdmx - XDV to OFD launcher for Windows (usable as XeTeX -output-driver).
rem
rem   xelatex -output-driver=xdviofdmx.cmd doc.tex
rem   xdviofdmx [options] doc.xdv
rem
rem Environment: XDVIOFDMX_JAR (jar path), JAVA_HOME (Java 17+), JAVA_OPTS,
rem              XDVIOFDMX_CP (extra classpath, semicolon-separated; mirrors the
rem              Linux classpath-file mode of build-deb.sh installs).
setlocal EnableExtensions

set "JAR="
if defined XDVIOFDMX_JAR (
  if exist "%XDVIOFDMX_JAR%" set "JAR=%XDVIOFDMX_JAR%"
)
set "SELF=%~dp0"
if not defined JAR if exist "%SELF%xdviofdmx.jar" set "JAR=%SELF%xdviofdmx.jar"
if not defined JAR (
  for /f "delims=" %%f in ('dir /b /o-d 2^>nul "%SELF%target\xdviofdmx-*-dist.jar"') do (
    if not defined JAR set "JAR=%SELF%target\%%f"
  )
)
if not defined JAR if exist "%ProgramFiles%\xdviofdmx\xdviofdmx.jar" set "JAR=%ProgramFiles%\xdviofdmx\xdviofdmx.jar"
if not defined JAR if exist "%ProgramData%\xdviofdmx\xdviofdmx.jar" set "JAR=%ProgramData%\xdviofdmx\xdviofdmx.jar"
if not defined JAR (
  echo xdviofdmx: cannot locate dist jar. Set XDVIOFDMX_JAR or run: mvn package 1>&2
  exit /b 2
)

set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

rem Suppress the JDK 24+ restricted-method JNI warning when the JVM supports the flag.
set "NATIVE_FLAGS="
"%JAVA_EXE%" --enable-native-access=ALL-UNNAMED -version >nul 2>&1
if not errorlevel 1 set "NATIVE_FLAGS=--enable-native-access=ALL-UNNAMED"

if defined XDVIOFDMX_CP (
  "%JAVA_EXE%" %JAVA_OPTS% %NATIVE_FLAGS% -cp "%XDVIOFDMX_CP%;%JAR%" xdviofdmx.Main %*
) else (
  "%JAVA_EXE%" %JAVA_OPTS% %NATIVE_FLAGS% -jar "%JAR%" %*
)
