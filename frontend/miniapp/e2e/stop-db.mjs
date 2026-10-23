import { spawnSync } from 'node:child_process'

/** 删除本泳道 E2E 的数据库容器（连同匿名数据卷），不碰共享库 15432。 */
const result = spawnSync('docker', ['rm', '-f', '-v', 'erp-m4b-pg'], {
  encoding: 'utf8',
  timeout: 120_000,
})
if (result.status !== 0) {
  console.error('[e2e:db] 删除容器失败：', result.stderr || result.stdout)
  process.exit(1)
}
console.log('[e2e:db] 已删除容器 erp-m4b-pg（含数据卷）')
