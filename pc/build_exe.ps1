$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = Join-Path $ProjectRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $Python)) {
    py -m venv (Join-Path $ProjectRoot ".venv")
}

& $Python -m pip install -r (Join-Path $ProjectRoot "requirements-build.txt")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Push-Location $ProjectRoot
try {
    & $Python -m PyInstaller --noconfirm --clean "SRMXbox.spec"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $Executable = Join-Path $ProjectRoot "dist\SRMXbox.exe"
    $SmokeReport = Join-Path $ProjectRoot "smoke-report.json"
    $Smoke = Start-Process -FilePath $Executable `
        -ArgumentList @("--smoke-test", $SmokeReport) -PassThru -Wait
    if ($Smoke.ExitCode -ne 0) {
        Write-Error "Packaged executable smoke test failed. See $SmokeReport"
        exit $Smoke.ExitCode
    }
    Write-Host "Built and verified: $Executable"
} finally {
    Pop-Location
}
