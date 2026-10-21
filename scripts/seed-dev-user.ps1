# ════════════════════════════════════════════════════════════════════════════
# seed-dev-user.ps1：创建一个本地开发用管理员账号
#
# 为什么需要这个脚本：
#   V3 迁移刻意**不预置任何账号**（把 admin/admin 这类弱口令写进迁移脚本
#   是常见的生产事故来源）。但本地开发总得有个能登录的账号，
#   所以用这个脚本显式创建 —— 显式动作比默认存在更容易被意识到。
#
# 用法：
#   .\scripts\seed-dev-user.ps1
#   .\scripts\seed-dev-user.ps1 -Username dev -Password 'Dev@12345' -Role ADMIN
#
# 前置：docker compose 已起、Flyway 已迁移（跑过一次后端或测试即可）
# ════════════════════════════════════════════════════════════════════════════
[CmdletBinding()]
param(
    [string]$Username = 'admin',
    [string]$Password = 'Admin@12345',
    [string]$DisplayName = '管理员',
    [string]$Role = 'ADMIN'
)

$ErrorActionPreference = 'Stop'

Write-Host '=== 创建开发用账号 ===' -ForegroundColor Cyan
Write-Host "用户名: $Username   角色: $Role"

# BCrypt 哈希由 Java 侧生成，保证与后端校验完全同一算法与强度。
# 这里不手写哈希：手工填一个固定哈希是常见错误（强度不匹配、算法前缀不对都会导致登录失败）。
$hashHolder = & "$PSScriptRoot\bcrypt-hash.ps1" -Password $Password
if (-not $hashHolder) {
    Write-Error '  ✗ 生成 BCrypt 哈希失败'
    exit 1
}
Write-Host "  哈希前缀: $($hashHolder.Substring(0, 7))..."

$sql = @"
INSERT INTO app_users (username, password_hash, display_name, enabled)
VALUES ('$Username', '$hashHolder', '$DisplayName', TRUE)
ON CONFLICT (username) DO UPDATE
   SET password_hash = EXCLUDED.password_hash,
       display_name  = EXCLUDED.display_name,
       enabled       = TRUE,
       failed_attempts = 0,
       locked_until = NULL,
       updated_at = now()
RETURNING id;
"@

$sqlFile = Join-Path $env:TEMP "seed-user.sql"
Set-Content -Path $sqlFile -Value $sql -Encoding UTF8

# ★ 不用 `<`：PowerShell 里 `<` 是保留运算符（"reserved for future use"）。
#   用管道把文件内容送进 `docker exec -i` 的 stdin。
$out = Get-Content $sqlFile -Raw | docker exec -i erp-postgres psql -U erp -d erp 2>&1
$userId = ($out | Select-String -Pattern '^\s*(\d+)\s*$' | Select-Object -First 1)
Remove-Item $sqlFile -Force -ErrorAction SilentlyContinue

if (-not $userId) {
    Write-Host '  psql 输出：' -ForegroundColor Yellow
    $out | ForEach-Object { "    $_" }
    Write-Error '  ✗ 写入账号失败'
    exit 1
}

# 赋角色
docker exec erp-postgres psql -U erp -d erp -c @"
INSERT INTO app_user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u, app_roles r
 WHERE u.username = '$Username' AND r.code = '$Role'
ON CONFLICT DO NOTHING;
"@ | Out-Null

Write-Host "  ✓ 账号已就绪" -ForegroundColor Green
Write-Host ''
Write-Host '登录信息：'
Write-Host "  用户名: $Username"
Write-Host "  密码:   $Password"
Write-Host ''
