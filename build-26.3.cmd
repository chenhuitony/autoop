@echo off
REM ---------------------------------------------------------------
REM  AutoOp - one-click build for Paper 26.3
REM  Double-click this file, or run it from a terminal.
REM  It calls the already-verified build-javac.ps1 script.
REM ---------------------------------------------------------------

setlocal EnableExtensions

set "SCRIPTDIR=%~dp0"
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "SERVERDIR=E:\MC\paper\26.3"
set "OUTNAME=AutoOp-1.0.0-26.3.jar"
set "EXPECTED=%SCRIPTDIR%target\%OUTNAME%"
set "PLUGINS=%SERVERDIR%\plugins"

echo ============================================================
echo  AutoOp  -  build against Paper 26.3
echo ============================================================
echo.
echo  script dir : %SCRIPTDIR%
echo  server dir : %SERVERDIR%
echo  output     : target\%OUTNAME%
echo.

if not exist "%PS%" goto nops
if not exist "%SERVERDIR%\libraries\" goto nolibs

echo [..] running build-javac.ps1 ...
echo.

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPTDIR%build-javac.ps1" -ServerDir "%SERVERDIR%" -OutputName "%OUTNAME%"
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" goto buildfail
if not exist "%EXPECTED%" goto nojar

set "SIZE=0"
for %%F in ("%EXPECTED%") do set "SIZE=%%~zF"
echo [OK] built: %EXPECTED%  (%SIZE% bytes)
echo.

if not exist "%PLUGINS%\" goto noplugins

copy /y "%EXPECTED%" "%PLUGINS%\" >nul
if errorlevel 1 goto copyfail

echo [OK] copied into %PLUGINS%
echo      Restart the Paper 26.3 server ^(do NOT use /reload^)
goto done

:nops
echo [FAIL] powershell.exe not found at %PS%
goto done

:nolibs
echo [FAIL] libraries folder not found: %SERVERDIR%\libraries\
echo        Start the Paper 26.3 server once so it unpacks libraries.
goto done

:buildfail
echo [FAIL] build-javac.ps1 exited with %RC%
echo        Copy the full error text above and send it to the agent.
goto done

:nojar
echo [FAIL] exit code was 0 but the jar is missing:
echo        %EXPECTED%
goto done

:noplugins
echo [..] plugins folder not found at %PLUGINS% - copy the jar manually.
goto done

:copyfail
echo [WARN] could not copy into %PLUGINS%
goto done

:done
echo.
pause
endlocal