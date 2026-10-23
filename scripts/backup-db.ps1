# ════════════════════════════════════════════════════════════════════════════
# backup-db.ps1：pg_dump 自定义格式 + 可读性校验 + 按天数保留
#
# 用法：
#   .\scripts\backup-db.ps1
#   $env:BACKUP_RETENTION_DAYS = '30'; .\scripts\backup-db.ps1
#
# 产物：infra\deploy\backups\erp-YYYYMMDD-HHMMSS.dump（已在 .gitignore）
#
# ★ 为什么在容器内 dump 再 docker cp，而不是把 stdout 重定向到文件：
#   PowerShell 5.1 的原生进程重定向会按文本编码处理，二进制 dump 会被改写。
#   容器内落盘 + docker cp 全程不经过控制台编码。
# ════════════════════════════════════════════════════════════════════════════
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root 'infra\deploy\docker-compose.prod.yml'
$EnvFile = Join-Path $Root 'infra\deploy\.env'
$BackupDir = Join-Path $Root 'infra\deploy\backups'

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

Load-DotEnv -Path $EnvFile

& docker info *> $null
if ($LASTEXITCODE -ne 0) { Die "Docker 守护进程未运行" }

$retention = 14
if ($env:BACKUP_RETENTION_DAYS) { $retention = [int]$env:BACKUP_RETENTION_DAYS }

New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null

$cid = (& docker compose --env-file $EnvFile -f $ComposeFile ps -q postgres | Out-String).Trim()
if (-not $cid) { Die "postgres 容器未运行（docker compose ps 检查）" }

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$file = Join-Path $BackupDir ("erp-" + $stamp + ".dump")
$tmpInContainer = '/tmp/erp-backup.dump'

Write-Host ""
Write-Host "=== 备份 PostgreSQL ==="
Write-Host "  目标文件：$file"

try {
    & docker exec $cid sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/erp-backup.dump'
    if ($LASTEXITCODE -ne 0) { Die "pg_dump 失败（exit $LASTEXITCODE）" }

    & docker exec $cid sh -c 'pg_restore -l /tmp/erp-backup.dump | grep -qi "table data"'
    if ($LASTEXITCODE -ne 0) { Die "dump 无法被 pg_restore 解析或没有表数据" }

    & docker cp "${cid}:$tmpInContainer" $file
    if ($LASTEXITCODE -ne 0) { Die "docker cp 失败（exit $LASTEXITCODE）" }
} finally {
    & docker exec $cid rm -f $tmpInContainer *> $null
}

$item = Get-Item $file
if ($item.Length -le 0) { Die "dump 为空，备份失败" }

$deleted = 0
$cutoff = (Get-Date).AddDays(-$retention)
Get-ChildItem -Path $BackupDir -Filter 'erp-*.dump' -File |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    ForEach-Object {
        Remove-Item -Path $_.FullName -Force
        $deleted++
    }

Write-Host "  清理过期备份：$deleted 个（保留 $retention 天）"
Write-Host ""
Write-Host "=== 备份完成 ===" -ForegroundColor Green
Write-Host ("  {0}  {1:N1} MB" -f $file, ($item.Length / 1MB))
