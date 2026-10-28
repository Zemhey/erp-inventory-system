<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fenToYuan, nlQueueApi } from '@/api/client'
import { skeletonApi, type DashboardSummary, type RecentDocument, type TrendPoint } from '@/api/skeleton'
import DocumentDrawer from '@/components/DocumentDrawer.vue'
import NlCommandBox from '@/components/NlCommandBox.vue'
import StatusTag from '@/components/StatusTag.vue'
import { statusMeta } from '@/utils/documentStatus'
import SystemStatusPanel from '@/components/SystemStatusPanel.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 工作台（驾驶舱 · FR-V3-02）。
 *
 * ★ 版面顺序是"从系统到人"的阅读顺序，不是功能堆叠：
 *   运行状态 → 经营数字 → 趋势 → 待办 → 单据 → 自然语言操作台。
 *   自然语言操作沉到页面底部：它是"想到再去做"的能力，不是首屏要看的结论；
 *   首屏要让用户先确认"系统是活的、今天的盘子有多大"。顺序由版面表达，
 *   页头不再写一句"先看 A 再看 B"的旁白去复述它。
 *
 * ★ 数据仍走单一聚合端点 /dashboard/summary，前端只渲染，不在客户端拼。
 * ★ 图表零依赖：手绘 SVG（折线 / 饼图 / 横向柱状），不引第三方图表库。
 * ★ 交互原则（企业后台）：动效只服务于"这个能点""数据变了"，
 *   不做无意义的入场动画；hover / 点击 / 数字滚动都保持在 200–900ms 内。
 */

const router = useRouter()
const session = useSessionStore()

const loading = ref(false)
const loadError = ref('')
const summary = ref<DashboardSummary | null>(null)
/** 每次刷新 +1，用作图表 key，让"描线"动画在数据更新时重播一次 */
const epoch = ref(0)
const updatedAt = ref<Date | null>(null)

const drawerVisible = ref(false)
const drawerType = ref('')
const drawerId = ref<number | null>(null)

function openDocument(doc: RecentDocument) {
  drawerType.value = doc.type
  drawerId.value = doc.id
  drawerVisible.value = true
}

// ─────────────────────────── 数字滚动 ───────────────────────────

/**
 * KPI 数字从旧值滚到新值。
 *
 * 不是为了好看：8 个数字同时"跳"会让人分不清哪些变了；
 * 滚动让变化的幅度自己说话，也让刷新这件事有明确反馈。
 */
const counted = ref<Record<string, number>>({})
let rafId = 0

function prefersReducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

function runCountUp(targets: { key: string; value: number }[]): void {
  cancelAnimationFrame(rafId)
  if (prefersReducedMotion()) {
    counted.value = Object.fromEntries(targets.map((t) => [t.key, t.value]))
    return
  }
  const from = { ...counted.value }
  const started = performance.now()
  const duration = 720
  const tick = (now: number) => {
    const t = Math.min(1, (now - started) / duration)
    const eased = 1 - Math.pow(1 - t, 3)
    const next: Record<string, number> = {}
    for (const item of targets) {
      const start = from[item.key] ?? 0
      next[item.key] = Math.round(start + (item.value - start) * eased)
    }
    counted.value = next
    if (t < 1) rafId = requestAnimationFrame(tick)
  }
  rafId = requestAnimationFrame(tick)
}

onBeforeUnmount(() => cancelAnimationFrame(rafId))

// ─────────────────────────── SVG 折线图 ───────────────────────────

interface LineGeom {
  path: string
  /** 折线下方那块很淡的面：让"量级"在扫视时先于折线被看到 */
  area: string
  max: number
  dots: { x: number; y: number; label: string; value: number }[]
}

function lineGeometry(data: TrendPoint[], w = 320, h = 140, pad = 26): LineGeom {
  const max = Math.max(1, ...data.map((d) => d.value))
  const n = data.length
  const stepX = n > 1 ? (w - 2 * pad) / (n - 1) : 0
  const bottom = h - pad
  const dots = data.map((d, i) => ({
    x: pad + i * stepX,
    y: bottom - (d.value / max) * (h - 2 * pad),
    label: d.label,
    value: d.value,
  }))
  const path = dots.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
  const area = dots.length
    ? `${path} L${dots[dots.length - 1].x.toFixed(1)} ${bottom} L${dots[0].x.toFixed(1)} ${bottom} Z`
    : ''
  return { path, area, max, dots }
}

