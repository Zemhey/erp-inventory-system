import { expect, test } from './fixtures'
import { querySql } from './helpers/db'
import { runTag } from './helpers/run'
import { seedBase } from './helpers/seed'
import { dataRow, loginViaUi, readToken } from './helpers/ui'

/**
 * B1 · 租户 UI（FR-TNT-07/08）的浏览器级验收。
 *
 * 三条边界：
 *   1. 平台租户管理员：能看到租户管理菜单/页面，平台租户与当前租户的停用按钮被禁用；
 *   2. 多租户成员（VIEWER，同时属于 default + demo）：顶栏出现切换器；切换后 token
 *      真的换了一个（tid 变化），且**跨租户业务数据仍 404、同租户 200**；
 *   3. 单租户用户（FINANCE）：没有切换器；没有租户管理菜单；直接访问 URL 被路由
 *      守卫弹回；直接调接口仍是 403。
 *
 * ★ 断言"切换后 token 变化"不是形式主义：如果前端只改显示，token 不变，
 *   下一条业务请求仍会落在旧租户 —— 这正是需求禁止的假象。
 */

const PASSWORD = process.env.E2E_PASSWORD ?? 'Admin@12345'

test.describe('平台租户管理（平台租户管理员）', () => {
  test('E2E-20 平台管理员能看到租户管理页；平台租户/当前租户不可停用', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/dashboard$/)

    // 菜单入口（系统 → 租户管理）
    await page.locator('.menu .el-sub-menu__title', { hasText: '系统' }).click()
    const tenantMenu = page.getByRole('menuitem', { name: '租户管理' })
    await expect(tenantMenu).toBeVisible()
    await tenantMenu.click()

    await expect(page).toHaveURL(/\/tenants$/)
    await expect(page.getByRole('heading', { name: '租户管理' })).toBeVisible()
    await expect(dataRow(page, 'default')).toBeVisible()
    await expect(dataRow(page, 'demo')).toBeVisible()
    await expect(page.getByText('平台租户', { exact: true })).toBeVisible()

    // 平台租户（同时是当前租户）的停用按钮必须被禁用；
    // 另一个租户的停用按钮可用（但我们不点，避免在本轮改变共享库状态）。
    await expect(
      dataRow(page, 'default').getByRole('button', { name: '停用' }),
    ).toBeDisabled()
    await expect(
      dataRow(page, 'demo').getByRole('button', { name: '停用' }),
    ).toBeEnabled()
  })
})

