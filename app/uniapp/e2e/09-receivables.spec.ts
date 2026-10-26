import { expect, test, type Page } from '@playwright/test'
import { loginAs } from './helpers/identity'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 应收与收款（FR-SAL-07/08）。
 *
 * 三条用例依次验证：客户汇总与明细自洽 → 部分核销真的动了余额 → 超额被后端逐字拒。
 *
 * ★ 「超额不前端拦截」是这条链的关键性质：页面只给一条非阻断提示，请求照发。
 *   如果前端先把超额挡掉，我们永远看不到后端那句带「应收 id / 余额 / 本次核销额」
 *   的消息，也就失去了「账实一致的口径只有一个」这条保障。
 *
 * ★ 汇总卡是**整个客户**的合计（/receivables/summary 按 customerId 聚合），
 *   本轮运行里同一客户已被三张单出货，所以这里断言的是
 *   「汇总 = 明细之和」与「出货 − 退货 − 回款 = 余额」这两条恒等式，
 *   而不是把某一笔单据的金额写死成汇总值。
 */

const modalConfirm = (page: Page) => page.locator('.uni-modal__btn_primary')
const amountInput = (page: Page) => page.locator('.receipt-amount-input input')
const summaryCells = (page: Page) =>
  page.locator('[data-testid="customer-summary"] .cell-value')

/** 把 `¥1990.00` 读回整数分（展示层除过 100，断言时乘回来）。 */
async function cellFen(page: Page, index: number): Promise<number> {
  const text = await summaryCells(page).nth(index).innerText()
  return Math.round(Number(text.replace(/[^\d.-]/g, '')) * 100)
}

async function cardSum(page: Page, attribute: string): Promise<number> {
  const values = await page
    .locator('.debt-card')
    .evaluateAll((cards, attribute) => cards.map((card) => Number(card.getAttribute(attribute) ?? 0)), attribute)
  return values.reduce((sum, value) => sum + value, 0)
}

/** 余额展示文本 → 整数分。 */
async function balanceFen(page: Page): Promise<number> {
  const text = await page.locator('.hero-value.amount').innerText()
  return Math.round(Number(text.replace(/[^\d.-]/g, '')) * 100)
}

test('TC-MOB-008 应收：客户汇总等于明细之和，部分收款后余额与核销记录同步', async ({ page }) => {
  const seed = readSeed()

  await page.goto('/#/pages/receivables/receivables')
  await expect(page.locator('.chip').first()).toBeVisible()
  await page.locator('.chip', { hasText: '未结清' }).click()

  const card = page.locator(`.debt-card[data-receivable-id="${seed.receivable.id}"]`)
  await expect(card).toBeVisible()
  await expect(card).toContainText(`出货单 #${seed.soDebt.id}`)
  await expect(card).toHaveAttribute('data-shipped', String(seed.expected.soDebtAmount))
  await expect(card).toHaveAttribute('data-balance', String(seed.receivable.balance))

  // 点客户名把范围收到这一个客户，汇总卡才会出现（summary 接口必须带 customerId）
  await card.locator('.meta-text.link').click()
  await expect(page.locator('[data-testid="customer-summary"]')).toBeVisible()

  // 恒等式一：汇总的出货额 = 列表里各笔应收的出货额之和
  await expect
    .poll(async () => (await cellFen(page, 0)) - (await cardSum(page, 'data-shipped')), {
      message: '客户汇总的出货金额应等于明细之和',
    })
    .toBe(0)
  // 恒等式二：出货 − 退货 − 回款 = 余额（页面只复述后端给的四个数）
  const shipped = await cellFen(page, 0)
  const returned = await cellFen(page, 1)
  const received = await cellFen(page, 2)
  const balance = await cellFen(page, 3)
  expect(shipped - returned - received).toBe(balance)
  // 恒等式三：汇总余额 = 各笔未结余额之和
  await expect
    .poll(async () => (await cardSum(page, 'data-balance')) - balance)
    .toBe(0)
  await shot(page, 'receivables-list-summary')

  await card.click()
  await expect(page).toHaveURL(new RegExp(`pages/receivables/detail\\?id=${seed.receivable.id}`))
  await expect(page.locator('[data-testid="balance-check"] .check-ok')).toContainText('一致')
  expect(await balanceFen(page)).toBe(seed.receivable.balance)

  const partialYuan = 100
  const partialFen = partialYuan * 100
  const balanceAfter = seed.receivable.balance - partialFen

  await amountInput(page).fill(String(partialYuan))
  await page.locator('.receipt-remark-input input').fill('E2E 部分回款')
  await page.locator('.btn-receipt').click()
  await modalConfirm(page).click()

  await expect(page.locator('.receipt-done')).toContainText('已生成收款单')
  await expect(page.locator('.hero-value.amount')).toHaveText(
    `¥${(balanceAfter / 100).toFixed(2)}`,
  )
  // 核销记录必须能追到这一张应收，而不是只显示一张收款单
  await expect(page.locator('.receipt-card').first()).toContainText(
    `核销 #${seed.receivable.id} ¥${partialYuan.toFixed(2)}`,
  )
  await expect(page.locator('[data-testid="balance-check"] .check-ok')).toContainText('一致')
  await shot(page, 'receivable-after-partial-receipt')
})

test('TC-MOB-009 应收：超额核销被后端拒绝，消息逐字显示、余额分毫未动', async ({ page }) => {
  const seed = readSeed()

  await page.goto(`/#/pages/receivables/detail?id=${seed.receivable.id}`)
  await expect(page.locator('.btn-receipt')).toBeVisible()

  // 余额从页面现读，不写死「部分回款之后应是 29800」：
  // 本用例真正要证的是「消息里的余额 = 账上此刻的余额」，与前面收过多少无关。
  const balanceBefore = await balanceFen(page)
  expect(balanceBefore, '超额用例需要一张仍有余额的应收').toBeGreaterThan(0)

  const overFen = 99999900
  await amountInput(page).fill('999999')
  // 前端只提示、不拦：拦了就只能看到自造文案，看不到后端那条账实口径
  await expect(page.locator('.form-warn')).toContainText('由后端拒绝')

  await page.locator('.btn-receipt').click()
  await modalConfirm(page).click()

  const error = page.locator('[data-testid="receipt-error"]')
  await expect(error).toBeVisible()
  // ReceiptService:130 的原文：核销超额：应收 N 余额 X 分，本次核销 Y 分
  const message = (await error.innerText()).trim()
  expect(message).toContain('核销超额')
  expect(message).toContain(`余额 ${balanceBefore} 分`)
  expect(message).toContain(`本次核销 ${overFen} 分`)
  await shot(page, 'receivable-over-allocation-rejected')

  // 被拒的收款没有改变账
  expect(await balanceFen(page)).toBe(balanceBefore)
  await expect(page.locator('[data-testid="balance-check"] .check-ok')).toContainText('一致')
})

test('TC-MOB-010 应收：只读身份看不到收款表单，也登记不了收款', async ({ page }) => {
  const seed = readSeed()

  await loginAs(page, seed.viewer.username, seed.viewer.password)

  await page.goto(`/#/pages/receivables/detail?id=${seed.receivable.id}`)
  await expect(page.locator('.hero-value.amount')).toBeVisible()
  await expect(page.locator('.btn-receipt')).toHaveCount(0)
  await expect(page.locator('.receipt-amount-input')).toHaveCount(0)
  await expect(page.locator('.action-note-text')).toContainText('没有收款权限（sal:receipt）')
  await shot(page, 'receivable-detail-viewer')
})
