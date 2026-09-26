$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "env.ps1")

$src = Join-Path $here "..\tools\out\seed"
$dest = "/sdcard/Pictures/RecallSeed"

if (-not (Test-Path $src)) {
    Write-Error "Run: .venv\Scripts\python.exe tools\make_seed_docs.py"
    exit 1
}

$state = (adb get-state 2>&1)
if ($state -match "error" -or $state -match "no devices") {
    Write-Error "No device connected."
    exit 1
}

adb shell mkdir -p $dest
$files = Get-ChildItem -Path $src -Include *.jpg,*.png -File -Recurse
foreach ($f in $files) {
    adb push $f.FullName "$dest/" | Out-Null
    $basename = $f.Name
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d `"file://$dest/$basename`" 2>&1 | Out-Null
    Write-Host "pushed $basename"
}

adb shell content call --uri content://media --method scan_volume --arg external_primary 2>&1 | Out-Null
