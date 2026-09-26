$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "env.ps1")

$apk = if ($args.Length -gt 0) { $args[0] } else { Join-Path $here "..\app\build\outputs\apk\release\app-release.apk" }
if (-not (Test-Path $apk)) {
    Write-Error "APK not found: $apk"
    exit 2
}

$buildToolsDir = Join-Path $env:ANDROID_HOME "build-tools"
$bt = Get-ChildItem -Path $buildToolsDir | Sort-Object Name | Select-Object -Last 1
$aapt2 = Join-Path $bt.FullName "aapt2.exe"

$perms = & $aapt2 dump permissions $apk
Write-Host $perms

if ($perms -match "android\.permission\.INTERNET") {
    $apkName = Split-Path $apk -Leaf
    Write-Error "FAIL: $apkName requests android.permission.INTERNET"
    exit 1
}
$apkName = Split-Path $apk -Leaf
Write-Host "OK: $apkName does not request android.permission.INTERNET"
