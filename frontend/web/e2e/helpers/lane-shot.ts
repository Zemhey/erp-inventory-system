import { mkdirSync } from 'node:fs'
import path from 'node:path'
import type { Page } from '@playwright/test'
import { artifactsDir } from './paths'

/**
 * E1 证据截图（真实后端 + 真实 PG）。
 *
 * 目录 `.e2e-artifacts/screenshots/e1/` 被 .gitignore 忽略：截图是**运行证据**，
 * 不随代码提交，但报告里逐张登记路径与它对应的断言。
 */
const shotDir = path.join(artifactsDir, 'screenshots', 'e1')

export async function e1Shot(page: Page, name: string): Promise<string> {
  mkdirSync(shotDir, { recursive: true })
  const file = path.join(shotDir, `${name}.png`)
  await page.screenshot({ path: file, fullPage: true })
  console.log(`[e1] 截图：${file}`)
  return file
}
