@echo off
title MoYue Reader
cd /d "%~dp0"

rem Try python first, then the py launcher
where python >nul 2>nul
if %errorlevel%==0 (
    python moyue_desktop.py
    goto end
)

where py >nul 2>nul
if %errorlevel%==0 (
    py -3 moyue_desktop.py
    goto end
)

echo [ERROR] Python not found.
echo Please install Python 3 from https://www.python.org/downloads/
echo Remember to check "Add Python to PATH" during install.
pause
exit /b 1

:end
rem Keep the window open if the server exited with an error
if errorlevel 1 pause
