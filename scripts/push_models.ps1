$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "env.ps1")

$src = if ($args.Length -gt 0) { $args[0] } else { Join-Path $here "..\models" }
$dest = "/sdcard/Android/data/$env:APP_ID/files/models"

$state = (adb get-state 2>&1)
if ($state -match "error" -or $state -match "no devices") {
    Write-Error "No device: connect the phone with USB debugging on."
    exit 1
}

adb shell mkdir -p $dest

$manifestPath = [System.IO.Path]::GetTempFileName()
$manifest = @{ files = @{} }

$models = @("siglip2_vision.tflite", "nomic_embed_text.tflite", "qwen3_4b_instruct_2507", "qwen3_1_7b")
foreach ($name in $models) {
    $path = Join-Path $src $name
    if (-not (Test-Path $path)) {
        Write-Host "skip $name (not in $src)"
        continue
    }
    
    $bytes = 0
    if ((Get-Item $path) -is [System.IO.DirectoryInfo]) {
        $files = Get-ChildItem -Path $path -Recurse -File
        foreach ($f in $files) { $bytes += $f.Length }
    } else {
        $bytes = (Get-Item $path).Length
    }
    
    Write-Host "push $name ($bytes bytes)"
    adb push $path "$dest/" | Out-Null
    $manifest.files[$name] = $bytes
}

$manifest | ConvertTo-Json -Depth 3 | Out-File -Encoding UTF8 $manifestPath
adb push $manifestPath "$dest/manifest.json" | Out-Null
Remove-Item $manifestPath
adb shell ls -la $dest
