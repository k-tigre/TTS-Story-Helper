# UTF-8 console
chcp 65001 > $null
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$venvPython = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
    Write-Host "Creating venv..."
    python -m venv .venv
}
# Always sync deps (e.g. after requirements.txt adds omegaconf / PyYAML for Silero)
& (Join-Path $here ".venv\Scripts\pip.exe") install -r requirements.txt

& $venvPython -m uvicorn main:app --host 127.0.0.1 --port 8765