/** 日期只显示 "MM-DD"，年份对 7 日趋势没有信息量 */
function shortLabel(label: string): string {
  return label.length > 5 ? label.slice(-5) : label
}

/**
 * 把趋势补成完整的 7 个点。
 *
 * 后端只回有单据的日期，于是"近 7 日"可能只有 4 个点，折线提前结束、
 * 横轴和标题对不上。缺的那几天就是 0 —— 补齐比省略更诚实，也更好读。
 */
function normalizeTrend(data: TrendPoint[], days = 7): TrendPoint[] {
  const byLabel = new Map(data.map((d) => [d.label, d.value]))
  const out: TrendPoint[] = []
  for (let i = days - 1; i >= 0; i -= 1) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    const mmdd = `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const full = `${d.getFullYear()}-${mmdd}`
    out.push({ label: mmdd, value: byLabel.get(mmdd) ?? byLabel.get(full) ?? 0 })
  }
  return out
}

const purchaseGeom = computed(() => lineGeometry(normalizeTrend(summary.value?.charts.purchaseTrend ?? [])))
const salesGeom = computed(() => lineGeometry(normalizeTrend(summary.value?.charts.salesTrend ?? [])))

/** 折线 hover：鼠标停在哪一天，哪一天的点放大并显示数值气泡 */
const hoverLine = ref<{ chart: 'purchase' | 'sales'; index: number } | null>(null)

function enterLine(chart: 'purchase' | 'sales', index: number) {
  hoverLine.value = { chart, index }
}

function leaveLine() {
  hoverLine.value = null
}

// ─────────────────────────── SVG 饼图 ───────────────────────────

/**
 * 图表色板跟品牌走，不用 Element 默认的蓝绿橙红 ——
 * 那套是"控件色"，铺在暖白 + 黛青的界面上像是贴上去的。
 * 取 CSS 变量还有个额外好处：深色主题自动换成提亮后的同色系，不用两套代码。
 */
const PIE_COLORS = [
  'var(--ys-chart-1)',
  'var(--ys-chart-2)',
  'var(--ys-chart-3)',
  'var(--ys-chart-4)',
  'var(--ys-chart-5)',
  'var(--ys-chart-6)',
  'var(--ys-chart-7)',
]

interface PieSlice {
  path: string
  label: string
  value: number
  color: string
  percent: number
}

function pieSlices(data: { label: string; value: number }[]): PieSlice[] {
  const total = data.reduce((s, d) => s + d.value, 0) || 1
  const cx = 80
  const cy = 80
  const r = 70
  let acc = 0
  return data.map((d, i) => {
    const start = (acc / total) * 2 * Math.PI - Math.PI / 2
    acc += d.value
    const end = (acc / total) * 2 * Math.PI - Math.PI / 2
    const large = end - start > Math.PI ? 1 : 0
    const x1 = cx + r * Math.cos(start)
    const y1 = cy + r * Math.sin(start)
    const x2 = cx + r * Math.cos(end)
    const y2 = cy + r * Math.sin(end)
    const path = `M${cx} ${cy} L${x1.toFixed(1)} ${y1.toFixed(1)} A${r} ${r} 0 ${large} 1 ${x2.toFixed(1)} ${y2.toFixed(1)} Z`
    return {
      path,
      label: d.label,
      value: d.value,
      color: PIE_COLORS[i % PIE_COLORS.length],
      percent: Math.round((d.value / total) * 100),
    }
  })
}

const statusPie = computed(() => pieSlices(summary.value?.charts.docStatusPie ?? []))

/** 饼图与图例双向联动：悬停哪一块，图例对应行同步高亮 */
const hoverSlice = ref<number | null>(null)

// ─────────────────────────── SVG 横向柱状图 ───────────────────────────

const warehouseBars = computed(() => {
  const data = summary.value?.charts.inventoryByWarehouse ?? []
  const max = Math.max(1, ...data.map((d) => d.value))
  return data.map((d, i) => ({
    id: d.id,
    // 名字由后端 JOIN warehouses 给（V4-2）；只有后端回不来时才退化成 "仓 #id"
    label: d.name?.trim() ? d.name : `仓 #${d.id}`,
    value: d.value,
    width: Math.round((d.value / max) * 100),
    color: PIE_COLORS[i % PIE_COLORS.length],
  }))
})

