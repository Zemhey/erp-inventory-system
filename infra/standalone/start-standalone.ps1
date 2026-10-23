# ============================================================================
# 单机版一键启动（PowerShell 5.1）
#
# 前置：Docker Desktop 已启动；镜像已导入（或加 -Build 联网构建）。
#
# 用法：
#   .\infra\standalone\start-standalone.ps1
#   .\infra\standalone\start-standalone.ps1 -AdminPassword 'S3cure-Passw0rd'
#   $env:ERP_ADMIN_PASSWORD='S3cure-Passw0rd'; .\infra\standalone\start-standalone.ps1
#   .\infra\standalone\start-standalone.ps1 -Build
#   .\infra\standalone\start-standalone.ps1 -ImageTag standalone-local
#
# 管理员密码字符集：字母、数字与 . _ @ # % + = : -（至少 8 位）。
# ============================================================================
[CmdletBinding()]
param(
    [string]$AdminUsername = '',
    [string]$AdminPassword = '',
    [string]$ImageTag = '',
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\standalone-common.ps1')

Test-DockerReady
Initialize-StandaloneEnvironment

if ($ImageTag) {
    $env:IMAGE_TAG = $ImageTag
}

if (-not $AdminUsername) {
    if ($env:ERP_ADMIN_USERNAME) {
        $AdminUsername = $env:ERP_ADMIN_USERNAME
    } else {
        $AdminUsername = 'admin'
    }
}
if (-not $AdminPassword -and $env:ERP_ADMIN_PASSWORD) {
    $AdminPassword = $env:ERP_ADMIN_PASSWORD
}

Assert-AdminUsername $AdminUsername
$explicitPassword = $false
if ($AdminPassword) {
    Assert-AdminPassword $AdminPassword
    $explicitPassword = $true
}

Ensure-Images -Build:$Build

Write-Step "启动单机版（tag=$($env:IMAGE_TAG)）"
try {
    Invoke-Compose -Arguments @('up', '-d', '--no-build', '--pull', 'never', '--remove-orphans', '--wait', '--wait-timeout', '240')
} catch {
    Write-Host "x $($_.Exception.Message)" -ForegroundColor Red
    Show-FailureLogs
    Die "启动失败；请按上面的日志排查（常见原因：端口被占用、镜像缺失、.env 凭据被改）"
}

if (-not (Wait-Ready -TimeoutSec 120)) {
    Show-FailureLogs
    Die "服务已启动但 /readyz 未通过；请检查 backend 日志与数据库连接"
}

Initialize-Admin -Username $AdminUsername -Password $AdminPassword -Explicit:$explicitPassword
Show-Endpoints
