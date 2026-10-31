@echo off
rem Talk to Ronie with this laptop's mic and speakers. Start start-ronie.cmd first so Ronie's tools work.
rem Add --headphones to be able to interrupt Ronie while it talks.
cd /d "%~dp0brain"
".venv\Scripts\ronie-voice.exe" %*
pause
