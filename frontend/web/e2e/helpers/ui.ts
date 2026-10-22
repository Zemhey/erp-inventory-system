import { expect, type Locator, type Page } from '@playwright/test'

export async function loginViaUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
}

/** el-select 的可访问名称来自 form-item 标签（如 “* 供应商”），不是 placeholder。 */
export function formSelect(dialog: Locator, name: string | RegExp): Locator {
  return dialog.getByRole('combobox', { name })
}

/** 明细表格第 index 行（0 起）；Element Plus 的表头单独占第 1 个 row。 */
export function lineRow(dialog: Locator, index: number): Locator {
  return dialog.getByRole('row').nth(index + 1)
}

/** 打开 Element Plus 下拉并点选可见选项（不依赖内部 class）。 */
export async function selectOption(
  page: Page,
  select: Locator,
  text: string | RegExp,
): Promise<void> {
  // el-select 的 input 被占位符 span 覆盖，普通 click 会被 actionability 挡住；
  // force 让事件落到覆盖层（同属 select 外壳）从而正常展开下拉。
  await select.click({ force: true })
  const option = page.getByRole('option').filter({ hasText: text, visible: true }).first()
  await expect(option).toBeVisible()
  await option.click()
  // 必须等下拉真正收起：否则上一个 popper 会拦截下一个 select 的点击
  await expect(option).toBeHidden()
}

export function visibleDialog(page: Page, title: string | RegExp): Locator {
  return page.getByRole('dialog').filter({ hasText: title, visible: true }).last()
}

export function dataRow(page: Page, text: string | RegExp): Locator {
  return page.getByRole('row').filter({ hasText: text }).first()
}

/** el-input-number：先填值再失焦，确保 v-model 与校验都更新。 */
export async function fillNumber(input: Locator, value: number | string): Promise<void> {
  await input.fill(String(value))
  await input.press('Tab')
}

/** Element Plus 的确认框（作废/出货等）。 */
export async function confirmBox(
  page: Page,
  text: string | RegExp,
  button: string,
): Promise<void> {
  const box = page.getByRole('dialog').filter({ hasText: text, visible: true }).last()
  await expect(box).toBeVisible()
  await box.getByRole('button', { name: button, exact: true }).click()
}
