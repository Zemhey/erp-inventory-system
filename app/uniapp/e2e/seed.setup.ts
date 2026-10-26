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
  // ★ origin 必须和 Playwright 的 baseURL 同一个 host:port。
  //   storageState 是按 origin 精确装载的，写死 3005 而实跑 3020 时，
  //   所有 app project 用例会静默地"没登录" —— 症状是满屏跳登录页，最难归因。
  const origin = new URL(process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3005').origin
  const state = {
    cookies: [],
    origins: [
      {
        origin,
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
