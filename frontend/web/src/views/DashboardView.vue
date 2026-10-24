<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Checked,
  Money,
  TrendCharts,
  Van,
  Warning,
} from '@element-plus/icons-vue'
import {
  approvalApi,
  fenToYuan,
  invApi,
  mdmApi,
  purApi,
  rptApi,
  salApi,
  type ApprovalInstanceView,
  type InventoryBalance,
  type ReportRow,
} from '@/api/client'
import {
  arApi,
  shipmentApi,
  type CustomerReceivable,
  type Shipment,
} from '@/api/salesV23'
import NlCommandBox from '@/components/NlCommandBox.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 工作台（驾驶舱）。
 *
 * ★ 分两层：
 *   1. 概览 —— 商品/库存/单据总数 + 库存一致性，回答"系统现在是什么状态"；
 *   2. 待办 —— 审批、发货、库存预警、逾期应收、近 7 日单据，回答"我现在该做什么"。
 *
 * ★ 取数纪律：
 *   - 不新增后端聚合端点，全部用现有列表端点，客户端只做**汇总与排序**；
 *   - 每个区块独立 loading / empty / error，任一失败不拖垮整页；
 *   - 按权限跳过请求，避免无权限账号首屏刷一串 403；
 *   - 列表端点有分页上限时明确标注"统计范围"，不把截断数据说成全量。
 */

type Settled<T> = { status: 'fulfilled'; value: T } | { status: 'rejected' }

async function settle<T>(promise: Promise<T>): Promise<Settled<T>> {
  try {
    return { status: 'fulfilled', value: await promise }
  } catch {
    return { status: 'rejected' }
  }
}

const session = useSessionStore()
const router = useRouter()

const canMdm = computed(() => session.hasPermission('mdm:read'))
const canInv = computed(() => session.hasPermission('inv:read'))
const canPur = computed(() => session.hasPermission('pur:read'))
const canSal = computed(() => session.hasPermission('sal:read'))
const canApsRead = computed(() => session.hasPermission('aps:read'))
const canApprove = computed(() => session.hasPermission('aps:approve'))
const canRpt = computed(() => session.hasPermission('rpt:read'))

// ─────────────────────────── 概览 ───────────────────────────

const loading = ref(false)
const loadError = ref('')
const consistencyError = ref(false)
const productTotal = ref<number | null>(null)
const inventoryRows = ref<number | null>(null)
const inventoryBalances = ref<InventoryBalance[] | null>(null)
const inventoryError = ref(false)
const purchaseTotal = ref<number | null>(null)
const salesTotal = ref<number | null>(null)
const consistent = ref<boolean | null>(null)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const displayName = computed(() => session.displayName || session.username || '用户')

async function loadKpis() {
  loading.value = true
  loadError.value = ''
  consistencyError.value = false

  // 无权限的指标不发请求；null 表示"没取到"，界面显示 - 并注明原因。
  const results = await Promise.all([
    canMdm.value ? settle(mdmApi.listProducts({ page: 1, size: 1 })) : null,
    canInv.value ? settle(invApi.query({})) : null,
    canPur.value ? settle(purApi.list({ page: 1, size: 1 })) : null,
    canSal.value ? settle(salApi.list({ page: 1, size: 1 })) : null,
    canInv.value ? settle(invApi.consistencyCheck()) : null,
  ])

  const products = results[0]
  const inventory = results[1]
  const purchases = results[2]
  const sales = results[3]
  const consistency = results[4]

  productTotal.value = products?.status === 'fulfilled' ? products.value.total : null
  inventoryBalances.value = inventory?.status === 'fulfilled' ? inventory.value : null
  inventoryRows.value = inventory?.status === 'fulfilled' ? inventory.value.length : null
  inventoryError.value = inventory?.status === 'rejected'
  purchaseTotal.value = purchases?.status === 'fulfilled' ? purchases.value.total : null
  salesTotal.value = sales?.status === 'fulfilled' ? sales.value.total : null
  consistent.value = consistency?.status === 'fulfilled' ? consistency.value.consistent : null
  consistencyError.value = consistency?.status === 'rejected'

  const failed = results.some((r) => r?.status === 'rejected')
  loadError.value = failed ? '部分统计加载失败，显示为 - 的数字不是真实值。请重试。' : ''
  loading.value = false
}

