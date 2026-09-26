$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dest = Join-Path $here "..\app\src\main\assets\tessdata"
if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Force -Path $dest | Out-Null }

function Fetch-Tessdata($repo, $lang) {
    $out = Join-Path $dest "$lang.traineddata"
    $metaUrl = "https://api.github.com/repos/tesseract-ocr/$repo/contents/$lang.traineddata"
    $meta = Invoke-RestMethod -Uri $metaUrl
    $sha = $meta.sha
    $rawUrl = "https://raw.githubusercontent.com/tesseract-ocr/$repo/main/$lang.traineddata"
    Invoke-WebRequest -Uri $rawUrl -OutFile $out
    
    # Verify hash (git blob hash)
    $data = [System.IO.File]::ReadAllBytes($out)
    $prefix = [System.Text.Encoding]::ASCII.GetBytes("blob $($data.Length)`0")
    $blob = new-object byte[] ($prefix.Length + $data.Length)
    [System.Array]::Copy($prefix, 0, $blob, 0, $prefix.Length)
    [System.Array]::Copy($data, 0, $blob, $prefix.Length, $data.Length)
    
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    $hashBytes = $sha1.ComputeHash($blob)
    $got = ($hashBytes | ForEach-Object { $_.ToString("x2") }) -join ""
    
    if ($got -ne $sha) {
        Write-Error "hash mismatch for $lang ($repo): $got != $sha"
        exit 1
    }
    $length = (Get-Item $out).Length
    Write-Host "OK $lang.traineddata ($repo, $length bytes)"
}

Fetch-Tessdata "tessdata_best" "tel"
Fetch-Tessdata "tessdata_fast" "eng"
Fetch-Tessdata "tessdata_fast" "hin"
