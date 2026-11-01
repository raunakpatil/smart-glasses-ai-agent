# Installs the Ronie laptop agent on Windows in one go. It lets the brain (on your server) open videos and pages on
# this laptop, control media and volume, and hear calls in interview mode.
#
#   Install or update (PowerShell):
#     irm https://raw.githubusercontent.com/raunakpatil/smart-glasses-ai-agent/main/install-agent.ps1 | iex
#   Or from a downloaded copy of the repo:
#     powershell -ExecutionPolicy Bypass -File install-agent.ps1
#   Remove it again:
#     & ([scriptblock]::Create((irm https://raw.githubusercontent.com/raunakpatil/smart-glasses-ai-agent/main/install-agent.ps1))) -Uninstall
#
# It asks for one thing: the same pairing code you gave the phone app. Everything goes in %LOCALAPPDATA%\Ronie\agent
# (your token stays in your own user folder), it starts at sign-in with no window, and it starts right away.
param(
    [switch]$Uninstall,
    [string]$PairingCode = "",                                   # skip the question (e.g. for scripting)
    [string]$InstallDir = "$env:LOCALAPPDATA\Ronie\agent",
    [switch]$NoStartup                                           # don't add it to Windows sign-in
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # makes downloads much faster in Windows PowerShell
$Repo = "raunakpatil/smart-glasses-ai-agent"
$StartupLink = Join-Path ([Environment]::GetFolderPath("Startup")) "Ronie agent.lnk"

function Say($text) { Write-Host "  $text" }
function Step($text) { Write-Host ""; Write-Host "> $text" -ForegroundColor Magenta }

function Stop-Agent {
    # Only the agent installed here, not one running from a developer's copy of the repo.
    Get-CimInstance Win32_Process -Filter "Name = 'python.exe' OR Name = 'ronie-brain.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$InstallDir*" -and $_.CommandLine -like "*agent*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

Write-Host ""
Write-Host "Ronie laptop agent" -ForegroundColor White

if ($Uninstall) {
    Step "Removing the agent"
    Stop-Agent
    Remove-Item $StartupLink -Force -ErrorAction SilentlyContinue
    if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
    Say "Removed. Ronie can no longer reach this laptop."
    return
}

# ---------- 1. the pairing code ----------
Step "Your pairing code"
Say "Paste the pairing code you used for the phone app. Get it on the server with: ronie-brain pairing-code"
Say "(it starts with {""app"": ""ronie""...)"
$p = $null
for ($try = 0; $try -lt 5 -and -not $p; $try++) {
    $code = if ($PairingCode) { $PairingCode } else { Read-Host "  Pairing code" }
    $PairingCode = ""
    if (-not "$code".Trim()) { Say "Nothing pasted."; continue }
    try {
        $p = "$code".Trim() | ConvertFrom-Json
        if ($p.app -ne "ronie" -or -not $p.token) { throw "not a Ronie code" }
    } catch { $p = $null; Say "That isn't a Ronie pairing code. Copy the whole line and try again." }
}
if (-not $p) { throw "No pairing code. Run this again when you have it." }
$Url = if ($p.url) { "$($p.url)".TrimEnd("/") } else { "ws://$($p.host):$($p.port)" }
Say "Server: $Url"

# ---------- 2. Python ----------
Step "Checking Python"
function Find-Python {
    foreach ($c in @(
        @{ exe = "py"; args = @("-3") },
        @{ exe = "python"; args = @() },
        @{ exe = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"; args = @() },
        @{ exe = "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe"; args = @() }
    )) {
        try {
            $ok = & $c.exe @($c.args + @("-c", "import sys; print(sys.version_info >= (3, 11))")) 2>$null
            if ($LASTEXITCODE -eq 0 -and "$ok".Trim() -eq "True") { return $c }
        } catch { }
    }
    return $null
}
$py = Find-Python
if (-not $py) {
    Say "Python 3.11+ not found. Installing Python 3.12 for this user (winget)..."
    winget install -e --id Python.Python.3.12 --scope user --silent --accept-package-agreements --accept-source-agreements | Out-Null
    $py = Find-Python
    if (-not $py) { throw "Couldn't install Python. Install Python 3.12 from python.org, then run this again." }
}
Say ("Using " + (& $py.exe @($py.args + @("--version"))))

# ---------- 3. the agent's code ----------
Step "Getting the agent"
Stop-Agent
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$src = Join-Path $InstallDir "brain"
$local = if ($PSScriptRoot) { Join-Path $PSScriptRoot "brain" } else { "" }
if ($local -and (Test-Path (Join-Path $local "pyproject.toml"))) {
    Say "From this copy of the repo"
    if (Test-Path $src) { Remove-Item $src -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $src | Out-Null
    Get-ChildItem $local -Force | Where-Object { $_.Name -notin @(".venv", "__pycache__", ".pytest_cache", "tests") } |
        Copy-Item -Destination $src -Recurse -Force
} else {
    Say "Downloading from github.com/$Repo"
    $zip = Join-Path $env:TEMP "ronie-agent.zip"
    $unz = Join-Path $env:TEMP "ronie-agent"
    Invoke-WebRequest "https://github.com/$Repo/archive/refs/heads/main.zip" -OutFile $zip -UseBasicParsing
    if (Test-Path $unz) { Remove-Item $unz -Recurse -Force }
    Expand-Archive $zip $unz -Force
    if (Test-Path $src) { Remove-Item $src -Recurse -Force }
    Move-Item (Join-Path (Get-ChildItem $unz | Select-Object -First 1).FullName "brain") $src
    Remove-Item $zip, $unz -Recurse -Force -ErrorAction SilentlyContinue
}

Step "Installing (a minute or two the first time)"
$venv = Join-Path $InstallDir ".venv"
if (-not (Test-Path (Join-Path $venv "Scripts\python.exe"))) { & $py.exe @($py.args + @("-m", "venv", $venv)) }
$vpy = Join-Path $venv "Scripts\python.exe"
& $vpy -m pip install --quiet --upgrade pip 2>$null | Out-Null
& $vpy -m pip install --quiet $src
if ($LASTEXITCODE -ne 0) { throw "Installing the agent failed (pip). Check your internet connection and run this again." }
Say "Installed"

# ---------- 4. settings and the launcher ----------
Step "Setting it up"
# The token is private: it lives here, in your own user folder, and nowhere else.
Set-Content -Path (Join-Path $InstallDir "agent.env") -Encoding ASCII -Value @("RONIE_URL=$Url", "RONIE_TOKEN=$($p.token)")
$log = Join-Path $InstallDir "agent.log"
$vbs = Join-Path $InstallDir "start-agent.vbs"
Set-Content -Path $vbs -Encoding ASCII -Value @"
' Starts the Ronie laptop agent with no window. Settings: agent.env next to this file. Log: agent.log.
Set shell = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
here = fs.GetParentFolderName(WScript.ScriptFullName)
url = ""
Set env = fs.OpenTextFile(here & "\agent.env", 1)
Do Until env.AtEndOfStream
    line = Trim(env.ReadLine)
    If Left(line, 10) = "RONIE_URL=" Then url = Mid(line, 11)
    If Left(line, 12) = "RONIE_TOKEN=" Then shell.Environment("PROCESS")("RONIE_TOKEN") = Mid(line, 13)
Loop
env.Close
cmd = "cmd /c """"" & here & "\.venv\Scripts\ronie-brain.exe"" agent --url " & url & " > """ & here & "\agent.log"" 2>&1"""
shell.Run cmd, 0, False
"@
if (-not $NoStartup) {
    $ws = New-Object -ComObject WScript.Shell
    $lnk = $ws.CreateShortcut($StartupLink)
    $lnk.TargetPath = "$env:WINDIR\System32\wscript.exe"
    $lnk.Arguments = """$vbs"""
    $lnk.WorkingDirectory = $InstallDir
    $lnk.Description = "Ronie laptop agent"
    $lnk.Save()
    Say "Starts by itself when you sign in to Windows"
}

# ---------- 5. start it and check ----------
Step "Starting"
Remove-Item $log -Force -ErrorAction SilentlyContinue
Start-Process "$env:WINDIR\System32\wscript.exe" -ArgumentList """$vbs"""
$connected = $false
for ($i = 0; $i -lt 20 -and -not $connected; $i++) {
    Start-Sleep -Milliseconds 750
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern "connected to" -Quiet)) { $connected = $true }
}
Write-Host ""
if ($connected) {
    Write-Host "  Done. Ronie can now use this laptop." -ForegroundColor Green
    Say "Try: ""Ronie, play lofi on my laptop"". Pick your browser in the app: Devices > Laptop."
} else {
    Write-Host "  Installed, but it hasn't reached your server yet." -ForegroundColor Yellow
    Say "It keeps retrying in the background. Check the server is running, and see $log"
}
Say "To remove it later, see docs/SETUP.md (Optional: your Windows laptop)."
Write-Host ""
