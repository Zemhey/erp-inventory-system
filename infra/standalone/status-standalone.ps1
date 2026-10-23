# ============================================================================
# 单机版状态检查（PowerShell 5.1）
#
# 输出：容器状态、/healthz 与 /readyz、管理员、数据卷、最近备份。
# 退出码：0 = /readyz 200；1 = 未就绪/未初始化。
#
# 用法：.\infra\standalone\status-standalone.ps1
# ============================================================================
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\standalone-common.ps1')

Test-DockerReady
if (-not (Test-Path $script:EnvFile)) {
    Write-Host "单机版尚未初始化：$($script:EnvFile) 不存在。"
    Write-Host "首次启动：.\infra\standalone\start-standalone.ps1"
    exit 1
}
Load-DotEnv -Path $script:EnvFile

Write-Step "容器状态"
Invoke-Compose -Arguments @('ps')

$port = Get-WebPort
$healthCode = Get-HttpStatus -Url "http://127.0.0.1:$port/healthz"
$readyCode = Get-HttpStatus -Url "http://127.0.0.1:$port/readyz"

Write-Step "健康检查"
Write-Info "GET /healthz -> $healthCode"
Write-Info "GET /readyz  -> $readyCode  (http://127.0.0.1:$port/readyz)"

Write-Step "运行信息"
Write-Info "配置 IMAGE_TAG：$($env:IMAGE_TAG)（实际镜像见上方容器状态）"
Write-Info "Web 入口：http://127.0.0.1:$port/"
Write-Info "PostgreSQL：127.0.0.1:$(Get-PgPort)（仅本机）"

$pgRunning = $false
try {
    $running = Invoke-ComposeCapture -Arguments @('ps', '--status', 'running', '-q', 'postgres')
    $pgRunning = [bool]$running
} catch {
    $pgRunning = $false
}

if ($pgRunning) {
    $adminName = if ($env:ERP_ADMIN_USERNAME) { $env:ERP_ADMIN_USERNAME } else { 'admin' }
    try {
        $count = Get-AdminCount $adminName
        if ($count -eq 0) {
            Write-Info "管理员 ${adminName}：不存在"
        } else {
            Write-Info "管理员 ${adminName}：存在"
        }
    } catch {
        Write-Info "管理员状态：查询失败"
    }
}

$volumeOutput = & docker volume inspect -f '{{.Mountpoint}}' $script:DataVolume 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Info "数据卷 $($script:DataVolume)：存在"
    if ($volumeOutput) {
        Write-Info "  挂载点（Docker VM 内）：$volumeOutput"
    }
} else {
    Write-Info "数据卷 $($script:DataVolume)：不存在（首次启动时创建）"
}

$latest = @(Get-ChildItem -Path $script:BackupDir -Filter 'erp-standalone-*.dump' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1)
if ($latest.Count -gt 0) {
    Write-Info "最近备份：$($latest[0].FullName)"
} else {
    Write-Info "最近备份：无（运行 .\infra\standalone\backup-standalone.ps1）"
}

if ($readyCode -eq 200) {
    exit 0
}
exit 1