// ─────────────────────────── 待我审批 ───────────────────────────

interface ApprovalState {
  loading: boolean
  error: string
  mine: ApprovalInstanceView[]
  pendingTotal: number
  truncated: boolean
}

const approval = reactive<ApprovalState>({
  loading: false,
  error: '',
  mine: [],
  pendingTotal: 0,
  truncated: false,
})

const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '进货单',
  SALES_ORDER: '出货单',
  RETURN_ORDER: '退货单',
}

function pendingStep(row: ApprovalInstanceView) {
  return row.steps.find((s) => s.stepNo === row.currentStep && s.status === 'PENDING')
}

/** 待办按"当前节点的 approverPermission"匹配当前账号权限码，而不是按角色名猜。 */
function canHandle(row: ApprovalInstanceView): boolean {
  const step = pendingStep(row)
  return Boolean(step && session.hasPermission(step.approverPermission))
}

async function loadApprovals() {
  if (!canApsRead.value) return
  approval.loading = true
  approval.error = ''
  const [pending, transferred] = await Promise.all([
    settle(approvalApi.list('PENDING', undefined, 1, 200)),
    settle(approvalApi.list('TRANSFERRED', undefined, 1, 200)),
  ])

  let all: ApprovalInstanceView[] = []
  let total = 0
  let truncated = false
  let failed = false

  for (const result of [pending, transferred]) {
    if (result.status === 'fulfilled') {
      all = all.concat(result.value.items)
      total += result.value.total
      if (result.value.total > result.value.items.length) truncated = true
    } else {
      failed = true
    }
  }

  approval.pendingTotal = total
  approval.truncated = truncated
  approval.mine = canApprove.value
    ? all.filter(
        (row) => (row.status === 'PENDING' || row.status === 'TRANSFERRED') && canHandle(row),
      )
    : []
  if (failed) {
    approval.error = all.length
      ? '部分审批数据加载失败，下面只显示已取到的待办。'
      : '审批数据加载失败，请确认后端可用后重试。'
  }
  approval.loading = false
}

// ─────────────────────────── 待发货 / 拣货 ───────────────────────────

const ACTIONABLE_SHIPMENT_STATUSES = new Set(['DRAFT', 'PICKING', 'PICKED'])
const SHIPMENT_STATUS_LABELS: Record<string, string> = {
  DRAFT: '待拣货',
  PICKING: '拣货中',
  PICKED: '待确认发货',
  COMPLETED: '已发货',
  VOIDED: '已作废',
}

interface ShipmentState {
  loading: boolean
  error: string
  rows: Shipment[]
  counts: Record<string, number>
  scanned: number
  total: number
  truncated: boolean
}

const shipments = reactive<ShipmentState>({
  loading: false,
  error: '',
  rows: [],
  counts: { DRAFT: 0, PICKING: 0, PICKED: 0 },
  scanned: 0,
  total: 0,
  truncated: false,
})

const shipmentPendingCount = computed(
  () => shipments.counts.DRAFT + shipments.counts.PICKING + shipments.counts.PICKED,
)

async function loadShipments() {
  if (!canSal.value) return
  shipments.loading = true
  shipments.error = ''
  try {
    // 后端分页上限 200；用一页覆盖常见待办量，超出时在界面上标注统计范围。
    const data = await shipmentApi.list({ page: 1, size: 200 })
    const actionable = data.items.filter((s) => ACTIONABLE_SHIPMENT_STATUSES.has(s.status))
    const counts: Record<string, number> = { DRAFT: 0, PICKING: 0, PICKED: 0 }
    for (const row of actionable) {
      if (row.status in counts) counts[row.status] += 1
    }
    shipments.rows = actionable
    shipments.counts = counts
    shipments.scanned = data.items.length
    shipments.total = data.total
    shipments.truncated = data.total > data.items.length
  } catch {
    shipments.rows = []
    shipments.counts = { DRAFT: 0, PICKING: 0, PICKED: 0 }
    shipments.error = '发货单加载失败，请稍后重试。'
  } finally {
    shipments.loading = false
  }
}

