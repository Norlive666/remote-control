@echo off
chcp 65001 >nul
setlocal
rem Find Python: bundled portable build first, then PATH python, then py -3 / python3.
rem The py launcher may point at an uninstalled interpreter, so it goes last.
rem Keep this file ASCII-only: cmd.exe parses .bat in the OEM codepage, and
rem non-ASCII text here gets split into stray commands like '' on Chinese Windows.
set "PY="
if exist "%~dp0python\python.exe" set PY="%~dp0python\python.exe"
if not defined PY (
    where python >nul 2>nul && set "PY=python"
)
if not defined PY (
    where py >nul 2>nul && set "PY=py -3"
)
if not defined PY (
    where python3 >nul 2>nul && set "PY=python3"
)
if not defined PY (
    echo Python not found.
    echo   Install Python 3.9+ from https://www.python.org/downloads/windows/
    echo   and tick "Add python.exe to PATH", or extract a portable build so that
    echo   tools\rc-tune\python\python.exe exists.
    pause
    exit /b 1
)
%PY% -c "import sys" >nul 2>nul
if errorlevel 1 (
    echo Cannot start %PY%
    echo If Python was uninstalled or moved, run "py -0p", or reinstall Python.
    pause
    exit /b 1
)
%PY% "%~dp0start.py" %*
if errorlevel 1 pause