// ─────────────────────────── 视图数据 ───────────────────────────

const kpis = computed(() => summary.value?.kpis ?? null)

interface KpiCard {
  key: string
  label: string
  value: number
  hint: string
  danger?: boolean
  to: string
  permission?: string
}

const kpiCards = computed<KpiCard[]>(() => {
  const k = kpis.value
  if (!k) return []
  return [
    { key: 'productTotal', label: '商品款式', value: k.productTotal, hint: '个款式', to: '/products', permission: 'mdm:read' },
    { key: 'skuTotal', label: 'SKU', value: k.skuTotal, hint: '个商品规格', to: '/products', permission: 'mdm:read' },
    { key: 'customerTotal', label: '客户', value: k.customerTotal, hint: '家', to: '/customers', permission: 'mdm:read' },
    { key: 'supplierTotal', label: '供应商', value: k.supplierTotal, hint: '家', to: '/suppliers', permission: 'mdm:read' },
    { key: 'purchaseOrderTotal', label: '进货单', value: k.purchaseOrderTotal, hint: '张（全状态）', to: '/purchase-orders', permission: 'pur:read' },
    { key: 'salesOrderTotal', label: '出货单', value: k.salesOrderTotal, hint: '张（全状态）', to: '/sales-orders', permission: 'sal:read' },
    { key: 'inventoryRowTotal', label: '库存行', value: k.inventoryRowTotal, hint: '条（SKU×仓×批）', to: '/inventory', permission: 'inv:read' },
    { key: 'lowStockTotal', label: '零可用库存', value: k.lowStockTotal, hint: '条 可用量≤0', danger: true, to: '/inventory-alerts', permission: 'inv:read' },
  ]
})

function canVisit(card: KpiCard): boolean {
  return !card.permission || session.hasPermission(card.permission)
}

function goKpi(card: KpiCard): void {
  if (!canVisit(card)) return
  void router.push(card.to)
}

const todos = computed(() => summary.value?.todos ?? null)

/**
 * 待确认的自然语言操作条数（FR-V4-03 · S5）。
 *
 * ★ 为什么要放到工作台的待办里：一条计划在等人确认时，后端什么都没发生。
 *   只把它挂在页面底部那张计划卡上，人一关页面就再也想不起来——
 *   **看不见的待确认等于没审。**
 *
 * ★ 为什么是 null 而不是 0：没有 sys:read 权限时请求会 403，
 *   这时既不该显示 0（那是撒谎：可能真有待确认的），也不该让整页报错。
 *   null = "我不知道"，于是这一格干脆不渲染。
 */
const nlPending = ref<number | null>(null)

async function loadNlPending(): Promise<void> {
  try {
    nlPending.value = (await nlQueueApi.pending({ page: 1, size: 1 })).pendingCount
  } catch {
    nlPending.value = null
  }
}
const recentDocuments = computed(() => summary.value?.recentDocuments ?? [])

const DOC_TYPE_LABELS: Record<string, string> = {
  purchase: '进货单',
  sales: '出货单',
  return: '退货单',
  transfer: '调拨单',
}

const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '进货单',
  SALES_ORDER: '出货单',
  RETURN_ORDER: '退货单',
}

function isoDates(): { from: string; to: string } {
  const to = new Date()
  const from = new Date()
  from.setDate(from.getDate() - 6)
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  return { from: fmt(from), to: fmt(to) }
}

const weekRange = isoDates()

