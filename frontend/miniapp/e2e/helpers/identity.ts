import { expect, type Page } from '@playwright/test'

/**
 * 在同一个浏览器上下文里换一个身份登录。
 *
 * app project 的 storageState 是 admin，负向用例（无权限身份看不到写按钮）必须换成
 * 权限更小的账号。这里**不走 API 换 token**，而是清掉 localStorage 后真走登录表单：
 * 被验证的性质是「页面按前端权限副本隐藏按钮」，那副本是登录时写进 storage 的，
 * 只有真登录才算覆盖了同一条路径。
 *
 * ★ 定位方式沿用 01-login 的结论：uni-app H5 的 `<input>` 把 placeholder 渲染成
 *   同级占位元素，input 本身没有 placeholder 属性，getByPlaceholder 一律找不到。
 */
const usernameInput = (page: Page) => page.locator('.field', { hasText: '用户名' }).locator('input')
const passwordInput = (page: Page) => page.locator('.field', { hasText: '密码' }).locator('input')

export async function loginAs(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/#/pages/login/login')
  await page.evaluate(() => window.localStorage.clear())
  // 清完必须真重载：登录态是模块初始化时从 storage 读进来的，
  // 只换 hash 不会重跑，页面会继续用内存里的旧权限副本 —— 那用例会假绿。
  await page.reload()

  await expect(page.locator('.login-btn')).toBeVisible()
  await usernameInput(page).fill(username)
  await passwordInput(page).fill(password)
  await page.locator('.login-btn').click()

  await expect(page).toHaveURL(/pages\/dashboard\/dashboard/, { timeout: 15_000 })
  const stored = await page.evaluate(() => localStorage.getItem('erp.permissions') ?? '')
  expect(stored, `${username} 登录后应拿到权限清单`).not.toBe('')
}
