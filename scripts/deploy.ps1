# ════════════════════════════════════════════════════════════════════════════
# deploy.ps1：构建 → 启动 → 等 /readyz → 记录版本；失败默认自动回滚上一版
#
# 用法（PowerShell 5.1+）：
#   .\scripts\deploy.ps1
#   .\scripts\deploy.ps1 -Tag m6-20261023
#   .\scripts\deploy.ps1 -NoBuild
#   .\scripts\deploy.ps1 -NoAutoRollback
#
# 前置：infra/deploy/.env（从 .env.example 复制并改掉密码/密钥）
# ════════════════════════════════════════════════════════════════════════════
[CmdletBinding()]
param(
    [string]$Tag = '',
    [switch]$NoBuild,
    [switch]$NoAutoRollback
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
        Die "找不到 .env：$Path（先 cp infra/deploy/.env.example infra/deploy/.env）"
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

function Show-FailureLogs {
    Write-Host ""
    Write-Host "--- compose ps ---" -ForegroundColor Yellow
    & docker compose --env-file $EnvFile -f $ComposeFile ps
    Write-Host ""
    Write-Host "--- backend / web 最近 100 行日志 ---" -ForegroundColor Yellow
    & docker compose --env-file $EnvFile -f $ComposeFile logs --tail=100 backend web
}

function Wait-Healthy([int]$TimeoutSec = 180) {
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
            # 503 / 连接失败都继续等
        }
        Start-Sleep -Seconds 3
    }
    Write-Host "  健康检查超时（${TimeoutSec}s）：$url" -ForegroundColor Red
    return $false
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

function Invoke-AutoRollback([string]$Current, [string]$Previous, [string]$FailedTag) {
    Write-Host ""
    Write-Host "=== 健康检查失败，自动回滚到 $Current ===" -ForegroundColor Yellow
    $env:IMAGE_TAG = $Current
    try {
        Invoke-Compose @('up', '-d', '--remove-orphans', '--no-build')
    } catch {
        Write-Host "x 自动回滚启动失败：$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
    if (Wait-Healthy -TimeoutSec 120) {
        Write-State -Current $Current -Previous $Previous
        Die "新版本 $FailedTag 部署失败，已自动回滚到 $Current（日志见上）"
    }
    Die "自动回滚也失败；请立即人工介入（Runbook 常见故障）"
}

# ── 前置检查 ──────────────────────────────────────────────────────────────
try {
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker 守护进程未运行" }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { throw "未找到 docker compose 插件" }
} catch {
    Die "Docker 前置检查失败：$($_.Exception.Message)"
}

Load-DotEnv -Path $EnvFile

if (-not $env:POSTGRES_PASSWORD) { Die "POSTGRES_PASSWORD 未设置" }
if (-not $env:ERP_JWT_SECRET) { Die "ERP_JWT_SECRET 未设置（openssl rand -base64 48）" }
if ($env:POSTGRES_PASSWORD -match '^(change-me|erp$|password$|postgres$)') {
    Die "POSTGRES_PASSWORD 仍是占位/弱值，拒绝部署"
}
if ($env:ERP_JWT_SECRET -match 'change-me|dev-only') {
    Die "ERP_JWT_SECRET 仍是占位/开发默认值，拒绝部署"
}
if ($env:ERP_JWT_SECRET.Length -lt 32) {
    Die "ERP_JWT_SECRET 至少需要 32 字节（当前 $($env:ERP_JWT_SECRET.Length)）"
}

if (-not $Tag) {
    $sha = (& git -C $Root rev-parse --short HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $sha) { $sha = 'manual' }
    $Tag = "$sha-" + (Get-Date).ToString('yyyyMMdd-HHmmss')
}
if ($Tag -notmatch '^[A-Za-z0-9._-]+$') {
    Die "tag 只能包含字母、数字、点、下划线、连字符：$Tag"
}

$Current = Read-StateField 'CURRENT_TAG'
$Previous = Read-StateField 'PREVIOUS_TAG'

Write-Host ""
Write-Host "=== 部署 erp-prod · tag=$Tag ==="
Write-Host "  上一版：$(if ($Current) { $Current } else { '（无，首次部署）' })"

$env:IMAGE_TAG = $Tag

# ── 构建 + 启动 ───────────────────────────────────────────────────────────
try {
    if (-not $NoBuild) {
        Write-Host ""
        Write-Host "=== 构建镜像（backend + web） ==="
        Invoke-Compose @('build', 'backend', 'web')
    } else {
        Write-Host ""
        Write-Host "=== 跳过构建（-NoBuild） ==="
        foreach ($img in @("erp-backend:$Tag", "erp-web:$Tag")) {
            & docker image inspect $img *> $null
            if ($LASTEXITCODE -ne 0) {
                Die "本机没有镜像 $img，无法 -NoBuild"
            }
        }
    }

    Write-Host ""
    Write-Host "=== 启动 / 滚动更新 ==="
    Invoke-Compose @('up', '-d', '--remove-orphans', '--no-build')
} catch {
    Write-Host "x $($_.Exception.Message)" -ForegroundColor Red
    Show-FailureLogs
    if ($Current -and -not $NoAutoRollback -and $Current -ne $Tag) {
        Invoke-AutoRollback -Current $Current -Previous $Previous -FailedTag $Tag
    }
    exit 1
}

# ── 健康检查 ──────────────────────────────────────────────────────────────
$timeout = 180
if ($env:DEPLOY_HEALTH_TIMEOUT) { $timeout = [int]$env:DEPLOY_HEALTH_TIMEOUT }

if (Wait-Healthy -TimeoutSec $timeout) {
    $prevForState = $Current
    if ($Current -eq $Tag) { $prevForState = $Previous }
    Write-State -Current $Tag -Previous $prevForState

    Write-Host ""
    Write-Host "=== 部署成功 ===" -ForegroundColor Green
    Write-Host "  当前版本：$Tag"
    Write-Host "  上一版本：$(if ($prevForState) { $prevForState } else { '（无）' })"
    $port = if ($env:WEB_PORT) { $env:WEB_PORT } else { '18080' }
    Write-Host "  入口：http://127.0.0.1:$port/"
    Write-Host "  就绪探针：http://127.0.0.1:$port/readyz"
    Write-Host ""
    & docker compose --env-file $EnvFile -f $ComposeFile ps
    exit 0
}

Show-FailureLogs
if ($Current -and -not $NoAutoRollback -and $Current -ne $Tag) {
    Invoke-AutoRollback -Current $Current -Previous $Previous -FailedTag $Tag
}
Die "部署失败，未自动回滚（当前版本：$(if ($Current) { $Current } else { '无' })）"
