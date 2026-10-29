# Build the two downloadable packages (standalone / server deploy) for the ERP.
# ASCII only on purpose: Windows PowerShell 5.1 parses non-BOM UTF-8 scripts as GBK.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\make-download-bundles.ps1 -OutDir D:\MajorCacheFolder\dl
#
# Both packages carry the same tracked source (that is what a recipient needs in order
# to build the images); they differ in the front-door guide put at the zip root.
param(
    [string]$OutDir = 'D:\MajorCacheFolder\dl',
    [string]$Version = 'v1'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Write-Host "[dl] repo   = $repo"
Write-Host "[dl] outdir = $OutDir"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---- 1. file list: everything tracked, minus internal planning material ----
# core.quotePath=false is required: without it git escapes every non-ASCII path
# (启动系统.cmd, docs with Chinese names) into octal quotes, those entries then fail
# Test-Path and get silently skipped -- the zip would be missing files with no error.
#
# Console output encoding must be switched to UTF-8 as well: git emits UTF-8, and
# Windows PowerShell 5.1 decodes a child process' stdout with the OEM codepage (936/GBK),
# which turns every Chinese path into mojibake -> Test-Path fails -> 30 files vanished
# from the package with no error at all. Measured, not theoretical.
$prevConsole = [Console]::OutputEncoding
$prevGlobal = $OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$tracked = & git -C $repo -c core.quotePath=false ls-files
[Console]::OutputEncoding = $prevConsole
$OutputEncoding = $prevGlobal
if ($LASTEXITCODE -ne 0) { throw "git ls-files failed ($LASTEXITCODE)" }

$excludePrefix = @('.plan/', 'design/', 'vibe_images/')
$files = $tracked | Where-Object {
    $p = $_ -replace '\\', '/'
    -not ($excludePrefix | Where-Object { $p.StartsWith($_) })
}
Write-Host ("[dl] tracked={0}  included={1}" -f $tracked.Count, $files.Count)

# ---- 2. one staging dir, two zips (only the front-door differs) ----
$stage = Join-Path $env:TEMP 'erp-download-stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }

foreach ($pkg in @(
        @{ Name = "yunshang-erp-standalone-$Version";     Guide = 'docs/DEPLOY-standalone.md'; Front = 'README-START.md' },
        @{ Name = "yunshang-erp-server-deploy-$Version";  Guide = 'docs/DEPLOY-server.md';     Front = 'README-START.md' }
    )) {
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    $missing = @()
    foreach ($rel in $files) {
        $srcRel = $rel -replace '\\', '/'
        $from = Join-Path $repo ($srcRel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $from)) { $missing += $srcRel; continue }
        $to = Join-Path $stage ($srcRel -replace '/', '\')
        $dir = Split-Path $to -Parent
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        Copy-Item -LiteralPath $from -Destination $to -Force
    }
    # 少一个文件就不出包：静默漏文件比构建失败危险得多（收件人永远不知道少了什么）
    if ($missing.Count -gt 0) {
        $preview = ($missing | Select-Object -First 8) -join "`n  "
        throw ("有 {0} 个跟踪文件在磁盘上找不到，拒绝出包：`n  {1}" -f $missing.Count, $preview)
    }
    # both guides ship in every package; the package's own one becomes the front door
    Copy-Item -LiteralPath (Join-Path $repo 'docs\DEPLOY-standalone.md') -Destination (Join-Path $stage 'README-START-standalone.md') -Force
    Copy-Item -LiteralPath (Join-Path $repo 'docs\DEPLOY-server.md')     -Destination (Join-Path $stage 'README-START-server.md') -Force
    Copy-Item -LiteralPath (Join-Path $repo (($pkg.Guide -replace '/', '\'))) -Destination (Join-Path $stage $pkg.Front) -Force

    $zip = Join-Path $OutDir ($pkg.Name + '.zip')
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -CompressionLevel Optimal

    $bytes = (Get-Item $zip).Length
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLower()
    $count = (Get-ChildItem $stage -Recurse -File).Count
    Set-Content -Path "$zip.sha256" -Value "$hash *$($pkg.Name).zip" -Encoding ascii -NoNewline
    Write-Host ("[dl] {0}.zip  files={1}  bytes={2}  sha256={3}" -f $pkg.Name, $count, $bytes, $hash)
    Remove-Item $stage -Recurse -Force
}

Write-Host '[dl] done'