// ─────────────────────────── 库存预警 / 零可用 ───────────────────────────

/**
 * 余额口径：可用量 ≤ 0 的 (SKU, 仓库, 批次)。
 *
 * 不调用 /inventory/alerts：当前 8080 后端的 SAFETY 预警与补货建议均返回 500，
 * 而预警阈值（skus.min_level）不在余额响应里。这里复用概览已取的余额数据，
 * 只做筛选与排序，少发一个请求，也不把接口失败隐藏成"没有预警"。
 */
const zeroAvailableRows = computed(() => {
  const rows = inventoryBalances.value
  if (!rows) return []
  return rows
    .filter((row) => row.availableQty <= 0)
    .sort((a, b) => a.availableQty - b.availableQty || a.skuId - b.skuId)
})

const zeroAvailableTotal = computed(() => zeroAvailableRows.value.length)

// ─────────────────────────── 应收逾期 ───────────────────────────

interface ArState {
  loading: boolean
  error: string
  items: CustomerReceivable[]
  total: number
}

const receivables = reactive<ArState>({ loading: false, error: '', items: [], total: 0 })

async function loadReceivables() {
  if (!canSal.value) return
  receivables.loading = true
  receivables.error = ''
  try {
    const data = await arApi.receivables({ overdue: true, page: 1, size: 5 })
    receivables.items = data.items
    receivables.total = data.total
  } catch {
    receivables.items = []
    receivables.total = 0
    receivables.error = '逾期应收加载失败，请稍后重试。'
  } finally {
    receivables.loading = false
  }
}

// ─────────────────────────── 今日 / 近 7 日单据 ───────────────────────────

interface DocStat {
  todayOrders: number
  weekOrders: number
  weekAmount: number
}

interface ReportState {
  loading: boolean
  error: string
  purchase: DocStat | null
  sales: DocStat | null
}

const reportState = reactive<ReportState>({
  loading: false,
  error: '',
  purchase: null,
  sales: null,
})

function isoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const today = isoDate(new Date())
const sevenDaysAgo = new Date()
sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 6)
const weekStart = isoDate(sevenDaysAgo)

function summarizeDays(rows: ReportRow[], day: string): DocStat {
  return {
    todayOrders: rows
      .filter((row) => row.label === day)
      .reduce((sum, row) => sum + row.orderCount, 0),
    weekOrders: rows.reduce((sum, row) => sum + row.orderCount, 0),
    weekAmount: rows.reduce((sum, row) => sum + row.amount, 0),
  }
}

async function loadReports() {
  if (!canRpt.value) return
  reportState.loading = true
  reportState.error = ''
  const params = { from: weekStart, to: today, groupBy: 'day' as const }
  const [purchase, sales] = await Promise.all([
    settle(rptApi.purchase(params)),
    settle(rptApi.sales(params)),
  ])
  reportState.purchase =
    purchase.status === 'fulfilled' ? summarizeDays(purchase.value.items, today) : null
  reportState.sales = sales.status === 'fulfilled' ? summarizeDays(sales.value.items, today) : null
  reportState.error =
    purchase.status === 'rejected' || sales.status === 'rejected'
      ? '近 7 日单据统计加载失败，请稍后重试。'
      : ''
  reportState.loading = false
}

// ─────────────────────────── 统一加载 ───────────────────────────

async function loadAll() {
  await Promise.allSettled([
    loadKpis(),
    loadApprovals(),
    loadShipments(),
    loadReceivables(),
    loadReports(),
  ])
}

const anyLoading = computed(
  () =>
    loading.value ||
    approval.loading ||
    shipments.loading ||
    receivables.loading ||
    reportState.loading,
)

