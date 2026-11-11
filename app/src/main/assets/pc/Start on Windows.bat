@echo off
rem Sticker Search: describes the stickers in this folder with a model on this PC.
cd /d "%~dp0"
title Sticker Search - describing stickers
where py >nul 2>nul
if %errorlevel%==0 (
    py -3 describe.py
    goto :eof
)
where python >nul 2>nul
if %errorlevel%==0 (
    python describe.py
    goto :eof
)
echo Python 3 isn't installed on this PC.
echo Install it from https://www.python.org/downloads/ (tick "Add python.exe to PATH"),
echo then double-click "Start on Windows.bat" again.
echo.
pause
