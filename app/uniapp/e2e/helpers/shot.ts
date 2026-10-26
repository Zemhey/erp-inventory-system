import { mkdirSync } from 'node:fs'
import path from 'node:path'
import type { Page } from '@playwright/test'
import { artifactsDir } from './paths'

const shotDir = path.join(artifactsDir, 'screenshots')

/**
 * 把当前页面截图存到 .e2e-artifacts/screenshots/<name>.png（仓库外、已 gitignore）。
 * 失败用例本来就有截图，这里要的是**通过时的正片**，用来肉眼确认移动端没画歪。
 */
export async function shot(page: Page, name: string): Promise<void> {
  mkdirSync(shotDir, { recursive: true })
  await page.screenshot({ path: path.join(shotDir, `${name}.png`), fullPage: true })
}
