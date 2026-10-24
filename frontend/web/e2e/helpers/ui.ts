import { expect, type Locator, type Page } from '@playwright/test'

async function submitLogin(page: Page, username: string, password: string): Promise<void> {
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
}

export async function loginViaUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await submitLogin(page, username, password)
}

/**
 * 换一个账号登录。
 *
 * ★ 两次 goto 之间夹一次 `localStorage.clear()`，两个原因各占一半：
 *   - 不清 token 的话，路由守卫见到 token 会把 `/login` 弹回工作台
 *     （router/index.ts 的 `to.name === 'login' && token` 分支），第二个账号根本登不进去；
 *   - 第一次 goto 是为了**拿到应用自身的 origin** —— 在 `about:blank` 上读 localStorage
 *     会直接 SecurityError。clear 之后必须整页重载，内存里的 Pinia 会话才会跟着失效。
 *
 * ★ 第二次 goto 要容忍一次 `ERR_ABORTED`（E1 连跑两遍时实测复现过一次）：
 *   clear 之后，上一页可能还有"迟到"的请求以 401 返回，axios 拦截器会整页跳 `/login`，
 *   与这次 goto 抢导航。次数极少、与页面逻辑无关；重试一次即可，其它错误照常抛 ——
 *   不要用 sleep 掩盖，否则只是把竞态变成了更慢的竞态。
 */
async function gotoIgnoringRedirectRace(page: Page, path: string): Promise<void> {
  try {
    await page.goto(path)
  } catch (error) {
    if (!String(error).includes('ERR_ABORTED')) throw error
    await page.goto(path)
  }
}

export async function loginAsAnother(
  page: Page,
  username: string,
  password: string,
): Promise<void> {
  await page.goto('/login')
  await page.evaluate(() => localStorage.clear())
  await gotoIgnoringRedirectRace(page, '/login')
  await submitLogin(page, username, password)
}

/** 当前会话的 token（从 UI 登录后的 localStorage 读，用例里不出现任何硬编码凭据）。 */
export async function readToken(page: Page): Promise<string> {
  const token = await page.evaluate(() => localStorage.getItem('erp.token'))
  if (!token) throw new Error('localStorage 里没有 erp.token：登录未成功')
  return token
}

/** 当前会话的权限码集合（前端菜单/按钮显隐与路由守卫都读这一份）。 */
export async function readPermissions(page: Page): Promise<string[]> {
  return page.evaluate(() => JSON.parse(localStorage.getItem('erp.permissions') ?? '[]') as string[])
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

/**
 * 切换 el-tabs 页签。
 *
 * ★ 三个 pane **同时存在于 DOM**，只有一个可见（S5b 实测第 ① 条）。
 *   所以任何"页签内的按钮"都必须先在 `[role="tabpanel"]:visible` 里找，
 *   否则 `getByRole('button', {name:'查询'})` 会命中隐藏 pane 里的同名按钮并超时。
 */
export async function switchTab(page: Page, name: string): Promise<void> {
  await page.getByRole('tab', { name }).click()
  await expect(page.getByRole('tab', { name, selected: true })).toBeVisible()
}

/** 当前可见的页签内容面板（配合 switchTab 使用）。 */
export function visiblePanel(page: Page): Locator {
  return page.locator('[role="tabpanel"]:visible').first()
}

/** 直接读写 el-date-picker 的值：value-format 让 fill 的文本就是模型值（S5b 第 ③ 条）。 */
export async function fillDate(input: Locator, value: string): Promise<void> {
  await input.fill(value)
  await input.press('Enter')
}
