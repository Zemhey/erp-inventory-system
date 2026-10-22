import { expect, test } from '@playwright/test'
import { adminStorageFile } from './helpers/paths'
import { loginViaUi } from './helpers/ui'

/**
 * 登录只在这里走一次 UI，之后用例复用 storageState。
 * 这样 token 不会出现在用例里，也顺带验证了登录页本身可用。
 */
test('登录并保存 storageState', async ({ page }) => {
  await loginViaUi(
    page,
    process.env.E2E_USERNAME ?? 'admin',
    process.env.E2E_PASSWORD ?? 'Admin@12345',
  )
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByText('当前账号权限')).toBeVisible()
  await page.context().storageState({ path: adminStorageFile })
})
