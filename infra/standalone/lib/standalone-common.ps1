# ============================================================================
# 单机版 PowerShell 公共库（PowerShell 5.1）
#
# 被 start/stop/status/backup-standalone.ps1 dot-source。
# 约定：.ps1 文件必须 UTF-8 with BOM + CRLF，否则 PS 5.1 会按 GBK 解码中文。
# ============================================================================

$script:StandaloneDir = Split-Path -Parent $PSScriptRoot
$script:RepoRoot = Split-Path -Parent (Split-Path -Parent $script:StandaloneDir)
$script:ComposeFile = Join-Path $script:StandaloneDir 'docker-compose.standalone.yml'
$script:EnvFile = Join-Path $script:StandaloneDir '.env'
$script:BackupDir = Join-Path $script:StandaloneDir 'backups'
$script:SeedSql = Join-Path $script:StandaloneDir 'seed-admin.sql'
$script:PgContainer = 'erp-standalone-postgres'
$script:DataVolume = 'erp-standalone-pgdata'

$ProgressPreference = 'SilentlyContinue'

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "=== $Message ==="
}

function Write-Info([string]$Message) {
    Write-Host "  $Message"
}

function Die([string]$Message) {
    Write-Host ""
    Write-Host "x $Message" -ForegroundColor Red
    exit 1
}

function Test-DockerReady {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        Die "未找到 docker，请先安装 Docker Desktop / Docker Engine"
    }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Die "Docker 守护进程未运行（Windows 请先启动 Docker Desktop）"
    }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        Die "未找到 docker compose 插件（需要 Compose v2）"
    }
}

function New-RandomHex([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
    } finally {
        $rng.Dispose()
    }
    return (($buffer | ForEach-Object { $_.ToString('x2') }) -join '')
}

function New-StandaloneEnv {
    $pgPassword = New-RandomHex -Bytes 24
    $jwtSecret = New-RandomHex -Bytes 32
    $lines = @(
        '# 由 start-standalone 自动生成（' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '）',
        '# 本文件含真实凭据，已被 .gitignore 忽略；不要提交、不要发到聊天工具。',
        'POSTGRES_DB=erp',
        'POSTGRES_USER=erp',
        "POSTGRES_PASSWORD=$pgPassword",
        "ERP_JWT_SECRET=$jwtSecret",
        '',
        'WEB_BIND_IP=127.0.0.1',
        'WEB_PORT=18090',
        'PG_HOST_PORT=15439',
        '',
        'TZ=Asia/Shanghai',
        'IMAGE_TAG=standalone',
        'BACKUP_RETENTION_DAYS=14'
    )
    $content = ($lines -join "`r`n") + "`r`n"
    [System.IO.File]::WriteAllText($script:EnvFile, $content, (New-Object System.Text.UTF8Encoding($false)))
    Write-Info "已生成 $($script:EnvFile)（随机数据库密码与 JWT 密钥）"
}

function Load-DotEnv([string]$Path) {
    if (-not (Test-Path $Path)) {
        Die "找不到 $Path；请先运行 start-standalone.ps1（会自动生成）"
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

function Initialize-StandaloneEnvironment {
    if (-not (Test-Path $script:EnvFile)) {
        New-StandaloneEnv
    }
    Load-DotEnv -Path $script:EnvFile

    if (-not $env:POSTGRES_PASSWORD) { Die "POSTGRES_PASSWORD 未设置（见 infra/standalone/.env.example）" }
    if (-not $env:ERP_JWT_SECRET) { Die "ERP_JWT_SECRET 未设置（见 infra/standalone/.env.example）" }
    if ($env:POSTGRES_PASSWORD -match '^(change-me|erp$|password$|postgres$)') {
        Die "POSTGRES_PASSWORD 仍是占位/弱值，拒绝启动；请修改 $($script:EnvFile)"
    }
    if ($env:ERP_JWT_SECRET -match 'change-me|dev-only') {
        Die "ERP_JWT_SECRET 仍是占位/开发默认值，拒绝启动；请修改 $($script:EnvFile)"
    }
    if ($env:ERP_JWT_SECRET.Length -lt 32) {
        Die "ERP_JWT_SECRET 至少需要 32 字节（当前 $($env:ERP_JWT_SECRET.Length)）"
    }
}

function Invoke-Compose([string[]]$Arguments) {
    & docker compose --env-file $script:EnvFile -f $script:ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') 失败（exit $LASTEXITCODE）"
    }
}

function Invoke-ComposeCapture([string[]]$Arguments) {
    $output = & docker compose --env-file $script:EnvFile -f $script:ComposeFile @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') 失败（exit $LASTEXITCODE）"
    }
    return (($output | Out-String).Trim())
}

