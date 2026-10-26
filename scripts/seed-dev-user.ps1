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

# ★ 不走临时文件 + stdin 管道（本机 PS 5.1 实测的两个坑，计划书 §6 坑 17 的同族）：
#   ① `Set-Content -Encoding UTF8` 会写 BOM，psql 读到「无效的 UTF-8 字节顺序」；
#      去掉 BOM 后 `Get-Content -Raw` 又按 ANSI 码页解码，中文变成乱码；
#   ② 更隐蔽的是管道：PowerShell 送进原生进程 stdin 时按 **[Console]::OutputEncoding**
#      重新编码，中文直接掉成 `???`（实测：display_name 存成 3f3f3f，
#      脚本报"账号已就绪"却静默把名字写坏 —— 比报错更难发现）。
#   改成把 SQL 当**单个 -c 参数**传：argv 由 .NET 以 UTF-16 交给 docker CLI，
#   再到容器里是 UTF-8，全程不经过控制台码页（下面赋角色的语句本来就是这么写的）。
$sql = @"
INSERT INTO app_users (username, password_hash, display_name, enabled, tenant_id)
VALUES ('$Username', '$hashHolder', '$DisplayName', TRUE,
        COALESCE((SELECT tenant_id FROM app_users WHERE username = '$Username'),
                 (SELECT id FROM tenants WHERE code = 'default' ORDER BY id LIMIT 1)))
ON CONFLICT (username) DO UPDATE
   SET password_hash = EXCLUDED.password_hash,
       display_name  = EXCLUDED.display_name,
       enabled       = TRUE,
       failed_attempts = 0,
       locked_until = NULL,
       updated_at = now()
RETURNING id;
"@

$out = docker exec erp-postgres psql -U erp -d erp -c $sql 2>&1
$userId = ($out | Select-String -Pattern '^\s*(\d+)\s*$' | Select-Object -First 1)

if (-not $userId) {
    Write-Host '  psql 输出：' -ForegroundColor Yellow
    $out | ForEach-Object { "    $_" }
    Write-Error '  ✗ 写入账号失败'
    exit 1
}

# 赋角色（V16 之后 tenant_id 是 NOT NULL，且角色按租户各一份，必须同租户配对；
# app_user_tenants 是登录时的可见性检查，缺了它账号存在也登不进去）
docker exec erp-postgres psql -U erp -d erp -c @"
INSERT INTO app_user_tenants (user_id, tenant_id)
SELECT u.id, u.tenant_id FROM app_users u WHERE u.username = '$Username'
ON CONFLICT (user_id, tenant_id) DO NOTHING;

INSERT INTO app_user_roles (user_id, role_id, tenant_id)
SELECT u.id, r.id, u.tenant_id
  FROM app_users u
  JOIN app_roles r ON r.code = '$Role' AND r.tenant_id = u.tenant_id
 WHERE u.username = '$Username'
ON CONFLICT DO NOTHING;
"@ | Out-Null

Write-Host "  ✓ 账号已就绪" -ForegroundColor Green
Write-Host ''
Write-Host '登录信息：'
Write-Host "  用户名: $Username"
Write-Host "  密码:   $Password"
Write-Host ''
