import { spawnSync } from 'node:child_process'

/**
 * 删除本泳道 E2E 的数据库容器（连同匿名数据卷），不碰共享库 15432。
 * ★ 默认 erp-m4b-pg 属于 M4B 泳道；并行泳道必须显式传 E2E_DB_CONTAINER，
 *   否则这条命令会删掉别人的库。删除前把目标打进日志，便于误用时看得见。
 */
const CONTAINER = process.env.E2E_DB_CONTAINER ?? 'erp-m4b-pg'

console.log(`[e2e:db] 即将删除容器 ${CONTAINER}（含数据卷）`)
const result = spawnSync('docker', ['rm', '-f', '-v', CONTAINER], {
  encoding: 'utf8',
  timeout: 120_000,
})
if (result.status !== 0) {
  console.error('[e2e:db] 删除容器失败：', result.stderr || result.stdout)
  process.exit(1)
}
console.log(`[e2e:db] 已删除容器 ${CONTAINER}（含数据卷）`)
