import { spawnSync } from 'node:child_process'

/**
 * 直连开发库执行 SQL（仅用于 E2E 造 VIEWER 账号与清理本 runId 数据）。
 *
 * 默认走 `docker exec erp-postgres psql`；若设置 E2E_DATABASE_URL，
 * 则改用本机 psql（为未来 CI 的 PG service 预留，不改变本地用法）。
 */
function psql(sql: string): string {
  const databaseUrl = process.env.E2E_DATABASE_URL
  const baseArgs = ['-v', 'ON_ERROR_STOP=1', '-X', '-q', '-t', '-A', '-F', '\t']
  const command = databaseUrl ? 'psql' : 'docker'
  const args = databaseUrl
    ? [databaseUrl, ...baseArgs]
    : [
        'exec',
        '-i',
        process.env.E2E_PG_CONTAINER ?? 'erp-postgres',
        'psql',
        '-U',
        process.env.E2E_PG_USER ?? 'erp',
        '-d',
        process.env.E2E_PG_DB ?? 'erp',
        ...baseArgs,
      ]

  const result = spawnSync(command, args, {
    input: sql,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  })
  if (result.error) {
    throw new Error(
      `无法执行 ${command}：${result.error.message}。E2E 需要 Docker（erp-postgres）或 E2E_DATABASE_URL。`,
    )
  }
  if (result.status !== 0) {
    throw new Error(`${command} 执行失败（exit ${result.status}）：\n${result.stderr || result.stdout}`)
  }
  return (result.stdout ?? '').trim()
}

export function querySql(sql: string): string {
  return psql(sql)
}

export function queryScalar(sql: string): string {
  return querySql(sql).split('\n')[0]?.trim() ?? ''
}

/** 单引号字符串字面量（runId 已由调用方校验字符集）。 */
export function sqlString(value: string): string {
  return `'${value.replaceAll("'", "''")}'`
}
