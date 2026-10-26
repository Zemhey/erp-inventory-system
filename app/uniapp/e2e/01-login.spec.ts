import { expect, test, type Page } from '@playwright/test'
import { shot } from './helpers/shot'

/**
 * 冒烟 1：真实登录。
 * 错误密码必须内联展示后端消息且不跳转（M3-S8 E2E-02 的同族缺陷）；
 * 正确密码后进工作台，并把 token 持久化到 localStorage。
 * 本用例在 login project（无 storageState），是唯一从零走登录表单的用例。
 *
 * ★ 定位方式：uni-app H5 的 `<input>` 把 placeholder 渲染成**同级占位元素**，
 *   input 本身没有 placeholder 属性，所以 getByPlaceholder 一律找不到 —— 用 .field + input。
 */
const usernameInput = (page: Page) => page.locator('.field', { hasText: '用户名' }).locator('input')
const passwordInput = (page: Page) => page.locator('.field', { hasText: '密码' }).locator('input')

test('登录：错误密码内联提示，正确密码进入工作台', async ({ page }) => {
  await page.goto('/#/pages/login/login')
  await expect(page.locator('.brand-name')).toHaveText('云裳进销存')

  await usernameInput(page).fill('admin')
  await passwordInput(page).fill('Wrong@12345')
  await page.locator('.login-btn').click()

  await expect(page.locator('.error-banner')).toBeVisible()
  await expect(page.locator('.error-banner')).toContainText('用户名或密码错误')
  // 仍在登录页，没有被 401 跳走
  await expect(page).toHaveURL(/pages\/login\/login/)
  await shot(page, 'login-inline-error')

  await passwordInput(page).fill('Admin@12345')
  await page.locator('.login-btn').click()

  await expect(page).toHaveURL(/pages\/dashboard\/dashboard/)
  await expect(page.getByText('可用库存合计')).toBeVisible()
  await shot(page, 'dashboard')

  const token = await page.evaluate(() => localStorage.getItem('erp.token'))
  expect(token).toBeTruthy()
  // ADMIN 拿到的是 "模块:*" 通配码；按钮显隐靠 hasPermission() 展开，不是逐条精确码
  const permissions = await page.evaluate(() => localStorage.getItem('erp.permissions'))
  expect(permissions ?? '').toContain('sal:*')
  expect(permissions ?? '').toContain('pur:*')
})