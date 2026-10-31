@echo off
rem Starts the Ronie laptop agent: Ronie's brain runs on the cloud server, and this lets it open sites and apps,
rem press media keys and hear calls on this laptop. Double-click it and leave the window open.
rem The server's address comes from cloud.env (copy cloud.env.example).
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0cloud.env") do set "%%a=%%b"
cd /d "%~dp0brain"
".venv\Scripts\ronie-brain.exe" agent --url %RONIE_URL% %*
pause
