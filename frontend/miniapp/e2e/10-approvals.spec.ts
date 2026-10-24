import { expect, test, type Page } from '@playwright/test'
import { api } from './helpers/api'
import { loginAs } from './helpers/identity'
import { readSeed } from './helpers/load'
import { shot } from './helpers/shot'

/**
 * 待我审批（FR-APS-02）。
 *
 * ★ 为什么要在本文件里临时启用 SO-STD 审批流、并在末尾还原：
 *   V14 把三条种子流程一律置为 enabled=FALSE —— 「引入一项新治理能力，不能默认改变
 *   存量行为」。而单据侧的审批闸门只挂在 `approve()` 上（不拦 ship），
 *   所以启用期间只要不去审核新出货单就不影响别的用例；本文件排在最后，
 *   且 afterAll 无条件还原，不让启用态泄漏到下一次运行。
 *
 * ★ 状态机是审批自己的七态，色板与词表都和单据七态分开：
 *   这里断言的「审批通过 / 审批驳回 / 已撤回 / 已转办」在单据页是另一套说法。
 */

interface FlowRow {
  id: number
  code: string
  bizType: string
  enabled: boolean
}

interface OrderDetail {
  id: number
  orderNo: string
  totalAmount: number
}

interface Instance {
  id: number
  bizRefNo: string
  status: string
  currentStep: number
  steps: Array<{ stepNo: number; approverPermission: string; status: string }>
}

const instances: Instance[] = []
let flowId = 0

test.beforeAll(async () => {
  const seed = readSeed()
  const token = seed.admin.token

  const flows = await api<{ items: FlowRow[] }>('GET', '/approvals/flows', { token })
  const salesFlow = flows.items.find((flow) => flow.code === 'SO-STD')
  if (!salesFlow) throw new Error('[e2e] 找不到 SO-STD 审批流种子，审批用例无从跑起')
  flowId = salesFlow.id

  await api('POST', `/approvals/flows/${flowId}/enabled?enabled=true`, { token })

  // 一张单据只能挂一个未结实例，所以按用途分张：0 同意 / 1 驳回 / 2 撤回 / 3 转办
  for (const order of seed.approvalOrders) {
    const detail = await api<OrderDetail>('GET', `/sales-orders/${order.id}`, { token })
    const instance = await api<Instance>('POST', '/approvals', {
      token,
      body: {
        bizType: 'SALES_ORDER',
        bizId: detail.id,
        bizRefNo: detail.orderNo,
        amount: detail.totalAmount,
      },
    })
    if (instance.status !== 'PENDING') {
      throw new Error(`[e2e] 提交审批后应为 PENDING，实际 ${instance.status}`)
    }
    // 阈值 50 万分以下的第二节点应被跳过：实例只走第一节点，一次同意即终态
    const active = instance.steps.filter((step) => step.status === 'PENDING')
    if (active.length !== 1 || active[0].approverPermission !== 'sal:approve') {
      throw new Error(
        `[e2e] 期望只有 1 个 PENDING 节点(sal:approve)，实际 ` +
          instance.steps.map((step) => `${step.stepNo}:${step.status}`).join(','),
      )
    }
    instances.push(instance)
  }
})

test.afterAll(async () => {
  // 还原启用态：不还原就等于把「单据审核必须先过审批」这件事偷偷留给下一条用例和下一次运行
  if (flowId) {
    await api('POST', `/approvals/flows/${flowId}/enabled?enabled=false`, {
      token: readSeed().admin.token,
    })
  }
})

const modalConfirm = (page: Page) => page.locator('.uni-modal__btn_primary')

async function openInstance(page: Page, instance: Instance): Promise<void> {
  await page.goto(`/#/pages/approvals/detail?id=${instance.id}`)
  await expect(page.locator('.order-no')).toContainText(instance.bizRefNo)
}

