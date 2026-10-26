<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fenToYuan } from '@/api/client'
import { skeletonApi, type DashboardSummary, type RecentDocument, type TrendPoint } from '@/api/skeleton'
import DocumentDrawer from '@/components/DocumentDrawer.vue'
import NlCommandBox from '@/components/NlCommandBox.vue'

/**
 * 工作台（驾驶舱 · FR-V3-02）。
 *
 * ★ 改走单一聚合端点 /dashboard/summary：后端一次算好 8 个 KPI、4 个图表、待办、最近单据，
 *   前端只负责渲染，不再散着调七八个列表端点、客户端拼。
 * ★ 图表零依赖：项目未装 echarts，且不允许改 package.json，因此用手绘 SVG
 *   （折线 / 饼图 / 横向柱状）实现，组件不引入任何第三方图表库。
 * ★ 最近单据 / 待办里的单据，点一下用 DocumentDrawer 调 /documents/{type}/{id} 看详情。
 */

const router = useRouter()

const loading = ref(false)
const loadError = ref('')
const summary = ref<DashboardSummary | null>(null)

const drawerVisible = ref(false)
const drawerType = ref('')
const drawerId = ref<number | null>(null)

function openDocument(doc: RecentDocument) {
  drawerType.value = doc.type
  drawerId.value = doc.id
  drawerVisible.value = true
}

// ─────────────────────────── SVG 折线图 ───────────────────────────

interface LineGeom {
  path: string
  dots: { x: number; y: number; label: string; value: number }[]
}

function lineGeometry(data: TrendPoint[], w = 320, h = 140, pad = 26): LineGeom {
  const max = Math.max(1, ...data.map((d) => d.value))
  const n = data.length
  const stepX = n > 1 ? (w - 2 * pad) / (n - 1) : 0
  const dots = data.map((d, i) => ({
    x: pad + i * stepX,
    y: h - pad - (d.value / max) * (h - 2 * pad),
    label: d.label,
    value: d.value,
  }))
  const path = dots.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
  return { path, dots }
}

const purchaseGeom = computed(() => lineGeometry(summary.value?.charts.purchaseTrend ?? []))
const salesGeom = computed(() => lineGeometry(summary.value?.charts.salesTrend ?? []))

// ─────────────────────────── SVG 饼图 ───────────────────────────

const PIE_COLORS = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9254de', '#13c2c2']

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

// ─────────────────────────── SVG 横向柱状图 ───────────────────────────

const warehouseBars = computed(() => {
  const data = summary.value?.charts.inventoryByWarehouse ?? []
  const max = Math.max(1, ...data.map((d) => d.value))
  return data.map((d, i) => ({
    id: d.id,
    value: d.value,
    width: Math.round((d.value / max) * 100),
    color: PIE_COLORS[i % PIE_COLORS.length],
  }))
})

// ─────────────────────────── 视图数据 ───────────────────────────

const kpis = computed(() => summary.value?.kpis ?? null)

const kpiCards = computed(() => {
  const k = kpis.value
  if (!k) return []
  return [
    { label: '商品款式', value: k.productTotal, hint: '个款式' },
    { label: 'SKU', value: k.skuTotal, hint: '个商品规格' },
    { label: '客户', value: k.customerTotal, hint: '家' },
    { label: '供应商', value: k.supplierTotal, hint: '家' },
    { label: '进货单', value: k.purchaseOrderTotal, hint: '张（全状态）' },
    { label: '出货单', value: k.salesOrderTotal, hint: '张（全状态）' },
    { label: '库存行', value: k.inventoryRowTotal, hint: '条（SKU×仓×批）' },
    { label: '零可用库存', value: k.lowStockTotal, hint: '条 可用量≤0', danger: true },
  ]
})

const todos = computed(() => summary.value?.todos ?? null)
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