onMounted(loadAll)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">{{ greeting }}，{{ displayName }}</h2>
        <p class="page-hint cockpit-page-hint">待办优先；没有数据的区块会明确说明原因。</p>
      </div>
      <el-button :loading="anyLoading" @click="loadAll">刷新</el-button>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="loadError"
    >
      <el-button size="small" @click="loadKpis">重试</el-button>
    </el-alert>

    <!-- 概览：真实反映后端状态，不造欢迎页假数据 -->
    <div v-loading="loading" class="mb-16">
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12" :md="6">
          <el-card shadow="hover">
            <div class="stat-label">商品</div>
            <div class="stat-value">{{ productTotal ?? '-' }}</div>
            <div class="stat-hint">
              {{ canMdm ? '个款式（不含 SKU 明细）' : '无 mdm:read 权限' }}
            </div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card shadow="hover">
            <div class="stat-label">库存行</div>
            <div class="stat-value">{{ inventoryRows ?? '-' }}</div>
            <div class="stat-hint">
              {{ canInv ? '条 (SKU × 仓库 × 批次)' : '无 inv:read 权限' }}
            </div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card shadow="hover">
            <div class="stat-label">进货单</div>
            <div class="stat-value">{{ purchaseTotal ?? '-' }}</div>
            <div class="stat-hint">{{ canPur ? '张（全状态）' : '无 pur:read 权限' }}</div>
          </el-card>
        </el-col>
        <el-col :xs="24" :sm="12" :md="6">
          <el-card shadow="hover">
            <div class="stat-label">出货单</div>
            <div class="stat-value">{{ salesTotal ?? '-' }}</div>
            <div class="stat-hint">{{ canSal ? '张（全状态）' : '无 sal:read 权限' }}</div>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <!-- 待办驾驶舱 -->
    <el-row :gutter="16">
      <el-col v-if="canApsRead" :xs="24" :lg="12" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title"><el-icon><Checked /></el-icon>待我审批</span>
              <span class="card-head-actions">
                <el-tag
                  v-if="!approval.loading && !approval.error"
                  size="small"
                  :type="approval.mine.length ? 'warning' : 'success'"
                >
                  {{ canApprove ? `${approval.mine.length} 条可处理` : `${approval.pendingTotal} 条待审` }}
                </el-tag>
                <el-button link type="primary" @click="router.push('/approvals')">去审批台</el-button>
              </span>
            </div>
          </template>

          <el-skeleton v-if="approval.loading" :rows="2" animated />
          <template v-else>
            <el-alert
              v-if="approval.error"
              type="warning"
              show-icon
              :closable="false"
              :title="approval.error"
              class="mb-16"
            >
              <el-button size="small" @click="loadApprovals">重试</el-button>
            </el-alert>
            <el-alert
              v-if="approval.truncated"
              type="info"
              show-icon
              :closable="false"
              title="待审批超过 200 条，本卡片只统计最近 200 条；请进审批台查看全量。"
              class="mb-16"
            />
            <div v-if="approval.mine.length" class="todo-list">
              <div
                v-for="row in approval.mine.slice(0, 5)"
                :key="row.id"
                class="todo-row"
                @click="router.push('/approvals')"
              >
                <div class="todo-main">
                  <div class="todo-title">
                    {{ row.bizRefNo }}
                    <el-tag size="small" type="info">
                      {{ BIZ_TYPE_LABELS[row.bizType] ?? row.bizType }}
                    </el-tag>
                  </div>
                  <div class="todo-sub">
                    {{ pendingStep(row)?.name ?? '当前节点' }} · 金额 ¥{{ fenToYuan(row.amount) }}
                  </div>
                </div>
                <el-tag size="small" type="warning">
                  {{ row.status === 'TRANSFERRED' ? '已转办' : '待审批' }}
                </el-tag>
              </div>
              <div v-if="approval.mine.length > 5" class="todo-more">
                仅显示前 5 条，共 {{ approval.mine.length }} 条可处理
              </div>
              <div class="cockpit-hint">
                按当前节点的 approverPermission 匹配；你是否已同意过该节点，以审批台详情为准。
              </div>
            </div>
            <div v-else-if="!approval.error" class="empty-block cockpit-empty">
              {{
                !canApprove
                  ? '当前账号无 aps:approve 权限，只能进入审批台查看列表'
                  : approval.pendingTotal
                    ? `共 ${approval.pendingTotal} 条待审批，但当前节点都不需要你的权限`
                    : '暂无待审批'
              }}
            </div>
          </template>
        </el-card>
      </el-col>

      <el-col v-if="canSal" :xs="24" :lg="12" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title"><el-icon><Van /></el-icon>待发货 / 拣货</span>
              <span class="card-head-actions">
                <el-tag
                  v-if="!shipments.loading && !shipments.error"
                  size="small"
                  :type="shipmentPendingCount ? 'warning' : 'success'"
                >
                  {{ shipmentPendingCount }} 条待处理
                </el-tag>
                <el-button link type="primary" @click="router.push('/shipments')">去发货</el-button>
              </span>
            </div>
          </template>

          <el-skeleton v-if="shipments.loading" :rows="2" animated />
          <template v-else>
            <el-alert
              v-if="shipments.error"
              type="warning"
              show-icon
              :closable="false"
              :title="shipments.error"
              class="mb-16"
            >
              <el-button size="small" @click="loadShipments">重试</el-button>
            </el-alert>
            <el-alert
              v-if="shipments.truncated"
              type="info"
              show-icon
              :closable="false"
              :title="`统计基于最近 ${shipments.scanned} 张发货单（共 ${shipments.total} 张）。`"
              class="mb-16"
            />
            <template v-if="shipments.rows.length">
              <div class="status-strip">
                <span>待拣货 <strong>{{ shipments.counts.DRAFT }}</strong></span>
                <span>拣货中 <strong>{{ shipments.counts.PICKING }}</strong></span>
                <span>待确认发货 <strong>{{ shipments.counts.PICKED }}</strong></span>
              </div>
              <div class="todo-list">
                <div
                  v-for="row in shipments.rows.slice(0, 5)"
                  :key="row.id"
                  class="todo-row"
                  @click="router.push('/shipments')"
                >
                  <div class="todo-main">
                    <div class="todo-title">{{ row.shipmentNo }}</div>
                    <div class="todo-sub">
                      出货单 #{{ row.salesOrderId }} · 仓库 #{{ row.warehouseId }}
                    </div>
                  </div>
                  <el-tag size="small" type="warning">
                    {{ SHIPMENT_STATUS_LABELS[row.status] ?? row.status }}
                  </el-tag>
                </div>
              </div>
              <div v-if="shipments.rows.length > 5" class="todo-more">
                仅显示前 5 条，共 {{ shipments.rows.length }} 条待处理
              </div>
            </template>
            <div v-else-if="!shipments.error" class="empty-block cockpit-empty">
              没有待拣货 / 待发货的发货单
            </div>
          </template>
        </el-card>
      </el-col>

      <el-col v-if="canInv" :xs="24" :lg="12" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title"><el-icon><Warning /></el-icon>库存预警 / 零可用</span>
              <span class="card-head-actions">
                <el-tag
                  v-if="!loading && !inventoryError"
                  size="small"
                  :type="zeroAvailableTotal ? 'danger' : 'success'"
                >
                  {{ zeroAvailableTotal }} 条零可用
                </el-tag>
                <el-button link type="primary" @click="router.push('/inventory')">去库存</el-button>
              </span>
            </div>
          </template>

          <el-skeleton v-if="loading" :rows="2" animated />
          <template v-else>
            <el-alert
              v-if="inventoryError"
              type="warning"
              show-icon
              :closable="false"
              title="库存余额加载失败，无法判断零可用库存。"
              class="mb-16"
            >
              <el-button size="small" @click="loadKpis">重试</el-button>
            </el-alert>
            <div v-if="zeroAvailableRows.length" class="todo-list">
              <div
                v-for="row in zeroAvailableRows.slice(0, 5)"
                :key="`${row.skuId}-${row.warehouseId}-${row.batchNo}`"
                class="todo-row"
                @click="router.push('/inventory')"
              >
                <div class="todo-main">
                  <div class="todo-title">SKU #{{ row.skuId }}</div>
                  <div class="todo-sub">
                    仓库 #{{ row.warehouseId }} · 批次 {{ row.batchNo || '—' }} · 实存
                    {{ row.onHandQty }} / 预占 {{ row.reservedQty }} / 冻结 {{ row.frozenQty }}
                  </div>
                </div>
                <el-tag size="small" type="danger">可用 {{ row.availableQty }}</el-tag>
              </div>
              <div v-if="zeroAvailableTotal > 5" class="todo-more">
                仅显示前 5 条，共 {{ zeroAvailableTotal }} 条
              </div>
            </div>
            <div v-else-if="!inventoryError" class="empty-block cockpit-empty">
              没有可用量 ≤ 0 的库存行
            </div>
            <div v-if="!loading && !inventoryError" class="cockpit-hint">
              余额口径：可用量 ≤ 0 的 (SKU, 仓库, 批次)；安全库存阈值预警接口未纳入本卡片。
            </div>
          </template>
        </el-card>
      </el-col>

      <el-col v-if="canSal" :xs="24" :lg="12" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title"><el-icon><Money /></el-icon>应收逾期</span>
              <span class="card-head-actions">
                <el-tag
                  v-if="!receivables.loading && !receivables.error"
                  size="small"
                  :type="receivables.total ? 'danger' : 'success'"
                >
                  {{ receivables.total }} 笔逾期
                </el-tag>
                <el-button link type="primary" @click="router.push('/receivables')">
                  去收款
                </el-button>
              </span>
            </div>
          </template>

          <el-skeleton v-if="receivables.loading" :rows="2" animated />
          <template v-else>
            <el-alert
              v-if="receivables.error"
              type="warning"
              show-icon
              :closable="false"
              :title="receivables.error"
              class="mb-16"
            >
              <el-button size="small" @click="loadReceivables">重试</el-button>
            </el-alert>
            <div v-if="receivables.items.length" class="todo-list">
              <div
                v-for="row in receivables.items"
                :key="row.id"
                class="todo-row"
                @click="router.push('/receivables')"
              >
                <div class="todo-main">
                  <div class="todo-title">客户 #{{ row.customerId }}</div>
                  <div class="todo-sub">
                    到期 {{ row.dueDate ?? '—' }} · 余额 ¥{{ fenToYuan(row.balance) }}
                  </div>
                </div>
                <el-tag size="small" type="danger">逾期</el-tag>
              </div>
              <div v-if="receivables.total > receivables.items.length" class="todo-more">
                仅显示最近 5 笔，共 {{ receivables.total }} 笔
              </div>
            </div>
            <div v-else-if="!receivables.error" class="empty-block cockpit-empty">
              没有逾期应收
            </div>
          </template>
        </el-card>
      </el-col>

      <el-col v-if="canRpt" :xs="24" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title"><el-icon><TrendCharts /></el-icon>近 7 日单据</span>
              <span class="card-head-actions">
                <span class="card-head-hint">{{ weekStart }} ~ {{ today }}</span>
                <el-button link type="primary" @click="router.push('/reports')">去报表</el-button>
              </span>
            </div>
          </template>

          <el-skeleton v-if="reportState.loading" :rows="2" animated />
          <template v-else>
            <el-alert
              v-if="reportState.error"
              type="warning"
              show-icon
              :closable="false"
              :title="reportState.error"
              class="mb-16"
            >
              <el-button size="small" @click="loadReports">重试</el-button>
            </el-alert>
            <div v-if="reportState.purchase && reportState.sales" class="doc-stat-row">
              <div class="doc-stat">
                <div class="doc-stat-label">进货（已入库 / 已部分入库）</div>
                <div class="doc-stat-value">{{ reportState.purchase.todayOrders }}</div>
                <div class="doc-stat-hint">
                  今日单据 · 近 7 日 {{ reportState.purchase.weekOrders }} 张 · ¥{{
                    fenToYuan(reportState.purchase.weekAmount)
                  }}
                </div>
              </div>
              <div class="doc-stat">
                <div class="doc-stat-label">出货（已出库 / 已部分出库）</div>
                <div class="doc-stat-value">{{ reportState.sales.todayOrders }}</div>
                <div class="doc-stat-hint">
                  今日单据 · 近 7 日 {{ reportState.sales.weekOrders }} 张 · ¥{{
                    fenToYuan(reportState.sales.weekAmount)
                  }}
                </div>
              </div>
            </div>
            <div v-else-if="!reportState.error" class="empty-block cockpit-empty">
              近 7 日没有已入库 / 已出库单据
            </div>
            <div class="cockpit-hint">
              口径与报表一致：只统计有实际入库量 / 出货量（PARTIAL、COMPLETED）的单据；今日数字
              0 表示今天还没有入库或出库。
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <!-- 自然语言操作（FR-SYS-04）：解析与调度都在后端，这里只做输入与展示 -->
    <NlCommandBox />

    <!-- 库存一致性 -->
    <el-card shadow="never" class="mb-16">
      <template #header>
        <span>库存一致性（不变量 INV-2：实存 == 流水净和）</span>
      </template>
      <el-alert
        v-if="!canInv"
        type="info"
        show-icon
        :closable="false"
        title="当前账号无 inv:read 权限，无法读取库存一致性"
      />
      <template v-else>
        <el-alert
          v-if="consistencyError"
          type="warning"
          show-icon
          :closable="false"
          title="库存一致性检查失败"
          description="无法读取一致性结果，不代表存在库存漂移。请稍后重试。"
        >
          <el-button size="small" @click="loadKpis">重试</el-button>
        </el-alert>
        <el-result
          v-else-if="consistent !== null"
          :icon="consistent ? 'success' : 'error'"
          :title="consistent ? '库存与流水一致' : '检测到库存漂移'"
          :sub-title="
            consistent
              ? '所有 (SKU, 仓库, 批次) 的实存量均等于流水净和'
              : '存在实存与流水不一致的记录，请立即排查是否有代码绕过流水修改库存'
          "
        />
        <div v-else class="empty-block">加载中…</div>
      </template>
    </el-card>

    <!-- 本次会话权限 -->
    <el-card shadow="never">
      <template #header>
        <span>当前账号权限</span>
      </template>
      <div v-if="session.roles.length" class="mb-16">
        <span class="text-muted mr-8">角色：</span>
        <el-tag v-for="r in session.roles" :key="r" class="mr-8" type="primary">{{ r }}</el-tag>
      </div>
      <div v-if="session.permissions.length">
        <span class="text-muted mr-8">权限码：</span>
        <el-tag
          v-for="p in session.permissions"
          :key="p"
          class="mr-8 mb-16"
          type="info"
          size="small"
        >
          {{ p }}
        </el-tag>
      </div>
      <div v-else class="empty-block">无可用权限，请联系管理员分配角色。</div>
    </el-card>
  </div>