test.describe('多租户切换（VIEWER 属于 default + demo）', () => {
  // 这一组必须真正用 VIEWER 登录，不能复用 admin 的 storageState
  test.use({ storageState: { cookies: [], origins: [] } })

  test('E2E-21 切换租户重新签发 token；跨租户业务数据 404、同租户 200', async ({
    page,
    api,
    request,
    run,
  }) => {
    // 租户 1 的探针商品（由 admin API 创建）；demo 的商品由 V16 种子给出
    const seed = await seedBase(api, runTag(run, 'TNTUI'))
    const demoProductId = Number(
      querySql("SELECT id FROM products WHERE tenant_id = 2 AND code = 'DEMO-P-001'"),
    )
    expect(demoProductId, 'E2E 库缺少 V16 的演示商品 DEMO-P-001').toBeGreaterThan(0)

    await loginViaUi(page, run.viewerUsername, PASSWORD)
    await expect(page).toHaveURL(/\/dashboard$/)

    // 多租户成员：顶栏有切换器，当前是主租户 default
    const chip = page.locator('.tenant-chip')
    await expect(chip).toContainText('默认租户')
    await expect(page.getByTitle('切换租户')).toHaveCount(1)

    const homeToken = await readToken(page)
    await chip.click()
    const demoItem = page.locator('.el-dropdown-menu__item:visible', { hasText: '演示租户' })
    await expect(demoItem).toBeVisible()
    await demoItem.click()

    // 等 UI 真正切到 demo（chip 文案变化 = 后端已返回新 token 且 store 已覆盖）
    await expect(page.locator('.tenant-chip')).toContainText('演示租户')
    const demoTenantToken = await readToken(page)
    expect(demoTenantToken, '切换租户必须重新签发 token，不能只改显示').not.toBe(homeToken)

    // ★ 跨租户 404 + 同租户 200（正对照）：用 demo 会话的 token 直接打接口
    const crossTenant = await request.get(`/api/v1/products/${seed.product.id}`, {
      headers: { Authorization: `Bearer ${demoTenantToken}` },
    })
    expect(crossTenant.status(), 'demo 会话读租户 1 的商品必须 404').toBe(404)
    const ownTenant = await request.get(`/api/v1/products/${demoProductId}`, {
      headers: { Authorization: `Bearer ${demoTenantToken}` },
    })
    expect(ownTenant.status(), 'demo 会话读 demo 商品必须 200').toBe(200)

    // 反向也成立：home token 读 demo 商品 404（旧 token 仍指向旧租户）
    const reverse = await request.get(`/api/v1/products/${demoProductId}`, {
      headers: { Authorization: `Bearer ${homeToken}` },
    })
    expect(reverse.status(), 'default 会话读 demo 商品必须 404').toBe(404)

    // UI 侧：切换后商品列表只剩 demo 的数据（router-view 以 tenantId 为 key 重新挂载）
    await page.goto('/products')
    await expect(dataRow(page, 'DEMO-P-001')).toBeVisible()
    await expect(page.getByRole('row').filter({ hasText: seed.product.code })).toHaveCount(0)

    // 切回主租户：token 再变一次，数据边界回到 default
    await expect(page.locator('.tenant-chip')).toContainText('演示租户')
    await page.locator('.tenant-chip').click()
    const defaultItem = page.locator('.el-dropdown-menu__item:visible', { hasText: '默认租户' })
    await expect(defaultItem).toBeVisible()
    await defaultItem.click()
    await expect(page.locator('.tenant-chip')).toContainText('默认租户')
    const backToken = await readToken(page)
    expect(backToken).not.toBe(demoTenantToken)

    const backOwn = await request.get(`/api/v1/products/${seed.product.id}`, {
      headers: { Authorization: `Bearer ${backToken}` },
    })
    expect(backOwn.status()).toBe(200)
    const backCross = await request.get(`/api/v1/products/${demoProductId}`, {
      headers: { Authorization: `Bearer ${backToken}` },
    })
    expect(backCross.status()).toBe(404)
  })
})

test.describe('单租户用户与平台权限边界', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('E2E-22 单租户用户没有切换器；看不到租户管理；直接调接口 403', async ({
    page,
    request,
    run,
  }) => {
    await loginViaUi(page, run.financeUsername, PASSWORD)
    await expect(page).toHaveURL(/\/dashboard$/)

    // 单租户：显示名称但没有切换器（多租户的 chip 才有 title="切换租户"）
    await expect(page.locator('.tenant-chip')).toContainText('默认租户')
    await expect(page.getByTitle('切换租户')).toHaveCount(0)

    // 菜单里没有租户管理
    await page.locator('.menu .el-sub-menu__title', { hasText: '系统' }).click()
    await expect(page.getByRole('menuitem', { name: '租户管理' })).toHaveCount(0)

    // 直接访问 URL：路由守卫按权限弹回工作台（前端不是安全边界，但体验上不该进得去）
    await page.goto('/tenants')
    await expect(page).toHaveURL(/\/dashboard$/)

    // 直接调接口：后端还是 403（前端拦截不是安全边界）
    const login = await request.post('/api/v1/auth/login', {
      data: { username: run.financeUsername, password: PASSWORD },
    })
    expect(login.status()).toBe(200)
    const payload = (await login.json()) as { data: { token: string } }
    const forbidden = await request.get('/api/v1/tenants', {
      headers: { Authorization: `Bearer ${payload.data.token}` },
    })
    expect(forbidden.status()).toBe(403)
    const body = (await forbidden.json()) as { code: string }
    expect(body.code).toBe('IAM_FORBIDDEN')
  })
})
