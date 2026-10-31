' Starts the Ronie laptop agent with no window (used at Windows sign-in). Its messages go to %USERPROFILE%\.ronie\agent.log.
' To stop it: Task Manager > Details > end the python.exe running "ronie-brain.exe agent". To turn auto-start off, delete
' "Ronie agent.vbs" from the Startup folder (Win+R, shell:startup).
Set shell = CreateObject("WScript.Shell")
here = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
logFile = shell.ExpandEnvironmentStrings("%USERPROFILE%") & "\.ronie\agent.log"
' The server's address comes from cloud.env next to this file (copy cloud.env.example).
url = ""
Set env = CreateObject("Scripting.FileSystemObject").OpenTextFile(here & "\cloud.env", 1)
Do Until env.AtEndOfStream
    line = Trim(env.ReadLine)
    If Left(line, 10) = "RONIE_URL=" Then url = Mid(line, 11)
    If Left(line, 12) = "RONIE_TOKEN=" Then shell.Environment("PROCESS")("RONIE_TOKEN") = Mid(line, 13)
Loop
env.Close
cmd = "cmd /c cd /d """ & here & "\brain"" && "".venv\Scripts\ronie-brain.exe"" agent --url " & url & " > """ & logFile & """ 2>&1"
shell.Run cmd, 0, False