test('TC-MOB-011 待我审批：按节点权限筛出待办，同意后进入审批通过终态', async ({ page }) => {
  await page.goto('/#/pages/approvals/approvals')
  await expect(page.locator('.mode-title')).toHaveText('待我审批')
  // 四条都在飞、且第一节点要 sal:approve（ADMIN 的 sal:* 覆盖它）→ 四条都该是"我的"
  await expect(page.locator('.approval-card')).toHaveCount(instances.length)
  for (const instance of instances) {
    await expect(page.locator(`.approval-card[data-biz-ref-no="${instance.bizRefNo}"]`)).toBeVisible()
  }
  await shot(page, 'approvals-todo-list')

  await openInstance(page, instances[0])
  await expect(page.locator('.status-tag')).toHaveText('等待审批')
  // 第二节点被阈值跳过，轨迹里留的是 SKIPPED 快照而不是消失
  await expect(page.locator('.step-card[data-step-no="2"] .step-tag')).toHaveText('已跳过')
  await expect(page.locator('[data-action="approve"]')).toBeVisible()

  await page.locator('.remark-input input').fill('E2E 同意意见')
  await page.locator('[data-action="approve"]').click()
  await modalConfirm(page).click()

  await expect(page.locator('.status-tag')).toHaveText('审批通过')
  await expect(page.locator('[data-testid="approval-terminal"]')).toContainText('终态')
  await expect(page.locator('[data-action="approve"]')).toHaveCount(0)
  await expect(page.locator('.step-card[data-step-no="1"] .step-remark')).toContainText(
    'E2E 同意意见',
  )
  await shot(page, 'approval-after-approve')
})

test('TC-MOB-012 待我审批：驳回是终态，重提是新实例', async ({ page }) => {
  const target = instances[1]

  await openInstance(page, target)
  await page.locator('[data-action="reject"]').click()
  await modalConfirm(page).click()

  await expect(page.locator('.status-tag')).toHaveText('审批驳回')
  await expect(page.locator('[data-testid="approval-terminal"]')).toContainText(
    '会生成一条新的审批实例',
  )
  await expect(page.locator('[data-action="reject"]')).toHaveCount(0)
  await shot(page, 'approval-after-reject')
})

test('TC-MOB-013 待我审批：发起人可以撤回自己提交的审批', async ({ page }) => {
  const target = instances[2]

  await openInstance(page, target)
  // 提交用的就是 admin 的 token，发起人正是当前登录人
  const withdraw = page.locator('[data-action="withdraw"]')
  await expect(withdraw).toBeVisible()
  await withdraw.click()
  await modalConfirm(page).click()

  await expect(page.locator('.status-tag')).toHaveText('已撤回')
  await expect(page.locator('[data-action="approve"]')).toHaveCount(0)
  await shot(page, 'approval-after-withdraw')
})

test('TC-MOB-014 待我审批：转办要有 aps:approve 的账号，只读身份既进不了待办也点不了同意', async ({
  page,
}) => {
  const seed = readSeed()
  const target = instances[3]

  await openInstance(page, target)
  await page.locator('.transfer-target input').fill(String(seed.approver.id))
  await page.locator('[data-action="transfer"]').click()
  await modalConfirm(page).click()

  await expect(page.locator('.status-tag')).toHaveText('已转办')
  await expect(page.locator('.atone-handover')).toBeVisible()
  await shot(page, 'approval-after-transfer')

  // 再转给只读账号：目标人不具备 aps:approve，等于要把审批甩给没有资格的人 —— 必须被拒
  await page.locator('.transfer-target input').fill(String(seed.viewer.id))
  await page.locator('[data-action="transfer"]').click()
  await modalConfirm(page).click()
  await expect(page.locator('[data-testid="approval-error"]')).toContainText('转办目标账号')
  await shot(page, 'approval-transfer-rejected')

  // 只读身份：有 aps:read 能看进度，但节点权限一条不占 → 待办为空、详情无动作
  await loginAs(page, seed.viewer.username, seed.viewer.password)
  await page.goto('/#/pages/approvals/approvals')
  await expect(page.locator('.empty-block')).toContainText('没有需要你处理的审批')

  await page.goto(`/#/pages/approvals/detail?id=${target.id}`)
  await expect(page.locator('.status-tag')).toHaveText('已转办')
  await expect(page.locator('[data-action="approve"]')).toHaveCount(0)
  await expect(page.locator('[data-action="reject"]')).toHaveCount(0)
  await expect(page.locator('[data-testid="approval-error"]')).toHaveCount(0)
  await expect(page.locator('.action-note-text')).toContainText('没有 aps:approve')
  await shot(page, 'approval-detail-viewer')
})
