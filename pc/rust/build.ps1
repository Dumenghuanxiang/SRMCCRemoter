$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Cargo = (Get-Command cargo -ErrorAction Stop).Source
$null = Get-Command windres -ErrorAction Stop

Push-Location $ProjectRoot
try {
    & $Cargo test --all-features --release --locked
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $Cargo build --release --locked
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $Cargo build --release --features cli-tools --locked
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $ReleaseDirectory = Join-Path $ProjectRoot "target\release"
    $Executable = Join-Path $ReleaseDirectory "srm-xbox.exe"
    $Tools = Join-Path $ReleaseDirectory "srm-xbox-tools.exe"
    & $Tools --smoke-test (Join-Path $ProjectRoot "smoke-report.json")
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Packaged executable smoke test failed."
        exit $LASTEXITCODE
    }

    $Upx = (Get-Command upx -ErrorAction SilentlyContinue).Source
    if ($Upx) {
        foreach ($Binary in @($Executable, $Tools)) {
            & $Upx --lzma $Binary
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            & $Upx --test $Binary
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
    } else {
        Write-Warning "UPX was not found on PATH; binaries remain uncompressed."
    }

    Get-Item -LiteralPath $Executable, $Tools |
        Select-Object Name, Length, LastWriteTime
} finally {
    Pop-Location
}
