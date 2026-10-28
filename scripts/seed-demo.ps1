# ============================================================================
# seed-demo.ps1 —— 演示数据一键重灌（Windows）
#
# 做三件事，按顺序：
#   1.（可选 -Reset）清空业务数据 → 干净的基线（主数据/IAM/科目保留）
#   2. 确认后端已加载演示时钟开关（X-Demo-Biz-Time 只有在它打开时才生效）
#   3. 跑 scripts/seed-demo.mjs：12 个月 + 全模块 + 异常样本
#
# ★ 为什么需要第 2 步：库存流水 occurred_at、应收到期日、成本层的时间
#   都取自服务端 ClockProvider。开关关着的时候，倒签单据日期不会让这些账
#   回到过去 —— 12 个月的数据会全挤在"今天"那一格，报表曲线退化成一个点。
#
# 用法：
#   scripts\seed-demo.ps1                 # 12 个月全模块（需库是干净的）
#   scripts\seed-demo.ps1 -Reset          # 先清库再灌（推荐第一次用）
#   scripts\seed-demo.ps1 -Months 6       # 只铺 6 个月
#   scripts\seed-demo.ps1 -Products 40    # 商品款数
#   scripts\seed-demo.ps1 -Force          # 跳过幂等，追加一波
#   scripts\seed-demo.ps1 -MasterOnly     # 只补主数据与员工（组织架构/花名册），不动单据
# ============================================================================
[CmdletBinding()]
param(
    [switch]$Reset,
    [switch]$Force,
    [switch]$Quick,
    # 只建主数据（商品/客户/供应商/组织架构/员工），不碰单据 —— 给已有库补员工用
    [switch]$MasterOnly,
    [int]$Months = 12,
    [int]$Products = 120,
    [int]$Customers = 40,
    [int]$Sales = 40,
    [string]$Api = 'http://127.0.0.1:8080/api/v1',
    [string]$PgContainer = 'erp-postgres',
    [string]$PgDb = 'erp',
    [string]$PgUser = 'erp'
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$SqlFile = Join-Path $PSScriptRoot 'reset-demo-data.sql'
$Seeder = Join-Path $PSScriptRoot 'seed-demo.mjs'

function Write-Step($msg) { Write-Host "`n=== $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host "  ! $msg" -ForegroundColor Yellow }
function Write-Fail($msg) { Write-Host "  ✗ $msg" -ForegroundColor Red }

Write-Host '=== 演示数据重灌 ===' -ForegroundColor Cyan
Write-Host "  后端 API : $Api"
Write-Host "  数据库   : $PgContainer / $PgDb"

# ── 1. 后端就绪 ──────────────────────────────────────────────
Write-Step '1/4 后端健康检查'
$health = "http://127.0.0.1:8080/actuator/health"
$deadline = (Get-Date).AddSeconds(60)
while ($true) {
    try {
        $r = Invoke-WebRequest -Uri $health -TimeoutSec 3 -SkipHttpErrorCheck
        if ($r.StatusCode -eq 200) { break }
    } catch { }
    if ((Get-Date) -gt $deadline) {
        Write-Fail "后端 60 秒内未就绪（$health）。先启动系统：启动系统.cmd"
        exit 1
    }
    Start-Sleep -Seconds 2
}
Write-Ok '后端就绪'

# ── 2. 演示时钟开关 ───────────────────────────────────────────
Write-Step '2/4 演示时钟开关（决定 12 个月曲线能否成立）'
try {
    $probe = & node (Join-Path $PSScriptRoot '..\.run\probe-clock.mjs') 2>&1
    $probe | ForEach-Object { Write-Host "  $_" }
} catch { }
Write-Warn2 '若上面判定"未生效"，用环境变量重启后端再灌：'
Write-Warn2 '  $env:ERP_DEMO_CLOCK_OVERRIDE="true"; 启动系统.cmd'
Write-Warn2 '（生产环境务必保持关闭：它允许调用方自行决定账目归属期间）'

# ── 3. 清库（可选）────────────────────────────────────────────
Write-Step '3/4 数据基线'
if ($Reset) {
    if (-not (Test-Path $SqlFile)) { Write-Fail "找不到 $SqlFile"; exit 1 }
    $running = docker inspect -f '{{.State.Running}}' $PgContainer 2>$null
    if ($running -ne 'true') { Write-Fail "容器 $PgContainer 没在运行，先 scripts\dev-up.ps1"; exit 1 }
    Get-Content $SqlFile -Raw | docker exec -i $PgContainer psql -U $PgUser -d $PgDb -f - -q
    if ($LASTEXITCODE -ne 0) { Write-Fail '清库失败，见上面的 psql 输出'; exit 1 }
    Write-Ok '业务数据已清空（主数据/IAM/科目/审批流保留）'
} else {
    Write-Warn2 '按原样追加：直接跑会在既有数据上叠加，报表时间轴可能互相污染。'
    Write-Warn2 '需要干净基线请加 -Reset。'
}

# ── 4. 灌数 ──────────────────────────────────────────────────
Write-Step '4/4 灌演示数据'
$args = @($Seeder)
if ($Force) { $args += '--force' }
if ($MasterOnly) {
    # 只补主数据与员工：库里通常已有单据，幂等闸门会拦，所以自动带 --force
    $args += '--master-only'
    $args += '--force'
} elseif ($Quick) {
    $args += '--quick'
} else {
    $args += "--months=$Months"
    $args += "--products=$Products"
    $args += "--customers=$Customers"
    $args += "--sales=$Sales"
}
$env:ERP_API = $Api
& node @args
if ($LASTEXITCODE -ne 0) {
    Write-Fail "seeder 退出码 $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "`n=== 完成 ===" -ForegroundColor Cyan
Write-Host '  打开 http://127.0.0.1:3030/ → 工作台 / 报表中心 / 数据大屏'
Write-Host '  异常样本在：待办中心、库存预警、账龄（>90 天档）、毛利（负毛利行）、盘点差异'
