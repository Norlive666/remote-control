@echo off
chcp 65001 >nul
setlocal
set "PY=python"
where py >nul 2>nul && set "PY=py -3"
%PY% "%~dp0start.py" %*
if errorlevel 1 pause
