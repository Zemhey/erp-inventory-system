# ============================================================================
# 单机版数据库备份（PowerShell 5.1）
#
# 产物：infra/standalone/backups/erp-standalone-YYYYMMDDTHHMMSSZ.dump
# 格式：pg_dump -Fc；pg_restore -l 校验通过才算成功。
# 保留：BACKUP_RETENTION_DAYS（默认 14 天），可用 -RetentionDays 覆盖。
#
# 用法：
#   .\infra\standalone\backup-standalone.ps1
#   .\infra\standalone\backup-standalone.ps1 -RetentionDays 30
# ============================================================================
[CmdletBinding()]
param(
    [int]$RetentionDays = 0
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\standalone-common.ps1')

Test-DockerReady
if (-not (Test-Path $script:EnvFile)) {
    Die "找不到 $($script:EnvFile)；单机版尚未初始化"
}
Load-DotEnv -Path $script:EnvFile

$running = Invoke-ComposeCapture -Arguments @('ps', '--status', 'running', '-q', 'postgres')
if (-not $running) {
    Die "PostgreSQL 未运行；先启动单机版"
}

if ($RetentionDays -le 0) {
    if ($env:BACKUP_RETENTION_DAYS) {
        $RetentionDays = [int]$env:BACKUP_RETENTION_DAYS
    } else {
        $RetentionDays = 14
    }
}

New-Item -ItemType Directory -Force -Path $script:BackupDir | Out-Null

$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$file = Join-Path $script:BackupDir "erp-standalone-$stamp.dump"
$part = "$file.part"
$tmpInContainer = "/tmp/erp-standalone-backup-$PID.dump"
$verifyInContainer = "/tmp/erp-standalone-verify-$PID.dump"

Write-Step "备份 PostgreSQL"
Write-Info "目标文件：$file"

try {
    Invoke-Compose -Arguments @(
        'exec', '-T', '-e', "BACKUP_FILE=$tmpInContainer", 'postgres',
        'sh', '-c', 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f "$BACKUP_FILE"'
    )
    & docker cp ($script:PgContainer + ':' + $tmpInContainer) $part | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "从容器复制 dump 失败"
    }
    if (-not (Test-Path $part) -or (Get-Item $part).Length -le 0) {
        throw "dump 为空，备份失败"
    }

    & docker cp $part ($script:PgContainer + ':' + $verifyInContainer) | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "复制 dump 回容器做校验失败"
    }
    $listing = Invoke-ComposeCapture -Arguments @('exec', '-T', 'postgres', 'pg_restore', '-l', $verifyInContainer)
    if ($listing -notmatch '(?i)table data') {
        throw "dump 无法被 pg_restore 解析或没有表数据，备份失败"
    }

    Move-Item -Force -Path $part -Destination $file
} catch {
    Remove-Item -Force -Path $part -ErrorAction SilentlyContinue
    throw
} finally {
    & docker compose --env-file $script:EnvFile -f $script:ComposeFile exec -T postgres rm -f $tmpInContainer $verifyInContainer *> $null
}

$cutoff = (Get-Date).AddDays(-$RetentionDays)
$expired = @(Get-ChildItem -Path $script:BackupDir -Filter 'erp-standalone-*.dump' -File |
    Where-Object { $_.LastWriteTime -lt $cutoff })
if ($expired.Count -gt 0) {
    $expired | Remove-Item -Force
}
Write-Info "清理过期备份：$($expired.Count) 个（保留 $RetentionDays 天）"

Write-Step "备份完成"
$sizeKb = [math]::Round((Get-Item $file).Length / 1KB, 1)
Write-Info "文件：$file（$sizeKb KB）"
Write-Info "备份目录：$($script:BackupDir)"
Write-Info "恢复步骤见 infra/standalone/README.md 的「备份与恢复」"