</template>

<style scoped>
.cockpit-page-hint {
  margin: 6px 0 0;
}

.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  margin: 8px 0 4px;
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

.stat-hint {
  font-size: 12px;
  color: #c0c4cc;
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.card-head-actions {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.card-head-hint {
  font-size: 12px;
  color: #909399;
}

.cockpit-empty {
  padding: 20px 0;
}

.todo-list {
  display: flex;
  flex-direction: column;
}

.todo-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 4px;
  border-bottom: 1px solid #f0f2f5;
  cursor: pointer;
}

.todo-row:last-of-type {
  border-bottom: none;
}

.todo-row:hover {
  background: #f5f7fa;
}

.todo-main {
  min-width: 0;
}

.todo-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #303133;
}

.todo-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.todo-more {
  padding-top: 8px;
  font-size: 12px;
  color: #909399;
  text-align: right;
}

.status-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 2px 4px 10px;
  margin-bottom: 2px;
  font-size: 13px;
  color: #606266;
  border-bottom: 1px solid #f0f2f5;
}

.status-strip strong {
  color: #303133;
  font-variant-numeric: tabular-nums;
}

.doc-stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.doc-stat {
  flex: 1 1 240px;
  padding: 12px 16px;
  background: #f7f9fc;
  border-radius: 6px;
}

.doc-stat-label {
  font-size: 13px;
  color: #909399;
}

.doc-stat-value {
  margin: 6px 0;
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

.doc-stat-hint {
  font-size: 12px;
  color: #909399;
}

.cockpit-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
