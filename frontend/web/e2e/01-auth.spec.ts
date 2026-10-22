import { expect, test } from './fixtures'
import { loginViaUi } from './helpers/ui'

// 这一组必须从“未登录”开始，不能复用 admin 的 storageState
test.use({ storageState: { cookies: [], origins: [] } })

test('E2E-01 登录成功 → 进工作台', async ({ page }) => {
  await loginViaUi(
    page,
    process.env.E2E_USERNAME ?? 'admin',
    process.env.E2E_PASSWORD ?? 'Admin@12345',
  )

  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByText('当前账号权限')).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '商品与 SKU' })).toBeVisible()

  const token = await page.evaluate(() => localStorage.getItem('erp.token'))
  expect(token).toBeTruthy()
})

test('E2E-02 登录失败 → 统一提示，不区分用户是否存在', async ({ page, run }) => {
  // 用不存在的用户名：既验证“不泄露账号是否存在”，也避免把 admin 的错误次数打满导致锁定
  const missingUser = `no-such-${run.runId}-02`

  await loginViaUi(page, missingUser, 'definitely-wrong-password')

  await expect(page.getByText(/用户名或密码错误/).filter({ visible: true }).last()).toBeVisible()
  await expect(page).toHaveURL(/\/login/)
  const token = await page.evaluate(() => localStorage.getItem('erp.token'))
  expect(token).toBeNull()
})

test('E2E-03 无 token 访问业务页 → 跳登录并带回跳地址', async ({ page }) => {
  await page.goto('/products')

  await expect(page).toHaveURL(/\/login\?redirect=/)
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/products')
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible()
  await expect(page.getByRole('button', { name: /登\s*录/ })).toBeVisible()
})