async function loadSummary() {
  loading.value = true
  loadError.value = ''
  try {
    summary.value = await skeletonApi.dashboardSummary()
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
    <div class="page-header">
      <div>
        <h2 class="page-title">工作台</h2>
        <p class="page-hint">系统当前运行状态与你的待办，数据来自单一聚合端点。</p>
      </div>
      <el-button :loading="loading" @click="loadSummary">刷新</el-button>
    </div>

    <!-- FR-SYS-04：自然语言操作入口（POST /nl/command）。
         放在工作台首屏，是因为它的能力就是"从这里一句话查到/建出东西"，
         藏进二级页面等于没有。意图与权限一律后端判定，组件只负责输入与展示。 -->
    <NlCommandBox />

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

    <!-- KPI 卡片：8 项 -->
    <div v-loading="loading" class="kpi-grid mb-16">
      <el-card
        v-for="c in kpiCards"
        :key="c.label"
        shadow="hover"
        :class="['kpi-card', { danger: c.danger }]"
      >
        <div class="kpi-label">{{ c.label }}</div>
        <div class="kpi-value">{{ c.value }}</div>
        <div class="kpi-hint">{{ c.hint }}</div>
      </el-card>
    </div>

    <!-- 图表：4 张手绘 SVG -->
    <div class="chart-grid mb-16">
      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">近 7 日进货趋势</span></template>
        <svg v-if="summary" viewBox="0 0 320 140" class="chart-svg">
          <line x1="26" y1="114" x2="294" y2="114" stroke="#e4e7ed" />
          <path :d="purchaseGeom.path" fill="none" stroke="#409eff" stroke-width="2" />
          <circle
            v-for="(p, i) in purchaseGeom.dots"
            :key="i"
            :cx="p.x"
            :cy="p.y"
            r="3"
            fill="#409eff"
          >
            <title>{{ p.label }}：{{ p.value }} 张</title>
          </circle>
        </svg>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">近 7 日出货趋势</span></template>
        <svg v-if="summary" viewBox="0 0 320 140" class="chart-svg">
          <line x1="26" y1="114" x2="294" y2="114" stroke="#e4e7ed" />
          <path :d="salesGeom.path" fill="none" stroke="#67c23a" stroke-width="2" />
          <circle
            v-for="(p, i) in salesGeom.dots"
            :key="i"
            :cx="p.x"
            :cy="p.y"
            r="3"
            fill="#67c23a"
          >
            <title>{{ p.label }}：{{ p.value }} 张</title>
          </circle>
        </svg>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>

      <el-card shadow="never" class="chart-card">
        <template #header><span class="chart-title">出货单状态分布</span></template>
        <div v-if="summary && statusPie.length" class="pie-wrap">
          <svg viewBox="0 0 160 160" class="pie-svg">
            <path v-for="(s, i) in statusPie" :key="i" :d="s.path" :fill="s.color" />
          </svg>
          <ul class="pie-legend">
            <li v-for="(s, i) in statusPie" :key="i">
              <span class="legend-dot" :style="{ background: s.color }" />
              <span class="legend-label">{{ s.label }}</span>
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
            <span class="bar-label">仓 #{{ b.id }}</span>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: b.width + '%', background: b.color }" />
            </div>
            <span class="bar-value">{{ b.value }}</span>
          </div>
        </div>
        <div v-else class="chart-empty">暂无数据</div>
      </el-card>
    </div>

    <!-- 待办概览 -->
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

    <!-- 最近单据 -->
    <el-card shadow="never" class="mb-16">
      <template #header><span>最近单据（{{ weekRange.from }} ~ {{ weekRange.to }} 附近）</span></template>
      <el-table v-if="recentDocuments.length" :data="recentDocuments" border size="small">
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ DOC_TYPE_LABELS[row.type] ?? row.type }}</template>
        </el-table-column>
        <el-table-column label="单号" prop="no" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag size="small">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="业务日期" prop="bizDate" width="120" />
        <el-table-column label="数量" prop="totalQty" width="80" />
        <el-table-column label="金额(元)" width="110">
          <template #default="{ row }">¥{{ fenToYuan(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDocument(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="chart-empty">暂无最近单据</div>
    </el-card>

    <DocumentDrawer
      v-model:visible="drawerVisible"
      :type="drawerType"
      :id="drawerId"
    />
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kpi-card {
  border-radius: 4px;
}

.kpi-card.danger .kpi-value {
  color: #f56c6c;
}

.kpi-label {
  font-size: 13px;
  color: #909399;
}

.kpi-value {
  margin: 8px 0 4px;
  font-size: 26px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

.kpi-hint {
  font-size: 12px;
  color: #c0c4cc;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.chart-card {
  border-radius: 4px;
}

.chart-title {
  font-weight: 600;
}

.chart-svg {
  width: 100%;
  height: auto;
}

.chart-empty {
  padding: 28px 0;
  text-align: center;
  color: #909399;
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
  padding: 2px 0;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex: 0 0 10px;
}

.legend-label {
  flex: 1 1 auto;
  color: #606266;
}

.legend-val {
  color: #909399;
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
  flex: 0 0 56px;
  color: #606266;
}

.bar-track {
  flex: 1 1 auto;
  height: 14px;
  background: #f0f2f5;
  border-radius: 7px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
}

.bar-value {
  flex: 0 0 48px;
  text-align: right;
  color: #303133;
  font-variant-numeric: tabular-nums;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head-title {
  font-weight: 600;
}

.todo-stat-row {
  display: flex;
  gap: 12px;
}

.todo-stat {
  flex: 1 1 0;
  padding: 12px;
  text-align: center;
  background: #f7f9fc;
  border-radius: 6px;
}

.todo-stat-value {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

.todo-stat-value.danger {
  color: #f56c6c;
}

.todo-stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
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

@media (max-width: 768px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
