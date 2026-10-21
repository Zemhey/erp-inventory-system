<#
.SYNOPSIS
  build-env：为含非 ASCII 字符的项目路径建立 ASCII 构建联接。

.DESCRIPTION
  背景（详见 docs/07-决策记录ADR/ADR-0010）：
  Gradle 8.9 在 Windows 上以系统 ANSI 代码页编码 test worker 的 classpath，
  当项目路径含中文时会导致所有测试类报 ClassNotFoundException
  （看起来像"类没编译"，实为编码不一致）。

  本脚本在与源目录同一盘符下建立 ASCII junction，使构建/测试可正常执行。
  **非破坏性**：不移动、不复制源码，仅建一个目录联接，可随时删除。

.PARAMETER LinkPath
  联接路径，默认 D:\erp-build。

.PARAMETER Remove
  删除已有联接（不删除源码）。

.EXAMPLE
  .\scripts\build-env.ps1
  cd D:\erp-build\backend\server; .\gradlew.bat test

.EXAMPLE
  .\scripts\build-env.ps1 -Remove
#>
[CmdletBinding()]
param(
    [string]$LinkPath = 'D:\erp-build',
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (Resolve-Path (Split-Path -Parent $ScriptDir)).Path

Write-Host '=== 构建环境检查 ===' -ForegroundColor Cyan
Write-Host "源目录: $RootDir"

# ── 删除模式 ──────────────────────────────────────────────────────────────
if ($Remove) {
    if (Test-Path $LinkPath) {
        $item = Get-Item $LinkPath -Force
        if ($item.LinkType -eq 'Junction') {
            # 只删联接本身，绝不能加 /S（那会连带删除目标内容）
            & cmd.exe /c rmdir "$LinkPath"
            Write-Host "✓ 已删除联接: $LinkPath（源码未受影响）" -ForegroundColor Green
        } else {
            Write-Warning "$LinkPath 不是目录联接，拒绝删除以免误删数据。"
        }
    } else {
        Write-Host "联接不存在: $LinkPath"
    }
    exit 0
}

# ── 判断是否需要联接 ──────────────────────────────────────────────────────
$nonAscii = $RootDir.ToCharArray() | Where-Object { [int]$_ -gt 127 }
if (-not $nonAscii) {
    Write-Host '✓ 路径为纯 ASCII，无需联接，可直接构建。' -ForegroundColor Green
    Write-Host ''
    Write-Host "  cd `"$RootDir\backend\server`"; .\gradlew.bat test"
    exit 0
}

Write-Host '' 
Write-Host '⚠ 检测到路径含非 ASCII 字符（中文）。' -ForegroundColor Yellow
Write-Host '  这是 Gradle 8.9 在 Windows 上的已知编码缺陷，会导致测试报 ClassNotFoundException。'
Write-Host '  解决：建立 ASCII 联接路径用于构建（详见 ADR-0010）。'
Write-Host ''

# ── 建联接 ────────────────────────────────────────────────────────────────
if (Test-Path $LinkPath) {
    $item = Get-Item $LinkPath -Force
    if ($item.LinkType -eq 'Junction' -and $item.Target -contains $RootDir) {
        Write-Host "✓ 联接已存在且指向正确: $LinkPath" -ForegroundColor Green
    } elseif ($item.LinkType -eq 'Junction') {
        Write-Warning "$LinkPath 已存在但指向 $($item.Target)，将重建。"
        & cmd.exe /c rmdir "$LinkPath"
        & cmd.exe /c mklink /J "$LinkPath" "$RootDir" | Out-Null
        Write-Host "✓ 联接已重建: $LinkPath -> $RootDir" -ForegroundColor Green
    } else {
        Write-Error "$LinkPath 已存在且不是联接。请换一个 -LinkPath。"
        exit 1
    }
} else {
    $parent = Split-Path -Parent $LinkPath
    if (-not (Test-Path $parent)) {
        Write-Error "父目录不存在: $parent"
        exit 1
    }
    & cmd.exe /c mklink /J "$LinkPath" "$RootDir" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "创建联接失败（需要管理员权限或已启用开发者模式）。"
        exit 1
    }
    Write-Host "✓ 联接已创建: $LinkPath -> $RootDir" -ForegroundColor Green
}

# ── 验证 ──────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '--- 验证 ---' -ForegroundColor Yellow
$checks = @(
    @{ p = "$LinkPath\backend\server\settings.gradle.kts"; d = 'Gradle 配置' },
    @{ p = "$LinkPath\backend\server\gradlew.bat";         d = 'Gradle wrapper' },
    @{ p = "$LinkPath\backend\server\src\main\java\com\erp\ErpApplication.java"; d = '源码' }
)
$ok = $true
foreach ($c in $checks) {
    if (Test-Path $c.p) { Write-Host "  ✓ $($c.d)" -ForegroundColor Green }
    else { Write-Host "  ✗ 缺失: $($c.d) -> $($c.p)" -ForegroundColor Red; $ok = $false }
}

# 端到端验证：跑一次最快的构建，确认 Gradle 能工作
if ($ok) {
    Write-Host ''
    Write-Host '--- 端到端验证（编译） ---' -ForegroundColor Yellow
    Push-Location "$LinkPath\backend\server"
    try {
        & .\gradlew.bat compileJava --quiet
        if ($LASTEXITCODE -eq 0) {
            Write-Host '  ✓ Gradle 可在联接路径下正常编译' -ForegroundColor Green
        } else {
            Write-Host '  ✗ 编译失败，请检查上面的输出' -ForegroundColor Red
            $ok = $false
        }
    } finally { Pop-Location }
}

Write-Host ''
if ($ok) {
    Write-Host '=== 就绪 ===' -ForegroundColor Cyan
    Write-Host ''
    Write-Host '所有构建/测试命令请在此路径执行：' -ForegroundColor White
    Write-Host "  cd $LinkPath\backend\server"
    Write-Host '  .\gradlew.bat test              # 运行全部测试'
    Write-Host '  .\gradlew.bat bootRun           # 启动服务'
    Write-Host '  .\gradlew.bat jacocoTestReport  # 覆盖率报告'
    Write-Host ''
    Write-Host '注意：用 IDE 打开项目时，任选源码路径或联接路径其一，'
    Write-Host '      不要同时打开两处（IDE 会识别为两个不同项目）。'
    Write-Host ''
    Write-Host "删除联接：.\scripts\build-env.ps1 -Remove"
} else {
    Write-Host '✗ 验证未全部通过，请检查上面的输出。' -ForegroundColor Red
    exit 1
}
