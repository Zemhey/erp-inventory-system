# ════════════════════════════════════════════════════════════════════════════
# rollback.ps1：按 tag 切回上一版（默认 .deploy-state 的 PREVIOUS_TAG）
#
# 用法：
#   .\scripts\rollback.ps1
#   .\scripts\rollback.ps1 -Tag m6-20261022-101500
#
# ★ 镜像回滚 != 数据库回滚。Flyway 只向前迁移；若本次发布包含不兼容迁移，
#   必须先按 Runbook 从备份恢复数据库，再回滚镜像。
# ════════════════════════════════════════════════════════════════════════════
[CmdletBinding()]
param(
    [string]$Tag = ''
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root 'infra\deploy\docker-compose.prod.yml'
$EnvFile = Join-Path $Root 'infra\deploy\.env'
$StateFile = Join-Path $Root 'infra\deploy\.deploy-state'

function Die([string]$Message) {
    Write-Host ""
    Write-Host "x $Message" -ForegroundColor Red
    exit 1
}

function Load-DotEnv([string]$Path) {
    if (-not (Test-Path $Path)) {
        Die "找不到 .env：$Path"
    }
    Get-Content -Path $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        Set-Item -Path "Env:$key" -Value $value
    }
}

function Invoke-Compose([string[]]$ComposeArgs) {
    & docker compose --env-file $EnvFile -f $ComposeFile @ComposeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($ComposeArgs -join ' ') 失败（exit $LASTEXITCODE）"
    }
}

function Read-StateField([string]$Name) {
    if (-not (Test-Path $StateFile)) { return '' }
    $line = Get-Content -Path $StateFile | Where-Object { $_ -match "^$Name=" } | Select-Object -First 1
    if ($line) { return $line.Substring($Name.Length + 1) }
    return ''
}

function Write-State([string]$Current, [string]$Previous) {
    $ts = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    @(
        "CURRENT_TAG=$Current",
        "PREVIOUS_TAG=$Previous",
        "DEPLOYED_AT=$ts"
    ) | Set-Content -Path $StateFile -Encoding ASCII
}

function Wait-Healthy([int]$TimeoutSec = 120) {
    $port = if ($env:WEB_PORT) { $env:WEB_PORT } else { '18080' }
    $url = "http://127.0.0.1:$port/readyz"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) {
                Write-Host "  健康检查通过：$url"
                return $true
            }
        } catch {
        }
        Start-Sleep -Seconds 3
    }
    Write-Host "  健康检查超时（${TimeoutSec}s）：$url" -ForegroundColor Red
    return $false
}

$Current = Read-StateField 'CURRENT_TAG'
if (-not $Tag) { $Tag = Read-StateField 'PREVIOUS_TAG' }
if (-not $Tag) { Die "没有可回滚的版本：.deploy-state 里没有 PREVIOUS_TAG，请显式传 -Tag" }

foreach ($img in @("erp-backend:$Tag", "erp-web:$Tag")) {
    & docker image inspect $img *> $null
    if ($LASTEXITCODE -ne 0) {
        Die "本机没有镜像 $img；先显式构建该 tag，或改用其他版本"
    }
}

Load-DotEnv -Path $EnvFile

Write-Host ""
Write-Host "=== 回滚 erp-prod ==="
Write-Host "  当前版本：$(if ($Current) { $Current } else { '未知' })"
Write-Host "  目标版本：$Tag"
Write-Host ""
Write-Host "! 数据库迁移不会自动回滚；若本次发布含不兼容迁移，请先按 Runbook 恢复备份。" -ForegroundColor Yellow
Write-Host ""

$env:IMAGE_TAG = $Tag

try {
    Invoke-Compose @('up', '-d', '--remove-orphans', '--no-build')
} catch {
    Write-Host "x $($_.Exception.Message)" -ForegroundColor Red
    & docker compose --env-file $EnvFile -f $ComposeFile logs --tail=100 backend web
    exit 1
}

if (Wait-Healthy -TimeoutSec 120) {
    Write-State -Current $Tag -Previous $Current
    Write-Host ""
    Write-Host "=== 回滚成功 ===" -ForegroundColor Green
    Write-Host "  当前版本：$Tag"
    & docker compose --env-file $EnvFile -f $ComposeFile ps
    exit 0
}

& docker compose --env-file $EnvFile -f $ComposeFile ps
& docker compose --env-file $EnvFile -f $ComposeFile logs --tail=100 backend web
Die "回滚后健康检查仍未通过；请人工介入（Runbook 常见故障）"