function Show-FailureLogs {
    Write-Host ""
    Write-Host "--- compose ps ---" -ForegroundColor Yellow
    & docker compose --env-file $script:EnvFile -f $script:ComposeFile ps
    Write-Host ""
    Write-Host "--- backend / web 最近 100 行日志 ---" -ForegroundColor Yellow
    & docker compose --env-file $script:EnvFile -f $script:ComposeFile logs --tail=100 backend web
}

function Get-WebPort {
    if ($env:WEB_PORT) { return [int]$env:WEB_PORT }
    return 18090
}

function Get-PgPort {
    if ($env:PG_HOST_PORT) { return [int]$env:PG_HOST_PORT }
    return 15439
}

function Get-HttpStatus([string]$Url) {
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        return [int]$resp.StatusCode
    } catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

function Wait-Ready([int]$TimeoutSec = 120) {
    $url = "http://127.0.0.1:$(Get-WebPort)/readyz"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((Get-HttpStatus -Url $url) -eq 200) {
            Write-Info "整套已就绪：$url"
            return $true
        }
        Start-Sleep -Seconds 3
    }
    Write-Host "  健康检查超时（${TimeoutSec}s）：$url" -ForegroundColor Red
    return $false
}

function Test-Image([string]$Image) {
    & docker image inspect $Image *> $null
    return ($LASTEXITCODE -eq 0)
}

function Ensure-Images([switch]$Build) {
    $tag = if ($env:IMAGE_TAG) { $env:IMAGE_TAG } else { 'standalone' }
    $images = @('postgres:16-alpine', "erp-backend:$tag", "erp-web:$tag")
    $missing = @($images | Where-Object { -not (Test-Image $_) })

    if ($missing.Count -gt 0 -and $Build) {
        Write-Step "构建后端/前端镜像（需要网络或本地基础镜像）"
        Invoke-Compose -Arguments @('build', 'backend', 'web')
        if (-not (Test-Image 'postgres:16-alpine')) {
            Write-Info "显式拉取 postgres:16-alpine（需要网络）"
            & docker pull postgres:16-alpine
            if ($LASTEXITCODE -ne 0) {
                throw "拉取 postgres:16-alpine 失败；请改用镜像包导入"
            }
        }
        $missing = @($images | Where-Object { -not (Test-Image $_) })
    }

    if ($missing.Count -gt 0) {
        foreach ($img in $missing) {
            Write-Host "  [warn] 缺少镜像：$img" -ForegroundColor Yellow
        }
        Write-Host ""
        Write-Host "x 单机版不会隐式联网拉镜像。请先准备镜像，二选一：" -ForegroundColor Red
        Write-Host ""
        Write-Host "  方式 A（推荐，目标机断网）：在联网机器上构建并导出，然后拷到本机导入"
        Write-Host "    docker save -o erp-standalone-images.tar erp-backend:$tag erp-web:$tag postgres:16-alpine"
        Write-Host "    docker load -i erp-standalone-images.tar"
        Write-Host ""
        Write-Host "  方式 B（本机 Docker Hub 不可达）：用 M6 离线垫片构建"
        Write-Host "    TAG=$tag ./scripts/build-local-images.sh"
        Write-Host "    然后用 -ImageTag $tag 或修改 .env 的 IMAGE_TAG"
        Write-Host ""
        Write-Host "  方式 C（本机可联网）：加 -Build 启动"
        Write-Host "    .\infra\standalone\start-standalone.ps1 -Build"
        Write-Host ""
        Write-Host "  当前 IMAGE_TAG=$tag（可在 $($script:EnvFile) 修改）"
        exit 1
    }
}

function Assert-AdminUsername([string]$Username) {
    if ($Username -notmatch '^[A-Za-z0-9._-]+$') {
        Die "管理员用户名只能包含字母、数字、点、下划线、连字符：$Username"
    }
}