const updatedLabel = computed(() => {
  if (!updatedAt.value) return ''
  const d = updatedAt.value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

async function loadSummary() {
  loading.value = true
  loadError.value = ''
  try {
    summary.value = await skeletonApi.dashboardSummary()
    epoch.value += 1
    updatedAt.value = new Date()
    runCountUp(kpiCards.value.map((c) => ({ key: c.key, value: c.value })))
    void loadNlPending()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    loadError.value = e?.response?.data?.message || e?.message || '工作台数据加载失败'
    summary.value = null
  } finally {
    loading.value = false
  }
}

onMounted(loadSummary)
</script>

<template>
  <div class="page">
    <!--
      页头：单行。标题在一端、时间与操作在另一端，左右两端和下方整宽区块的左右边缘对齐。
      原先这里挂了一句「先看系统是否健康……页面底部有一句话操作台」的旁白，
      删掉的理由：它描述的是版面顺序，而版面本身已经表达了这个顺序
      （第一屏就是运行状态、最后一块就是操作台）；而且 .page-header > div 是横向
      flex，标题与旁白会被排成左右两列、旁白还带 12px 下外边距，看着像没对齐。
    -->
    <header class="dash-head">
      <h2 class="page-title">工作台</h2>
      <div class="dash-head-actions">
        <span v-if="updatedLabel" class="updated">数据截至 {{ updatedLabel }}</span>
        <el-button :loading="loading" @click="loadSummary">刷新</el-button>
      </div>
    </header>

    <!-- ① 系统运行状态：首屏第一问题 -->
    <SystemStatusPanel />

    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`加载失败：${loadError}`"
    >
      <el-button size="small" @click="loadSummary">重试</el-button>
    </el-alert>

    <!-- ② KPI：点击直达对应业务页 -->
    <div v-if="loading && !summary" class="kpi-grid mb-16">
      <el-skeleton v-for="n in 8" :key="n" :rows="0" animated class="kpi-skeleton" />
    </div>
    <div v-else class="kpi-grid mb-16">
      <el-tooltip
        v-for="c in kpiCards"
        :key="c.key"
        :content="canVisit(c) ? `查看${c.label}` : '当前账号无该模块权限'"
        placement="top"
        :show-after="320"
      >
        <div
          class="kpi-card"
          :class="{ danger: c.danger, locked: !canVisit(c) }"
          role="button"
          tabindex="0"
          @click="goKpi(c)"
          @keydown.enter="goKpi(c)"
        >
          <div class="kpi-label">{{ c.label }}</div>
          <div class="kpi-value">{{ counted[c.key] ?? 0 }}</div>
          <div class="kpi-hint">
            <span>{{ c.hint }}</span>
            <span class="kpi-go" aria-hidden="true">→</span>
          </div>
        </div>
      </el-tooltip>
    </div>

    <!-- ③ 图表：悬停给细节，数据更新时重播描线 -->
    <div class="chart-grid mb-16">
      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">近 7 日进货趋势</span></template>
        <svg v-if="summary" :key="`purchase-${epoch}`" viewBox="0 0 320 140" class="chart-svg">
          <line x1="26" y1="114" x2="294" y2="114" class="axis" />
          <path :d="purchaseGeom.area" class="area area--purchase" />
          <path :d="purchaseGeom.path" class="line line--purchase" pathLength="1" />
          <text x="26" y="10" class="axis-label">峰值 {{ purchaseGeom.max }}</text>
          <text
            v-for="(p, i) in purchaseGeom.dots"
            :key="`px-${i}`"
            :x="p.x"
            y="130"
            class="axis-label"
            text-anchor="middle"
          >
            {{ shortLabel(p.label) }}
          </text>
          <g
            v-for="(p, i) in purchaseGeom.dots"
            :key="i"
            class="dot-group"
            @mouseenter="enterLine('purchase', i)"
            @mouseleave="leaveLine"
          >
            <circle :cx="p.x" :cy="p.y" r="10" class="dot-hit" />
            <circle
              :cx="p.x"
              :cy="p.y"
              :r="hoverLine?.chart === 'purchase' && hoverLine.index === i ? 4.6 : 3"
              class="dot dot--purchase"
            />
            <text
              v-if="hoverLine?.chart === 'purchase' && hoverLine.index === i"
              :x="p.x"
              :y="p.y - 10"
              class="dot-tip"
              text-anchor="middle"
            >
              {{ p.value }} 张
            </text>
          </g>
        </svg>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">近 7 日出货趋势</span></template>
        <svg v-if="summary" :key="`sales-${epoch}`" viewBox="0 0 320 140" class="chart-svg">
          <line x1="26" y1="114" x2="294" y2="114" class="axis" />
          <path :d="salesGeom.area" class="area area--sales" />
          <path :d="salesGeom.path" class="line line--sales" pathLength="1" />
          <text x="26" y="10" class="axis-label">峰值 {{ salesGeom.max }}</text>
          <text
            v-for="(p, i) in salesGeom.dots"
            :key="`sx-${i}`"
            :x="p.x"
            y="130"
            class="axis-label"
            text-anchor="middle"
          >
            {{ shortLabel(p.label) }}
          </text>
          <g
            v-for="(p, i) in salesGeom.dots"
            :key="i"
            class="dot-group"
            @mouseenter="enterLine('sales', i)"
            @mouseleave="leaveLine"
          >
            <circle :cx="p.x" :cy="p.y" r="10" class="dot-hit" />
            <circle
              :cx="p.x"
              :cy="p.y"
              :r="hoverLine?.chart === 'sales' && hoverLine.index === i ? 4.6 : 3"
              class="dot dot--sales"
            />
            <text
              v-if="hoverLine?.chart === 'sales' && hoverLine.index === i"
              :x="p.x"
              :y="p.y - 10"
              class="dot-tip"
              text-anchor="middle"
            >
              {{ p.value }} 张
            </text>
          </g>
        </svg>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">出货单状态分布</span></template>
        <div v-if="summary && statusPie.length" class="pie-wrap">
          <svg viewBox="0 0 160 160" class="pie-svg">
            <path
              v-for="(s, i) in statusPie"
              :key="`${epoch}-${i}`"
              :d="s.path"
              :fill="s.color"
              class="pie-slice"
              :class="{ dim: hoverSlice !== null && hoverSlice !== i }"
              @mouseenter="hoverSlice = i"
              @mouseleave="hoverSlice = null"
            >
              <title>{{ statusMeta(s.label).label }}：{{ s.value }}（{{ s.percent }}%）</title>
            </path>
          </svg>
          <ul class="pie-legend">
            <li
              v-for="(s, i) in statusPie"
              :key="i"
              :class="{ active: hoverSlice === i }"
              @mouseenter="hoverSlice = i"
              @mouseleave="hoverSlice = null"
            >
              <span class="legend-dot" :style="{ background: s.color }" />
              <span class="legend-label">{{ statusMeta(s.label).label }}</span>
              <span class="legend-val">{{ s.value }}（{{ s.percent }}%）</span>
            </li>
          </ul>
        </div>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">各仓库 SKU 覆盖</span></template>
        <div v-if="summary && warehouseBars.length" class="bar-wrap">
          <div v-for="b in warehouseBars" :key="b.id" class="bar-row">
            <span class="bar-label" :title="b.label">{{ b.label }}</span>
            <div class="bar-track">
              <div
                class="bar-fill"
                :style="{ width: b.width + '%', background: b.color }"
              />
            </div>
            <span class="bar-value">{{ b.value }}</span>
          </div>
        </div>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>
    </div>

    <!-- ④ 待办 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :xs="24" :lg="12" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header>
            <div class="card-head">
              <span class="card-head-title">待办概览</span>
              <el-button link type="primary" @click="router.push('/todos')">去待办中心</el-button>
            </div>
          </template>
          <div v-if="todos" class="todo-stat-row">
            <div class="todo-stat">
              <div class="todo-stat-value">{{ todos.approvalPending }}</div>
              <div class="todo-stat-label">待审批</div>
            </div>
            <div class="todo-stat">
              <div class="todo-stat-value">{{ todos.purchaseReceipt }}</div>
              <div class="todo-stat-label">待收货</div>
            </div>
            <div class="todo-stat">
              <div class="todo-stat-value">{{ todos.salesShip }}</div>
              <div class="todo-stat-label">待发货</div>
            </div>
            <div class="todo-stat">
              <div class="todo-stat-value danger">{{ todos.lowStock }}</div>
              <div class="todo-stat-label">库存预警</div>
            </div>
            <!--
              待确认的自然语言操作：数字本身是"还有几件事在等人拍板"。
              点进去是队列页 —— 这里刻意做成可点，因为它是"要你动手"的信号，
              不是一个只供观看的统计值。
            -->
            <div
              v-if="nlPending !== null"
              class="todo-stat todo-stat--link"
              @click="router.push('/nl/pending')"
            >
              <div class="todo-stat-value" :class="{ danger: nlPending > 0 }">{{ nlPending }}</div>
              <div class="todo-stat-label">待确认操作</div>
            </div>
          </div>
          <div v-else class="chart-empty">加载中…</div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12" class="mb-16">
        <el-card shadow="never" class="cockpit-card">
          <template #header><span class="card-head-title">最近的待审批</span></template>
          <div v-if="todos && todos.items.length" class="todo-list">
            <div
              v-for="row in todos.items.slice(0, 6)"
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
                <div class="todo-sub">金额 ¥{{ fenToYuan(row.amount) }}</div>
              </div>
              <el-tag size="small" type="warning">待审批</el-tag>
            </div>
          </div>
          <div v-else-if="todos" class="chart-empty">暂无待审批</div>
          <div v-else class="chart-empty">加载中…</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ⑤ 最近单据：整行可点，不用非得瞄准"查看详情" -->
    <el-card shadow="never" class="mb-16">
      <template #header>
        <div class="card-head">
          <span class="card-head-title">最近单据</span>
          <span class="card-sub">{{ weekRange.from }} ~ {{ weekRange.to }}</span>
        </div>
      </template>
      <el-table
        v-if="recentDocuments.length"
        :data="recentDocuments"
        border
        size="small"
        class="recent-table"
        @row-click="openDocument"
      >
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ DOC_TYPE_LABELS[row.type] ?? row.type }}</template>
        </el-table-column>
        <el-table-column label="单号" prop="no" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusTag :status="row.status" size="small" /></template>
        </el-table-column>
        <el-table-column label="业务日期" prop="bizDate" width="120" />
        <el-table-column label="数量" prop="totalQty" width="80" align="right" />
        <el-table-column label="金额(元)" width="120" align="right">
          <template #default="{ row }">
            <span class="amount">¥{{ fenToYuan(row.totalAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDocument(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="chart-empty">暂无最近单据</div>
    </el-card>

    <!--
      ⑥ 自然语言操作台：标题与说明都收进卡片头，不再在卡片上方另起一段旁白。
      卡片自己就是分隔，这里只留一点上方留白。
    -->
    <NlCommandBox class="nl-console-block" />

    <DocumentDrawer v-model:visible="drawerVisible" :type="drawerType" :id="drawerId" />
  </div>
</template>

<style scoped>
/* 页头：与下方区块同一条左右基线，只占一行 */
.dash-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ys-line-soft);
}

.dash-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.updated {
  font-family: var(--ys-font-num);
  font-size: 12px;
  color: var(--ys-mo-4);
  font-variant-numeric: tabular-nums;
}

/* ── KPI ── */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.kpi-skeleton {
  padding: 14px 16px;
  border: 1px solid var(--ys-line);
  border-radius: var(--ys-r-card);
  background: var(--ys-cibai);
}

.kpi-card {
  position: relative;
  padding: 13px 16px 11px;
  border: 1px solid var(--ys-line);
  border-radius: var(--ys-r-card);
  background: var(--ys-cibai);
  box-shadow: var(--ys-shadow-card);
  cursor: pointer;
  transition:
    transform 0.2s cubic-bezier(0.22, 0.58, 0.32, 1),
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.kpi-card:hover {
  transform: translateY(-2px);
  border-color: var(--ys-line-strong);
  box-shadow: 0 2px 4px rgba(58, 44, 28, 0.06), 0 14px 28px -16px rgba(58, 44, 28, 0.28);
}

.kpi-card:active {
  transform: translateY(0);
}

.kpi-card:focus-visible {
  outline: 2px solid var(--ys-daiqing);
  outline-offset: 2px;
}

.kpi-card.locked {
  cursor: not-allowed;
  opacity: 0.62;
}

.kpi-label {
  font-size: 12.5px;
  color: var(--ys-mo-3);
}

.kpi-value {
  margin: 6px 0 2px;
  font-family: var(--ys-font-num);
  font-size: 26px;
  font-weight: 600;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
  color: var(--ys-mo);
}

.kpi-card.danger .kpi-value {
  color: var(--ys-yanzhi);
}

.kpi-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11.5px;
  color: var(--ys-mo-5);
}

.kpi-go {
  opacity: 0;
  transform: translateX(-4px);
  color: var(--ys-daiqing);
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.kpi-card:hover .kpi-go {
  opacity: 1;
  transform: translateX(0);
}

/* ── 图表 ── */
.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.chart-card {
  border-radius: var(--ys-r-card);
}

.chart-title {
  font-weight: 600;
  font-size: 13.5px;
}

.chart-svg {
  width: 100%;
  height: auto;
  overflow: visible;
}

.axis {
  stroke: var(--ys-grid);
}

.area {
  stroke: none;
}

.area--purchase {
  fill: var(--ys-daiqing-wash);
}

.area--sales {
  fill: var(--ys-tai-wash);
}

.axis-label {
  font-family: var(--ys-font-num);
  font-size: 9px;
  fill: var(--ys-mo-4);
}

.line {
  fill: none;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  animation: draw-line 900ms cubic-bezier(0.33, 1, 0.68, 1) both;
}

/* pathLength=1 + dasharray=1：不依赖真实路径长度也能画准 */
@keyframes draw-line {
  from {
    stroke-dasharray: 1;
    stroke-dashoffset: 1;
  }
  to {
    stroke-dasharray: 1;
    stroke-dashoffset: 0;
  }
}

.line--purchase {
  stroke: var(--ys-daiqing);
}

.line--sales {
  stroke: var(--ys-tai);
}

.dot {
  transition: r 0.16s ease;
}

.dot--purchase {
  fill: var(--ys-daiqing);
}

.dot--sales {
  fill: var(--ys-tai);
}

.dot-hit {
  fill: transparent;
  cursor: pointer;
}

.dot-tip {
  font-family: var(--ys-font-num);
  font-size: 10px;
  fill: var(--ys-mo-2);
}

.chart-empty {
  padding: 28px 0;
  text-align: center;
  color: var(--ys-mo-4);
  font-size: 13px;
}

.pie-wrap {
  display: flex;
  align-items: center;
  gap: 16px;
}

.pie-svg {
  width: 140px;
  height: 140px;
  flex: 0 0 140px;
}

.pie-slice {
  cursor: pointer;
  transition: opacity 0.18s ease, transform 0.18s ease;
  transform-origin: 80px 80px;
}

.pie-slice:hover {
  transform: scale(1.035);
}

.pie-slice.dim {
  opacity: 0.35;
}

.pie-legend {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12px;
  flex: 1 1 auto;
  min-width: 0;
}

.pie-legend li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  border-radius: 6px;
  transition: background-color 0.16s ease;
}

.pie-legend li.active {
  background: var(--ys-warm-50);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex: 0 0 10px;
}

.legend-label {
  flex: 1 1 auto;
  color: var(--ys-mo-2);
}

.legend-val {
  color: var(--ys-mo-4);
  font-variant-numeric: tabular-nums;
}

.bar-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.bar-label {
  flex: 0 0 92px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ys-mo-2);
}

.bar-track {
  flex: 1 1 auto;
  height: 14px;
  background: var(--ys-track);
  border-radius: 7px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 7px;
  transition: width 0.7s cubic-bezier(0.33, 1, 0.68, 1);
}

.bar-value {
  flex: 0 0 48px;
  text-align: right;
  color: var(--ys-mo);
  font-variant-numeric: tabular-nums;
}

/* ── 待办 ── */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head-title {
  font-weight: 600;
  font-size: 13.5px;
}

.card-sub {
  font-family: var(--ys-font-num);
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.todo-stat-row {
  display: flex;
  gap: 10px;
}

.todo-stat {
  flex: 1 1 0;
  padding: 10px 8px;
  text-align: center;
  background: var(--ys-track-wash);
  border: 1px solid transparent;
  border-radius: 10px;
  transition: border-color 0.18s ease, background-color 0.18s ease;
}

.todo-stat:hover {
  border-color: var(--ys-line);
  background: var(--ys-warm-100);
}

/* 可点的统计格：手型 + 轻微按压反馈，让它看起来像"入口"而不是"读数" */
.todo-stat--link {
  cursor: pointer;
}

.todo-stat--link:active {
  transform: translateY(1px);
}

.todo-stat-value {
  font-family: var(--ys-font-num);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--ys-mo);
}

.todo-stat-value.danger {
  color: var(--ys-yanzhi);
}

.todo-stat-label {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ys-mo-4);
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
  padding: 9px 6px;
  border-bottom: 1px solid var(--ys-line-soft);
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.16s ease;
}

.todo-row:last-of-type {
  border-bottom: none;
}

.todo-row:hover {
  background: var(--ys-warm-50);
}

.todo-main {
  min-width: 0;
}

.todo-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  color: var(--ys-mo);
}

.todo-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ys-mo-4);
  font-variant-numeric: tabular-nums;
}

/* ── 最近单据 ── */
.recent-table :deep(.el-table__row) {
  cursor: pointer;
}

/* ── 自然语言操作台 ── */
.nl-console-block {
  margin-top: 24px;
}

@media (max-width: 1100px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .kpi-value {
    font-size: 22px;
  }

  .todo-stat-row {
    flex-wrap: wrap;
  }

  .todo-stat {
    flex: 1 1 40%;
  }
}
</style>
