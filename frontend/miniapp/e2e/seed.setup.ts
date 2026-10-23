import { readFileSync, writeFileSync } from 'node:fs'
import { test } from '@playwright/test'
import { adminStorage, runFile, seedFile } from './helpers/paths'
import type { RunInfo } from './helpers/run'
import { seedRun } from './helpers/seed'

/**
 * Setup project：走真实接口造本轮数据，并把 admin 登录态写成 Playwright storageState。
 * 后续用例都带这个 storageState；只有 01-login 故意从零开始验证真实登录。
 */
test('seed business data', async () => {
  const run = JSON.parse(readFileSync(runFile, 'utf8')) as RunInfo
  const seed = await seedRun(run)
  writeFileSync(seedFile, JSON.stringify(seed, null, 2), 'utf8')

  const user = JSON.stringify({
    username: seed.admin.username,
    displayName: seed.admin.displayName,
    roles: seed.admin.roles,
  })
  const state = {
    cookies: [],
    origins: [
      {
        origin: 'http://127.0.0.1:3005',
        localStorage: [
          { name: 'erp.token', value: seed.admin.token },
          { name: 'erp.permissions', value: JSON.stringify(seed.admin.permissions) },
          { name: 'erp.user', value: user },
        ],
      },
    ],
  }
  writeFileSync(adminStorage, JSON.stringify(state, null, 2), 'utf8')

  test.info().annotations.push({
    type: 'seed',
    description:
      `${seed.runId}: ${seed.expected.stockBalanceRows} 行余额 / ` +
      `${seed.expected.stockLedgerRows} 条流水 / SO ${seed.soAction.orderNo} / PO ${seed.poReceive.orderNo}`,
  })
})
