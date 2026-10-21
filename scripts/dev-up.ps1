<#
.SYNOPSIS
  dev-up：一键起本地开发环境（Windows 版）。

.DESCRIPTION
  与 scripts/dev-up.sh 等价，供 Windows 直接使用。
  设计要点与 bash 版一致：幂等、等健康检查、失败给可操作信息。

.NOTES
  若项目路径含中文，Gradle 的 test 任务会有编码问题（见 docs/ADR-0010）。
  日常构建建议用 ASCII 联接路径，脚本已提供提示。
#>
[CmdletBinding()]
param(
    [switch]$Down,
    [switch]$Status
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $ScriptDir
$ComposeFile = Join-Path $RootDir 'infra/compose/docker-compose.yml'

Write-Host '=== 进销存系统 · 本地环境 ===' -ForegroundColor Cyan
Write-Host "compose: $ComposeFile"

# ── 前置检查 ─────────────────────────────────────────────────────────────
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error '未找到 docker。请先安装 Docker Desktop。'
    exit 1
}
$null = & docker info 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Docker 守护进程未运行。请先启动 Docker Desktop。'
    exit 1
}

if ($Down) {
    Write-Host '--- 停止并移除容器 ---' -ForegroundColor Yellow
    & docker compose -f $ComposeFile down
    exit $LASTEXITCODE
}

if ($Status) {
    & docker compose -f $ComposeFile ps
    exit $LASTEXITCODE
}

# ── 启动 ─────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '--- 启动 PostgreSQL / Redis / RabbitMQ ---' -ForegroundColor Yellow
& docker compose -f $ComposeFile up -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# ── 等健康检查（最多 120s） ──────────────────────────────────────────────
Write-Host ''
Write-Host '--- 等待服务健康 ---' -ForegroundColor Yellow
$services = @('erp-postgres', 'erp-redis', 'erp-rabbitmq')
$deadline = (Get-Date).AddSeconds(120)

while ($true) {
    $all = $true
    foreach ($svc in $services) {
        $state = & docker inspect -f '{{.State.Health.Status}}' $svc 2>$null
        if ($state -ne 'healthy') { $all = $false }
    }
    if ($all) { break }

    if ((Get-Date) -gt $deadline) {
        Write-Host '✗ 等待健康检查超时（120s）。当前状态：' -ForegroundColor Red
        foreach ($svc in $services) {
            $state = & docker inspect -f '{{.State.Health.Status}}' $svc 2>$null
            Write-Host "    ${svc}: $state"
        }
        Write-Host "  排查：docker compose -f `"$ComposeFile`" logs --tail=50"
        exit 1
    }
    Start-Sleep -Seconds 2
}

foreach ($svc in $services) {
    Write-Host "  ✓ $svc healthy" -ForegroundColor Green
}

Write-Host ''
Write-Host '=== 环境就绪 ===' -ForegroundColor Cyan
Write-Host '  PostgreSQL : localhost:5432  库 erp / 用户 erp / 密码 erp'
Write-Host '  Redis      : localhost:16379 （容器内 6379；宿主用 16379，因本机保留段含 6281-6380）'
Write-Host '  RabbitMQ   : localhost:5672  (管理台 http://localhost:15672  erp/erp)'
Write-Host ''
Write-Host '下一步：'
Write-Host '  后端启动   cd backend/server; .\gradlew.bat bootRun'
Write-Host '  API 文档   http://localhost:8080/swagger-ui.html'
Write-Host '  跑测试     cd backend/server; .\gradlew.bat test'
Write-Host ''
Write-Host '停止环境：.\scripts\dev-up.ps1 -Down'
