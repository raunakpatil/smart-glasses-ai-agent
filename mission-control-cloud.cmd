@echo off
rem Opens mission control of the Ronie brain on the cloud server, through a private SSH tunnel (it isn't on the
rem internet). Keep this window open while you use it; close it when you're done. The server comes from cloud.env.
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%~dp0cloud.env") do set "%%a=%%b"
start "" http://127.0.0.1:8791/
ssh -i "%USERPROFILE%\.ssh\ronie-oracle" -N -L 8791:127.0.0.1:8790 %RONIE_SERVER%
