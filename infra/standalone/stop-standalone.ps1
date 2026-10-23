# ============================================================================
# 单机版停止（PowerShell 5.1）
#
# 默认：停止并删除容器/网络，保留数据卷与备份。
# -RemoveData：连同数据卷 erp-standalone-pgdata 一起删除（不可恢复，需 -Yes）。
#
# 用法：
#   .\infra\standalone\stop-standalone.ps1
#   .\infra\standalone\stop-standalone.ps1 -RemoveData -Yes
# ============================================================================
[CmdletBinding()]
param(
    [switch]$RemoveData,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\standalone-common.ps1')

Test-DockerReady
if (-not (Test-Path $script:EnvFile)) {
    Die "找不到 $($script:EnvFile)；单机版尚未初始化"
}
Load-DotEnv -Path $script:EnvFile

if ($RemoveData -and -not $Yes) {
    Write-Host "这会删除数据卷 $script:DataVolume 及全部业务数据（备份目录不受影响）。"
    $answer = Read-Host "确认请输入 yes"
    if ($answer -ne 'yes') {
        Die "已取消"
    }
}

if ($RemoveData) {
    Write-Step "停止单机版并删除数据卷"
    Invoke-Compose -Arguments @('down', '-v', '--remove-orphans')
    Write-Info "已删除容器、网络与数据卷 $script:DataVolume"
    Write-Info "备份目录未删除：$script:BackupDir"
} else {
    Write-Step "停止单机版（保留数据）"
    Invoke-Compose -Arguments @('down', '--remove-orphans')
    Write-Info "容器与网络已删除；数据卷 $script:DataVolume 保留"
    Write-Info "下次启动：.\infra\standalone\start-standalone.ps1"
}