function Assert-AdminPassword([string]$Password) {
    if ($Password.Length -lt 8) {
        Die "管理员密码至少 8 位（建议 16 位以上）"
    }
    if ($Password -notmatch '^[A-Za-z0-9._@#%+=:-]+$') {
        Die "管理员密码只允许字母、数字与 . _ @ # % + = : -（避免空格/引号在命令行转义时被破坏）"
    }
}

function Get-AdminCount([string]$Username) {
    $sql = "SELECT count(*) FROM app_users WHERE username='$Username'"
    $out = Invoke-ComposeCapture -Arguments @('exec', '-T', 'postgres', 'psql', '-U', $env:POSTGRES_USER, '-d', $env:POSTGRES_DB, '-tAc', $sql)
    return [int]$out.Trim()
}

function Get-AdminRoleCount([string]$Username) {
    $sql = "SELECT count(*) FROM app_users u JOIN app_user_roles ur ON ur.user_id=u.id JOIN app_roles r ON r.id=ur.role_id WHERE u.username='$Username' AND r.code='ADMIN'"
    $out = Invoke-ComposeCapture -Arguments @('exec', '-T', 'postgres', 'psql', '-U', $env:POSTGRES_USER, '-d', $env:POSTGRES_DB, '-tAc', $sql)
    return [int]$out.Trim()
}

function Invoke-SeedAdmin([string]$Username, [string]$Password, [string]$DisplayName) {
    $containerSql = $script:PgContainer + ':/tmp/erp-standalone-seed-admin.sql'
    & docker cp $script:SeedSql $containerSql | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Die "复制 seed-admin.sql 到容器失败"
    }
    try {
        Invoke-Compose -Arguments @(
            'exec', '-T', 'postgres', 'psql',
            '-U', $env:POSTGRES_USER, '-d', $env:POSTGRES_DB,
            '-v', 'ON_ERROR_STOP=1',
            '-v', "username=$Username",
            '-v', "password=$Password",
            '-v', "display_name=$DisplayName",
            '-v', 'role=ADMIN',
            '-f', '/tmp/erp-standalone-seed-admin.sql'
        ) | Out-Null
    } finally {
        & docker compose --env-file $script:EnvFile -f $script:ComposeFile exec -T postgres rm -f /tmp/erp-standalone-seed-admin.sql *> $null
    }
    if ((Get-AdminRoleCount $Username) -lt 1) {
        Die "管理员 $Username 已写入，但 ADMIN 角色绑定失败"
    }
}

function Initialize-Admin([string]$Username, [string]$Password, [switch]$Explicit) {
    $count = Get-AdminCount $Username
    if ($count -gt 0) {
        if ($Explicit) {
            Invoke-SeedAdmin -Username $Username -Password $Password -DisplayName 'Administrator'
            Write-Info "已重置管理员 $Username 的密码"
        } else {
            Write-Info "管理员 $Username 已存在，跳过初始化（重置密码：-AdminPassword '<新密码>'）"
        }
        return
    }

    if (-not $Password) {
        $Password = New-RandomHex -Bytes 16
        Write-Info "未提供管理员密码，已生成随机密码（只显示这一次）"
    }
    Invoke-SeedAdmin -Username $Username -Password $Password -DisplayName 'Administrator'
    Write-Info "管理员已创建：$Username"

    if (-not $Explicit) {
        Write-Host ""
        Write-Host "  管理员初始密码：$Password" -ForegroundColor Yellow
        Write-Host "  请登录后尽快修改；忘记可用 -AdminPassword 重置。"
    }
}

function Show-Endpoints {
    $port = Get-WebPort
    $bind = if ($env:WEB_BIND_IP) { $env:WEB_BIND_IP } else { '127.0.0.1' }
    Write-Host ""
    Write-Host "=== 单机版已启动 ==="
    Write-Host "  入口（本机）：http://127.0.0.1:$port/"
    Write-Host "  健康检查：    http://127.0.0.1:$port/readyz"
    Write-Host "  PostgreSQL：  127.0.0.1:$(Get-PgPort)（仅本机）"
    Write-Host "  数据卷：      $script:DataVolume"
    Write-Host "  备份：        .\infra\standalone\backup-standalone.ps1"
    Write-Host "  状态：        .\infra\standalone\status-standalone.ps1"
    Write-Host "  停止：        .\infra\standalone\stop-standalone.ps1"
    if ($bind -eq '0.0.0.0') {
        Write-Info "WEB_BIND_IP=0.0.0.0：局域网可通过本机 IP:$port 访问，请自行确认防火墙"
    }
}
