import { expect, test, type Page } from '@playwright/test'
import { loginAs } from './helpers/identity'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 报价单（FR-SAL-05）。
 *
 * 四条用例覆盖三件事：
 * 1. 状态机动作确实按镜像表给出（草稿只给 提交/作废，已提交给 审核/驳回/作废，
 *    已审核给 转化/作废）—— 按钮集合本身就是断言对象；
 * 2. 转化产出一张真出货单，且报价单落进 COMPLETED（不是停在 APPROVED）；
 * 3. 无权限身份看到的是同一张单、同样的状态，但一个写按钮都没有。
 */

const modalConfirm = (page: Page) => page.locator('.uni-modal__btn_primary')

/** 打开某张报价单详情（从列表按单号定位，不靠 ID 猜路由）。 */
async function openQuote(page: Page, quoteNo: string, chipLabel: string): Promise<void> {
  await page.goto('/#/pages/quotes/quotes')
  await expect(page.locator('.chip').first()).toBeVisible()
  await page.locator('.chip', { hasText: chipLabel }).click()
  const card = page.locator(`.quote-card[data-quote-no="${quoteNo}"]`)
  await expect(card).toBeVisible()
  await card.click()
}

const actionButtons = (page: Page) => page.locator('.action-row .action-btn')

test('TC-MOB-001 报价单：草稿 → 提交 → 审核 → 转出货单', async ({ page }) => {
  const seed = readSeed()

  await openQuote(page, seed.quoteFlow.quoteNo, '草稿')
  await expect(page).toHaveURL(new RegExp(`pages/quotes/detail\\?id=${seed.quoteFlow.id}`))
  await expect(page.locator('.order-no')).toHaveText(seed.quoteFlow.quoteNo)
  await expect(page.locator('.status-tag')).toHaveText('草稿')
  await expect(page.locator('.hero-value').nth(0)).toHaveText(String(seed.quoteFlow.totalQty))
  // 草稿态只可能给这两个动作，顺序即 ALLOWED_ACTIONS.DRAFT
  await expect(actionButtons(page)).toHaveText(['提交审核', '作废'])
  await shot(page, 'quotes-detail-draft')

  await page.locator('[data-action="submit"]').click()
  await modalConfirm(page).click()
  await expect(page.locator('.status-tag')).toHaveText('已提交')
  await expect(actionButtons(page)).toHaveText(['审核通过', '驳回', '作废'])

  await page.locator('[data-action="approve"]').click()
  await modalConfirm(page).click()
  await expect(page.locator('.status-tag')).toHaveText('已审核')
  await expect(actionButtons(page)).toHaveText(['转为出货单', '作废'])
  await shot(page, 'quotes-detail-approved')

  await page.locator('[data-action="convert"]').click()
  await modalConfirm(page).click()
  // 转化把报价单推到 COMPLETED，并留下可点进去的出货单引用
  await expect(page.locator('.status-tag')).toHaveText('已完成')
  await expect(page.locator('.kv-value.link')).toContainText('出货单 #')
  await expect(actionButtons(page)).toHaveCount(0)
  await expect(page.locator('.action-note-text')).toContainText('已转为出货单')
  await shot(page, 'quotes-detail-converted')
})

test('TC-MOB-002 报价单：已提交可被驳回，驳回后不再提供写动作', async ({ page }) => {
  const seed = readSeed()

  await openQuote(page, seed.quoteReject.quoteNo, '已提交')
  await expect(page.locator('[data-action="reject"]')).toBeVisible()

  await page.locator('[data-action="reject"]').click()
  await modalConfirm(page).click()
  await expect(page.locator('.status-tag')).toHaveText('已驳回')
  // REJECTED 在单据状态机里能退回草稿，但移动端不暴露该动作；只剩作废
  await expect(actionButtons(page)).toHaveText(['作废'])
  await shot(page, 'quotes-detail-rejected')
})

test('TC-MOB-003 报价单：作废是终态，作废后动作区消失', async ({ page }) => {
  const seed = readSeed()

  await openQuote(page, seed.quoteVoid.quoteNo, '草稿')
  await page.locator('[data-action="void"]').click()
  await modalConfirm(page).click()

  await expect(page.locator('.status-tag')).toHaveText('已作废')
  await expect(actionButtons(page)).toHaveCount(0)
  await expect(page.locator('.action-note-text')).toContainText('已作废的报价单不可再流转')
  await shot(page, 'quotes-detail-voided')
})

test('TC-MOB-004 报价单：无 sal:quote 的只读身份看不到任何写按钮', async ({ page }) => {
  const seed = readSeed()

  await loginAs(page, seed.viewer.username, seed.viewer.password)

  // 这张单停在草稿、从没被有权限的用例动过：
  // 同一个状态下 admin 看得到两个按钮、它一个都看不到，才证明是权限在起作用。
  await openQuote(page, seed.quoteLocked.quoteNo, '草稿')
  await expect(page.locator('.status-tag')).toHaveText('草稿')
  await expect(actionButtons(page)).toHaveCount(0)
  await expect(page.locator('.action-note-text')).toContainText('没有 sal:quote 权限')
  await shot(page, 'quotes-detail-viewer')
})
