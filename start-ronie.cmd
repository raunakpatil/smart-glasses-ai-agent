@echo off
rem Starts the Ronie brain server from any folder. Double-click it, or run it from a terminal.
cd /d "%~dp0brain"
".venv\Scripts\ronie-brain.exe" %*
pause
