<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fenToYuan,
  mdmApi,
  rptApi,
  type InventorySummaryRow,
  type Product,
  type ReportGroupBy,
  type ReportRow,
  type Sku,
  type Warehouse,
} from '@/api/client'
import {
  downloadReportCsv,
  reportApi,
  type AgingRow,
  type AgingSummary,
  type CustomerContributionRow,
  type CustomerContributionSummary,
  type InventoryTurnoverRow,
  type OperatingReportRow,
  type ReportPeriodDim,
} from '@/api/report'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 报表（FR-RPT-01~09）。
 *
 * ★ 这一页真正要防的是**口径混用**（§6 坑 4），不是渲染：
 *   进货 / 出货统计、经营报表里的进货/销售按**单据业务日期 `biz_date`**；
 *   进销存汇总、经营报表的期初/入/出/期末、周转与呆滞按**库存流水 `occurred_at`**。
 *   两组数分别列示，合计绝不跨口径相加 —— 跨天执行的业务落在不同日期不是 bug。
 *
 * ★ 导出与页面同一查询：CSV 由后端用同一个 ApplicationService 生成，
 *   金额/周转天数/复购率的舍入规则与页面一致；按钮只负责下载。
 *
 * ★ 打印：用浏览器原生打印，CSS 隐藏筛选区与按钮，仅保留标题、汇总与表格。
 */

type Tab = 'purchase' | 'sales' | 'inventory' | 'operating' | 'turnover'
  | 'contribution' | 'ar' | 'ap'
type DocTab = 'purchase' | 'sales'

const activeTab = ref<Tab>('purchase')
const TAB_LABELS: Record<Tab, string> = {
  purchase: '进货统计',
  sales: '出货统计',
  inventory: '进销存汇总',
  operating: '经营日报/月报',
  turnover: '库存周转与呆滞',
  contribution: '客户贡献与复购',
  ar: '应收账龄',
  ap: '应付账龄',
}

// ─────────────────────────── 主数据字典（只为显示名称） ───────────────────────────

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])
/** 后端 /skus、/products 每页最多 200 条，超出部分字典里没有 → 界面要说明。 */
const dictTruncated = ref(false)

const session = useSessionStore()
const canReadMaster = computed(() => session.hasPermission('mdm:read'))

async function loadDictionary() {
  if (!canReadMaster.value) {
    warehouses.value = []
    skus.value = []
    products.value = []
    return
  }
  const [wh, sku, prod] = await Promise.allSettled([
    mdmApi.listWarehouses(false),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
  dictTruncated.value =
    (sku.status === 'fulfilled' && sku.value.total > sku.value.items.length) ||
    (prod.status === 'fulfilled' && prod.value.total > prod.value.items.length)
}

const skuNames = computed(() => {
  const productNames = new Map<number, string>(products.value.map((p) => [p.id, p.name]))
  const map = new Map<number, string>()
  for (const s of skus.value) {
    const product = productNames.get(s.productId) ?? `商品#${s.productId}`
    map.set(s.id, `${product} · ${s.color}-${s.size}`)
  }
  return map
})

function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

const warehouseNames = computed(
  () => new Map(warehouses.value.map((w) => [w.id, w.enabled ? w.name : `${w.name}（已停用）`])),
)

function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}

/** 拦截器已弹过一次消息，这里再把原文留在页面上（刷新后仍可回看）。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

// ─────────────────────────── 日期与账期默认值 ───────────────────────────

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 用本地日期而不是 toISOString：后者按 UTC 取日，东八区早上 8 点前会差一天。 */
function dateText(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function defaultRange(): [string, string] {
  const now = new Date()
  return [dateText(new Date(now.getFullYear(), now.getMonth(), 1)), dateText(now)]
}

function currentPeriod(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}`
}

function defaultAsOf(): string {
  return dateText(new Date())
}

// ─────────────────────────── 进货 / 出货统计 ───────────────────────────

/** 契约里两张表允许的 groupBy 不同：进货没有 customer，出货没有 supplier。 */
const DOC_DIMS: Record<DocTab, { value: ReportGroupBy; label: string }[]> = {
  purchase: [
    { value: 'supplier', label: '供应商' },
    { value: 'product', label: '商品（款式）' },
    { value: 'sku', label: 'SKU' },
    { value: 'day', label: '业务日期' },
    { value: 'month', label: '账期（月）' },
  ],
  sales: [
    { value: 'customer', label: '客户' },
    { value: 'product', label: '商品（款式）' },
    { value: 'sku', label: 'SKU' },
    { value: 'day', label: '业务日期' },
    { value: 'month', label: '账期（月）' },
  ],
}

interface DocState {
  range: [string, string] | null
  groupBy: ReportGroupBy | undefined
  warehouseId: number | undefined
  rows: ReportRow[]
  loading: boolean
  error: string
  loaded: boolean
}

const docStates = reactive<Record<DocTab, DocState>>({
  purchase: { range: defaultRange(), groupBy: undefined, warehouseId: undefined, rows: [], loading: false, error: '', loaded: false },
  sales: { range: defaultRange(), groupBy: undefined, warehouseId: undefined, rows: [], loading: false, error: '', loaded: false },
})

/** 未选分组维度时后端取默认（进货 supplier / 出货 customer），这里跟着显示同一个名字。 */
function effectiveDim(tab: DocTab): string {
  const state = docStates[tab]
  const value = state.groupBy ?? (tab === 'purchase' ? 'supplier' : 'customer')
  return DOC_DIMS[tab].find((d) => d.value === value)?.label ?? value
}

async function loadDoc(tab: DocTab) {
  const state = docStates[tab]
  if (!state.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  state.loading = true
  state.error = ''
  try {
    const params = {
      from: state.range[0],
      to: state.range[1],
      groupBy: state.groupBy,
      warehouseId: state.warehouseId,
    }
    const data = tab === 'purchase' ? await rptApi.purchase(params) : await rptApi.sales(params)
    state.rows = data.items
  } catch (error) {
    state.rows = []
    state.error = errorMessage(error)
  } finally {
    state.loading = false
    state.loaded = true
  }
}

function resetDoc(tab: DocTab) {
  const state = docStates[tab]
  state.range = defaultRange()
  state.groupBy = undefined
  state.warehouseId = undefined
  loadDoc(tab)
}

/** 合计只把本页返回的行相加（数量与金额可加；`orderCount` 跨组会重复计数，故不求和）。 */
function docQty(rows: ReportRow[]): number {
  return rows.reduce((sum, r) => sum + r.qty, 0)
}

function docAmount(rows: ReportRow[]): number {
  return rows.reduce((sum, r) => sum + r.amount, 0)
}

// ─────────────────────────── 进销存汇总 ───────────────────────────

const invState = reactive({
  period: currentPeriod(),
  warehouseId: undefined as number | undefined,
  rows: [] as InventorySummaryRow[],
  loading: false,
  error: '',
  loaded: false,
})

async function loadInventorySummary() {
  if (!/^\d{4}-\d{2}$/.test(invState.period)) {
    ElMessage.warning('账期格式要求 yyyy-MM，例如 2026-10')
    return
  }
  invState.loading = true
  invState.error = ''
  try {
    const data = await rptApi.inventorySummary({
      period: invState.period,
      warehouseId: invState.warehouseId,
    })
    invState.rows = data.items
  } catch (error) {
    invState.rows = []
    invState.error = errorMessage(error)
  } finally {
    invState.loading = false
    invState.loaded = true
  }
}

function resetInventorySummary() {
  invState.period = currentPeriod()
  invState.warehouseId = undefined
  loadInventorySummary()
}

type SummaryQtyKey = 'openingQty' | 'inboundQty' | 'outboundQty' | 'closingQty'

function summaryQty(rows: InventorySummaryRow[], key: SummaryQtyKey): number {
  return rows.reduce((sum, r) => sum + r[key], 0)
}

// ─────────────────────────── 经营日报 / 月报 ───────────────────────────

type OperatingQtyKey = 'openingQty' | 'inboundQty' | 'outboundQty' | 'closingQty'
  | 'purchaseQty' | 'purchaseAmount' | 'salesQty' | 'salesAmount'

const operatingState = reactive({
  range: defaultRange() as [string, string] | null,
  dimension: 'day' as ReportPeriodDim,
  warehouseId: undefined as number | undefined,
  rows: [] as OperatingReportRow[],
  loading: false,
  error: '',
  loaded: false,
})

async function loadOperating() {
  if (!operatingState.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  operatingState.loading = true
  operatingState.error = ''
  try {
    const data = await reportApi.operating({
      from: operatingState.range[0],
      to: operatingState.range[1],
      dimension: operatingState.dimension,
      warehouseId: operatingState.warehouseId,
    })
    operatingState.rows = data.items
  } catch (error) {
    operatingState.rows = []
    operatingState.error = errorMessage(error)
  } finally {
    operatingState.loading = false
    operatingState.loaded = true
  }
}

function resetOperating() {
  operatingState.range = defaultRange()
  operatingState.dimension = 'day'
  operatingState.warehouseId = undefined
  loadOperating()
}

function sumOperating(rows: OperatingReportRow[], key: OperatingQtyKey): number {
  return rows.reduce((sum, r) => sum + r[key], 0)
}

function operatingOpening(rows: OperatingReportRow[]): number {
  return rows[0]?.openingQty ?? 0
}

function operatingClosing(rows: OperatingReportRow[]): number {
  return rows[rows.length - 1]?.closingQty ?? 0
}

function operatingIdentityOk(row: OperatingReportRow): boolean {
  return row.openingQty + row.inboundQty - row.outboundQty === row.closingQty
}

function operatingAllIdentitiesOk(rows: OperatingReportRow[]): boolean {
  return rows.every(operatingIdentityOk)
}

// ─────────────────────────── 库存周转与呆滞 ───────────────────────────

const turnoverState = reactive({
  range: defaultRange() as [string, string] | null,
  deadDays: 30,
  warehouseId: undefined as number | undefined,
  onlyStale: false,
  rows: [] as InventoryTurnoverRow[],
  loading: false,
  error: '',
  loaded: false,
})

const visibleTurnoverRows = computed(() =>
  turnoverState.onlyStale ? turnoverState.rows.filter((r) => r.stale) : turnoverState.rows,
)

async function loadTurnover() {
  if (!turnoverState.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  turnoverState.loading = true
  turnoverState.error = ''
  try {
    const data = await reportApi.turnover({
      from: turnoverState.range[0],
      to: turnoverState.range[1],
      deadDays: turnoverState.deadDays,
      warehouseId: turnoverState.warehouseId,
    })
    turnoverState.rows = data.items
  } catch (error) {
    turnoverState.rows = []
    turnoverState.error = errorMessage(error)
  } finally {
    turnoverState.loading = false
    turnoverState.loaded = true
  }
}

function resetTurnover() {
  turnoverState.range = defaultRange()
  turnoverState.deadDays = 30
  turnoverState.warehouseId = undefined
  turnoverState.onlyStale = false
  loadTurnover()
}

function turnoverText(days: number | null): string {
  return days == null ? '-' : Number(days).toFixed(1)
}

function turnoverTotal(rows: InventoryTurnoverRow[], key: 'openingQty' | 'closingQty' | 'outboundQty'): number {
  return rows.reduce((sum, r) => sum + r[key], 0)
}

function staleCount(rows: InventoryTurnoverRow[]): number {
  return rows.filter((r) => r.stale).length
}

// ─────────────────────────── 客户贡献与复购 ───────────────────────────

const contributionState = reactive({
  range: defaultRange() as [string, string] | null,
  warehouseId: undefined as number | undefined,
  limit: 20,
  items: [] as CustomerContributionRow[],
  summary: null as CustomerContributionSummary | null,
  loading: false,
  error: '',
  loaded: false,
})

async function loadContribution() {
  if (!contributionState.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  contributionState.loading = true
  contributionState.error = ''
  try {
    const data = await reportApi.contribution({
      from: contributionState.range[0],
      to: contributionState.range[1],
      limit: contributionState.limit,
      warehouseId: contributionState.warehouseId,
    })
    contributionState.items = data.items
    contributionState.summary = data.summary
  } catch (error) {
    contributionState.items = []
    contributionState.summary = null
    contributionState.error = errorMessage(error)
  } finally {
    contributionState.loading = false
    contributionState.loaded = true
  }
}

function resetContribution() {
  contributionState.range = defaultRange()
  contributionState.warehouseId = undefined
  contributionState.limit = 20
  loadContribution()
}

function contributionRate(bp: number | undefined | null): string {
  return bp == null ? '-' : (bp / 100).toFixed(2) + '%'
}

// ─────────────────────────── 应收 / 应付账龄 ───────────────────────────

interface AgingState {
  asOf: string
  rows: AgingRow[]
  summary: AgingSummary | null
  loading: boolean
  error: string
  loaded: boolean
}

const agingStates = reactive<Record<'ar' | 'ap', AgingState>>({
  ar: { asOf: defaultAsOf(), rows: [], summary: null, loading: false, error: '', loaded: false },
  ap: { asOf: defaultAsOf(), rows: [], summary: null, loading: false, error: '', loaded: false },
})

async function loadAging(kind: 'ar' | 'ap') {
  const state = agingStates[kind]
  state.loading = true
  state.error = ''
  try {
    const data = await reportApi.aging({
      type: kind === 'ar' ? 'AR' : 'AP',
      asOf: state.asOf,
    })
    state.rows = data.items
    state.summary = data.summary
  } catch (error) {
    state.rows = []
    state.summary = null
    state.error = errorMessage(error)
  } finally {
    state.loading = false
    state.loaded = true
  }
}

function resetAging(kind: 'ar' | 'ap') {
  agingStates[kind].asOf = defaultAsOf()
  loadAging(kind)
}

function agingTotal(row: AgingRow): number {
  return row.bucket0 + row.bucket1 + row.bucket2 + row.bucket3
}

// ─────────────────────────── 导出 / 打印 ───────────────────────────

async function exportReport(
  path: string,
  params: Record<string, string | number | boolean | undefined | null>,
  label: string,
  fallbackName: string,
) {
  try {
    await downloadReportCsv(path, params, fallbackName)
    ElMessage.success(`${label}已开始下载`)
  } catch (error) {
    ElMessage.error(`导出失败：${errorMessage(error)}`)
  }
}

async function exportPurchase() {
  const state = docStates.purchase
  if (!state.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  await exportReport('/reports/purchase/export', {
    from: state.range[0], to: state.range[1],
    groupBy: state.groupBy, warehouseId: state.warehouseId,
  }, '进货统计', 'purchase.csv')
}

async function exportSales() {
  const state = docStates.sales
  if (!state.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  await exportReport('/reports/sales/export', {
    from: state.range[0], to: state.range[1],
    groupBy: state.groupBy, warehouseId: state.warehouseId,
  }, '出货统计', 'sales.csv')
}

async function exportInventory() {
  await exportReport('/reports/inventory-summary/export', {
    period: invState.period, warehouseId: invState.warehouseId,
  }, '进销存汇总', 'inventory-summary.csv')
}

async function exportOperating() {
  const state = operatingState
  if (!state.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  await exportReport('/reports/operating/export', {
    from: state.range[0], to: state.range[1],
    dimension: state.dimension, warehouseId: state.warehouseId,
  }, '经营报表', 'operating.csv')
}

async function exportTurnover() {
  const state = turnoverState
  if (!state.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  await exportReport('/reports/inventory-turnover/export', {
    from: state.range[0], to: state.range[1],
    deadDays: state.deadDays, warehouseId: state.warehouseId,
  }, '周转呆滞', 'inventory-turnover.csv')
}

async function exportContribution() {
  const state = contributionState
  if (!state.range) {
    ElMessage.warning('请先选择日期区间')
    return
  }
  await exportReport('/reports/customer-contribution/export', {
    from: state.range[0], to: state.range[1],
    limit: state.limit, warehouseId: state.warehouseId,
  }, '客户贡献', 'customer-contribution.csv')
}

async function exportAging(kind: 'ar' | 'ap') {
  await exportReport('/reports/aging/export', {
    type: kind === 'ar' ? 'AR' : 'AP',
    asOf: agingStates[kind].asOf,
  }, kind === 'ar' ? '应收账龄' : '应付账龄', `aging-${kind.toUpperCase()}.csv`)
}

function printPage() {
  window.print()
}

const printSubtitle = computed(() => {
  switch (activeTab.value) {
    case 'purchase':
    case 'sales': {
      const state = docStates[activeTab.value]
      return `${state.range?.[0] ?? '-'} ~ ${state.range?.[1] ?? '-'}`
    }
    case 'inventory':
      return invState.period
    case 'operating':
      return `${operatingState.range?.[0] ?? '-'} ~ ${operatingState.range?.[1] ?? '-'} · ${
        operatingState.dimension === 'day' ? '日报' : '月报'}`
    case 'turnover':
      return `${turnoverState.range?.[0] ?? '-'} ~ ${turnoverState.range?.[1] ?? '-'} · ${
        turnoverState.deadDays} 天无出库`
    case 'contribution':
      return `${contributionState.range?.[0] ?? '-'} ~ ${contributionState.range?.[1] ?? '-'}`
    case 'ar':
      return `截止 ${agingStates.ar.asOf}`
    case 'ap':
      return `截止 ${agingStates.ap.asOf}`
  }
})

// ─────────────────────────── 刷新 / 页签切换 / 首次加载 ───────────────────────────

function refresh() {
  switch (activeTab.value) {
    case 'purchase':
    case 'sales':
      loadDoc(activeTab.value)
      break
    case 'inventory':
      loadInventorySummary()
      break
    case 'operating':
      loadOperating()
      break
    case 'turnover':
      loadTurnover()
      break
    case 'contribution':
      loadContribution()
      break
    case 'ar':
      loadAging('ar')
      break
    case 'ap':
      loadAging('ap')
      break
  }
}

onMounted(async () => {
  await loadDictionary()
  await loadDoc('purchase')
})

function onTabChange(name: string | number) {
  const tab = name as Tab
  if (tab === 'purchase' && !docStates.purchase.loaded) loadDoc('purchase')
  if (tab === 'sales' && !docStates.sales.loaded) loadDoc('sales')
  if (tab === 'inventory' && !invState.loaded) loadInventorySummary()
  if (tab === 'operating' && !operatingState.loaded) loadOperating()
  if (tab === 'turnover' && !turnoverState.loaded) loadTurnover()
  if (tab === 'contribution' && !contributionState.loaded) loadContribution()
  if (tab === 'ar' && !agingStates.ar.loaded) loadAging('ar')
  if (tab === 'ap' && !agingStates.ap.loaded) loadAging('ap')
}
</script>

<template>
  <div class="page">
    <div class="page-header no-print">
      <h2 class="page-title">报表</h2>
      <div>
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <div class="print-only print-head">
      ERP 报表 · {{ TAB_LABELS[activeTab] }}<span v-if="printSubtitle"> · {{ printSubtitle }}</span>
    </div>

    <p class="page-hint no-print">
      只读页面：报表不产生单据、不写流水、不动结算行。 <b>两张表两种口径</b>：
      进货 / 出货统计、经营报表的进货与销售按<b>单据业务日期 biz_date</b>，
      进销存汇总、经营报表的期初/期末与周转呆滞按<b>库存流水发生时间 occurred_at</b>；
      同一笔跨天分批执行的业务在两边可能落到不同日期上，合计绝不跨口径相加。
    </p>

    <el-alert
      v-if="!canReadMaster"
      type="info"
      show-icon
      :closable="false"
      class="mb-16 no-print"
      title="当前账号读不到主数据名称字典（需要 mdm:read）"
      description="报表照常出数，但进销存汇总会显示「SKU#编号」「仓库#编号」而不是商品名与仓库名，仓库筛选下拉也是空的（只能看全部仓库）；名称与候选来自 /warehouses、/skus、/products 接口。"
    />

    <el-alert
      v-if="dictTruncated"
      type="info"
      show-icon
      :closable="false"
      class="mb-16 no-print"
      title="部分 SKU / 商品名称未能显示"
      description="主数据查询接口每页最多 200 条，且后端没有按 ID 批量取名的接口；字典外的行会显示 SKU#编号、仓库#编号。"
    />

    <el-tabs v-model="activeTab" class="rpt-tabs" @tab-change="onTabChange">
      <!-- ─────────── 进货统计 ─────────── -->
      <el-tab-pane label="进货统计" name="purchase">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="口径：单据业务日期 biz_date（闭区间），只统计有入库量的进货单"
          description="数量 = 累计入库量 received_qty，金额 = 单价 × 入库量；与进销存汇总的流水发生时间口径不同，两者的合计不可互相核对。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="docStates.purchase.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="filter-daterange"
            @change="loadDoc('purchase')"
          />
          <el-select
            v-model="docStates.purchase.groupBy"
            placeholder="分组：供应商（默认）"
            clearable
            class="filter-select-sm"
            @change="loadDoc('purchase')"
          >
            <el-option
              v-for="d in DOC_DIMS.purchase"
              :key="d.value"
              :label="`按${d.label}`"
              :value="d.value"
            />
          </el-select>
          <el-select
            v-model="docStates.purchase.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select-sm"
            @change="loadDoc('purchase')"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-button type="primary" :loading="docStates.purchase.loading" @click="loadDoc('purchase')">
            查询
          </el-button>
          <el-button @click="resetDoc('purchase')">重置</el-button>
          <el-button @click="exportPurchase">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="docStates.purchase.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`进货统计查询失败：${docStates.purchase.error}`"
        >
          <el-button size="small" @click="loadDoc('purchase')">重试</el-button>
        </el-alert>

        <PageTable
          :rows="docStates.purchase.rows"
          :loading="docStates.purchase.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!docStates.purchase.error"
          empty-text="这个区间没有进货记录"
          empty-hint="进货统计来自状态为部分入库 / 已完成的进货单；放宽日期区间再试。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              进货统计 · 按{{ effectiveDim('purchase') }}
              <span class="text-muted">
                {{ docStates.purchase.range?.[0] ?? '-' }} ~ {{ docStates.purchase.range?.[1] ?? '-' }}
                （含两端）· {{ docStates.purchase.rows.length }} 行
              </span>
            </div>
            <div class="totals-line">
              本页合计（按单据 biz_date）：<span class="qty">{{ docQty(docStates.purchase.rows) }}</span> 件 ·
              <span class="amount">¥ {{ fenToYuan(docAmount(docStates.purchase.rows)) }}</span>
              <span class="text-muted">「单据数」列各分组内部去重，跨行相加会重复计数，故不求和</span>
            </div>
          </template>

          <el-table-column label="分组键" prop="key" min-width="140" />
          <el-table-column label="名称" prop="label" min-width="200" />
          <el-table-column label="数量" width="110" align="right">
            <template #default="{ row }">
              <span class="qty">{{ row.qty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="140" align="right">
            <template #default="{ row }">
              <span class="amount">¥ {{ fenToYuan(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单据数" width="100" align="right">
            <template #default="{ row }">
              <span class="qty">{{ row.orderCount }}</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 出货统计 ─────────── -->
      <el-tab-pane label="出货统计" name="sales">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="口径：单据业务日期 biz_date（闭区间），只统计有出货量的出货单，不含退货"
          description="数量 = 累计出货量 shipped_qty，金额 = 单价 × 出货量 × 行折扣（万分比）；退货在结算页单独列示，本表展示的是毛出货。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="docStates.sales.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="filter-daterange"
            @change="loadDoc('sales')"
          />
          <el-select
            v-model="docStates.sales.groupBy"
            placeholder="分组：客户（默认）"
            clearable
            class="filter-select-sm"
            @change="loadDoc('sales')"
          >
            <el-option
              v-for="d in DOC_DIMS.sales"
              :key="d.value"
              :label="`按${d.label}`"
              :value="d.value"
            />
          </el-select>
          <el-select
            v-model="docStates.sales.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select-sm"
            @change="loadDoc('sales')"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-button type="primary" :loading="docStates.sales.loading" @click="loadDoc('sales')">
            查询
          </el-button>
          <el-button @click="resetDoc('sales')">重置</el-button>
          <el-button @click="exportSales">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="docStates.sales.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`出货统计查询失败：${docStates.sales.error}`"
        >
          <el-button size="small" @click="loadDoc('sales')">重试</el-button>
        </el-alert>

        <PageTable
          :rows="docStates.sales.rows"
          :loading="docStates.sales.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!docStates.sales.error"
          empty-text="这个区间没有出货记录"
          empty-hint="出货统计来自状态为部分出货 / 已完成的出货单；放宽日期区间再试。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              出货统计 · 按{{ effectiveDim('sales') }}
              <span class="text-muted">
                {{ docStates.sales.range?.[0] ?? '-' }} ~ {{ docStates.sales.range?.[1] ?? '-' }}
                （含两端）· {{ docStates.sales.rows.length }} 行
              </span>
            </div>
            <div class="totals-line">
              本页合计（按单据 biz_date）：<span class="qty">{{ docQty(docStates.sales.rows) }}</span> 件 ·
              <span class="amount">¥ {{ fenToYuan(docAmount(docStates.sales.rows)) }}</span>
              <span class="text-muted">毛出货，未扣退货；净额见结算页的「净销售额」</span>
            </div>
          </template>

          <el-table-column label="分组键" prop="key" min-width="140" />
          <el-table-column label="名称" prop="label" min-width="200" />
          <el-table-column label="数量" width="110" align="right">
            <template #default="{ row }">
              <span class="qty">{{ row.qty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="140" align="right">
            <template #default="{ row }">
              <span class="amount">¥ {{ fenToYuan(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="单据数" width="100" align="right">
            <template #default="{ row }">
              <span class="qty">{{ row.orderCount }}</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 进销存汇总 ─────────── -->
      <el-tab-pane label="进销存汇总" name="inventory">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="口径：库存流水发生时间 occurred_at（按账期整月），不是单据业务日期"
          description="期初 = 期间开始前的流水净和，入库 / 出库 = 期间内的正 / 负变动，期末 = 期初 + 入 − 出（不变量 INV-6，由后端一条聚合 SQL 分别求和保证）。本表与上面两张统计的合计互不相等是口径差异，不是数据错。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="invState.period"
            type="month"
            value-format="YYYY-MM"
            placeholder="账期（yyyy-MM）"
            class="filter-date"
            @change="loadInventorySummary"
          />
          <el-select
            v-model="invState.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select-sm"
            @change="loadInventorySummary"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-button type="primary" :loading="invState.loading" @click="loadInventorySummary">
            查询
          </el-button>
          <el-button @click="resetInventorySummary">重置</el-button>
          <el-button @click="exportInventory">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
          <span class="text-muted no-print">本表按（SKU × 仓库）一行，参数是账期 period（yyyy-MM），不是任意日期区间</span>
        </div>

        <el-alert
          v-if="invState.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`进销存汇总查询失败：${invState.error}`"
        >
          <el-button size="small" @click="loadInventorySummary">重试</el-button>
        </el-alert>

        <PageTable
          :rows="invState.rows"
          :loading="invState.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!invState.error"
          empty-text="这个账期没有流水"
          empty-hint="进销存汇总来自库存流水；该期间没有出入库时不会出行。换一个账期试试。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              进销存汇总 · {{ invState.period }}
              <span class="text-muted">（按流水 occurred_at）· {{ invState.rows.length }} 行</span>
            </div>
            <div class="totals-line">
              本页合计（件，按流水口径）：期初
              <span class="qty">{{ summaryQty(invState.rows, 'openingQty') }}</span> · 入
              <span class="qty">{{ summaryQty(invState.rows, 'inboundQty') }}</span> · 出
              <span class="qty">{{ summaryQty(invState.rows, 'outboundQty') }}</span> · 期末
              <span class="qty">{{ summaryQty(invState.rows, 'closingQty') }}</span>
              <span class="text-muted">恒等式：期初 + 入 − 出 = 期末（后端保证，页面不重算）</span>
            </div>
          </template>

          <el-table-column label="SKU" min-width="200">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="仓库" min-width="140">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="期初" width="100" align="right">
            <template #default="{ row }">
              <span class="qty">{{ row.openingQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本期入库" width="110" align="right">
            <template #default="{ row }">
              <span class="qty delta-in">{{ row.inboundQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本期出库" width="110" align="right">
            <template #default="{ row }">
              <span class="qty delta-out">{{ row.outboundQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="期末" width="100" align="right">
            <template #default="{ row }">
              <span class="qty">{{ row.closingQty }}</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 经营日报 / 月报 ─────────── -->
      <el-tab-pane label="经营日报/月报" name="operating">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="流水四数满足 INV-6；进货/销售是单据口径，两组数不可相加或互相推算"
          description="日粒度逐自然日覆盖区间，没有流水的日子也出行；月粒度按完整自然月统计（from/to 所在月份整月），这样每行的期初 + 入 − 出 = 期末才成立。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="operatingState.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="filter-daterange"
            @change="loadOperating"
          />
          <el-select v-model="operatingState.dimension" class="filter-select-sm" @change="loadOperating">
            <el-option label="日报（逐日）" value="day" />
            <el-option label="月报（整月）" value="month" />
          </el-select>
          <el-select
            v-model="operatingState.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select-sm"
            @change="loadOperating"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-button type="primary" :loading="operatingState.loading" @click="loadOperating">查询</el-button>
          <el-button @click="resetOperating">重置</el-button>
          <el-button @click="exportOperating">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="operatingState.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`经营报表查询失败：${operatingState.error}`"
        >
          <el-button size="small" @click="loadOperating">重试</el-button>
        </el-alert>

        <PageTable
          :rows="operatingState.rows"
          :loading="operatingState.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!operatingState.error"
          empty-text="这个区间没有数据"
          empty-hint="经营报表按日/月分桶，即使没有流水也应出现 0 行；若为空请检查查询参数。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              经营{{ operatingState.dimension === 'day' ? '日报' : '月报' }}
              <span class="text-muted">
                {{ operatingState.range?.[0] ?? '-' }} ~ {{ operatingState.range?.[1] ?? '-' }} ·
                {{ operatingState.rows.length }} 行
              </span>
            </div>
            <div class="totals-line">
              流水口径：期初 <span class="qty">{{ operatingOpening(operatingState.rows) }}</span> · 入
              <span class="qty delta-in">{{ sumOperating(operatingState.rows, 'inboundQty') }}</span> · 出
              <span class="qty delta-out">{{ sumOperating(operatingState.rows, 'outboundQty') }}</span> · 期末
              <span class="qty">{{ operatingClosing(operatingState.rows) }}</span>
              <span class="text-muted">
                每行恒等式：{{ operatingAllIdentitiesOk(operatingState.rows) ? '全部成立' : '存在异常' }}
              </span>
            </div>
            <div class="totals-line">
              单据口径（不可与流水相加）：进货
              <span class="qty">{{ sumOperating(operatingState.rows, 'purchaseQty') }}</span> 件 ·
              <span class="amount">¥ {{ fenToYuan(sumOperating(operatingState.rows, 'purchaseAmount')) }}</span>
              · 出货 <span class="qty">{{ sumOperating(operatingState.rows, 'salesQty') }}</span> 件 ·
              <span class="amount">¥ {{ fenToYuan(sumOperating(operatingState.rows, 'salesAmount')) }}</span>
            </div>
          </template>

          <el-table-column label="周期" prop="period" min-width="110" />
          <el-table-column label="期初" width="90" align="right">
            <template #default="{ row }"><span class="qty">{{ row.openingQty }}</span></template>
          </el-table-column>
          <el-table-column label="本期入库" width="100" align="right">
            <template #default="{ row }"><span class="qty delta-in">{{ row.inboundQty }}</span></template>
          </el-table-column>
          <el-table-column label="本期出库" width="100" align="right">
            <template #default="{ row }"><span class="qty delta-out">{{ row.outboundQty }}</span></template>
          </el-table-column>
          <el-table-column label="期末" width="90" align="right">
            <template #default="{ row }"><span class="qty">{{ row.closingQty }}</span></template>
          </el-table-column>
          <el-table-column label="恒等式" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="operatingIdentityOk(row) ? 'success' : 'danger'" size="small">
                {{ operatingIdentityOk(row) ? '成立' : '异常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进货数量" width="100" align="right">
            <template #default="{ row }">{{ row.purchaseQty }}</template>
          </el-table-column>
          <el-table-column label="进货金额" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.purchaseAmount) }}</template>
          </el-table-column>
          <el-table-column label="出货数量" width="100" align="right">
            <template #default="{ row }">{{ row.salesQty }}</template>
          </el-table-column>
          <el-table-column label="出货金额" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.salesAmount) }}</template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 库存周转与呆滞 ─────────── -->
      <el-tab-pane label="库存周转与呆滞" name="turnover">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="口径：库存流水 occurred_at，与进销存汇总同源"
          description="周转天数 = ((期初 + 期末) / 2) × 区间天数 / 区间出库，保留一位小数；区间无出库显示「-」。呆滞 = 观察日 to 距最近一次出库的天数 ≥ deadDays（从未出库则从首次流水日起算）。只列出期末仍有库存的 SKU。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="turnoverState.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="filter-daterange"
            @change="loadTurnover"
          />
          <span class="filter-inline">呆滞阈值</span>
          <el-input-number
            v-model="turnoverState.deadDays"
            :min="1"
            :max="3650"
            :precision="0"
            class="filter-number"
            @change="loadTurnover"
          />
          <span class="filter-inline">天无出库</span>
          <el-select
            v-model="turnoverState.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select-sm"
            @change="loadTurnover"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-switch v-model="turnoverState.onlyStale" active-text="只看呆滞" />
          <el-button type="primary" :loading="turnoverState.loading" @click="loadTurnover">查询</el-button>
          <el-button @click="resetTurnover">重置</el-button>
          <el-button @click="exportTurnover">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="turnoverState.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`周转呆滞查询失败：${turnoverState.error}`"
        >
          <el-button size="small" @click="loadTurnover">重试</el-button>
        </el-alert>

        <PageTable
          :rows="visibleTurnoverRows"
          :loading="turnoverState.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!turnoverState.error"
          empty-text="这个区间没有库存"
          empty-hint="周转与呆滞只统计期末仍有库存的 SKU；可放宽区间或关闭「只看呆滞」。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              库存周转与呆滞
              <span class="text-muted">
                {{ turnoverState.range?.[0] ?? '-' }} ~ {{ turnoverState.range?.[1] ?? '-' }} ·
                阈值 {{ turnoverState.deadDays }} 天 · {{ visibleTurnoverRows.length }} 行
              </span>
            </div>
            <div class="totals-line">
              期末库存 <span class="qty">{{ turnoverTotal(visibleTurnoverRows, 'closingQty') }}</span> 件 ·
              区间出库 <span class="qty">{{ turnoverTotal(visibleTurnoverRows, 'outboundQty') }}</span> 件 ·
              呆滞 <span class="qty">{{ staleCount(visibleTurnoverRows) }}</span> 个 SKU
              <span class="text-muted">「只看呆滞」仅影响页面展示，导出始终按当前查询参数输出全量</span>
            </div>
          </template>

          <el-table-column label="SKU 编码" prop="skuCode" min-width="150" />
          <el-table-column label="SKU 名称" prop="skuName" min-width="190" />
          <el-table-column label="仓库" prop="warehouseName" min-width="140" />
          <el-table-column label="期初" width="90" align="right">
            <template #default="{ row }">{{ row.openingQty }}</template>
          </el-table-column>
          <el-table-column label="期末" width="90" align="right">
            <template #default="{ row }"><span class="qty">{{ row.closingQty }}</span></template>
          </el-table-column>
          <el-table-column label="区间出库" width="100" align="right">
            <template #default="{ row }"><span class="qty delta-out">{{ row.outboundQty }}</span></template>
          </el-table-column>
          <el-table-column label="周转天数" width="100" align="right">
            <template #default="{ row }"><span class="qty">{{ turnoverText(row.turnoverDays) }}</span></template>
          </el-table-column>
          <el-table-column label="呆滞天数" width="100" align="right">
            <template #default="{ row }">{{ row.idleDays }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.stale ? 'danger' : 'success'" size="small">
                {{ row.stale ? '呆滞' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 客户贡献与复购 ─────────── -->
      <el-tab-pane label="客户贡献与复购" name="contribution">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="与出货统计同口径：数量/金额/单据数同一公式、同一状态集合"
          description="复购 = 期间单据数 ≥ 2；新客 = 开始日期之前没有任何已执行出货单，否则为回头客。Top N 只截断列表，下方汇总始终覆盖全部客户。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="contributionState.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="filter-daterange"
            @change="loadContribution"
          />
          <el-select v-model="contributionState.limit" class="filter-select-sm" @change="loadContribution">
            <el-option label="Top 10" :value="10" />
            <el-option label="Top 20" :value="20" />
            <el-option label="Top 50" :value="50" />
            <el-option label="Top 100" :value="100" />
            <el-option label="Top 200" :value="200" />
          </el-select>
          <el-select
            v-model="contributionState.warehouseId"
            placeholder="全部仓库"
            clearable
            class="filter-select-sm"
            @change="loadContribution"
          >
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
          <el-button type="primary" :loading="contributionState.loading" @click="loadContribution">
            查询
          </el-button>
          <el-button @click="resetContribution">重置</el-button>
          <el-button @click="exportContribution">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="contributionState.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`客户贡献查询失败：${contributionState.error}`"
        >
          <el-button size="small" @click="loadContribution">重试</el-button>
        </el-alert>

        <PageTable
          :rows="contributionState.items"
          :loading="contributionState.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!contributionState.error"
          empty-text="这个区间没有出货客户"
          empty-hint="客户贡献来自已执行出货单；放宽日期区间或换仓库再试。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              客户贡献 · Top {{ contributionState.limit }}
              <span class="text-muted">
                {{ contributionState.range?.[0] ?? '-' }} ~ {{ contributionState.range?.[1] ?? '-' }} ·
                {{ contributionState.items.length }} 行
              </span>
            </div>
            <div v-if="contributionState.summary" class="totals-line">
              全量 {{ contributionState.summary.customerCount }} 个客户 · 复购
              <span class="qty">{{ contributionState.summary.repurchaseCount }}</span>
              （{{ contributionRate(contributionState.summary.repurchaseRateBp) }}）· 新客
              <span class="qty">{{ contributionState.summary.newCustomerCount }}</span> / 回头客
              <span class="qty">{{ contributionState.summary.returningCustomerCount }}</span> ·
              销量 <span class="qty">{{ contributionState.summary.totalQty }}</span> 件 · 销售额
              <span class="amount">¥ {{ fenToYuan(contributionState.summary.totalAmount) }}</span> ·
              单据 <span class="qty">{{ contributionState.summary.orderCount }}</span>
              <span class="text-muted">汇总覆盖全部客户，不受 Top N 截断影响</span>
            </div>
          </template>

          <el-table-column label="客户编码" prop="customerCode" min-width="130" />
          <el-table-column label="客户名称" prop="customerName" min-width="190" />
          <el-table-column label="单据数" width="90" align="right">
            <template #default="{ row }"><span class="qty">{{ row.orderCount }}</span></template>
          </el-table-column>
          <el-table-column label="销量" width="100" align="right">
            <template #default="{ row }">{{ row.qty }}</template>
          </el-table-column>
          <el-table-column label="销售额" width="140" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ fenToYuan(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column label="复购" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.repurchase ? 'success' : 'info'" size="small">
                {{ row.repurchase ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="客户类型" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.newCustomer ? 'warning' : 'info'" size="small">
                {{ row.newCustomer ? '新客' : '回头客' }}
              </el-tag>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 应收账龄 ─────────── -->
      <el-tab-pane label="应收账龄" name="ar">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="应收账龄：按到期日 due_date 起算，未到期/无到期日归 0-30"
          description="只统计未作废且余额 > 0 的应收；四桶合计必须等于余额（行级与汇总级）。账龄以观察日 asOf 为基准。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="agingStates.ar.asOf"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="观察日"
            class="filter-date"
            @change="loadAging('ar')"
          />
          <el-button type="primary" :loading="agingStates.ar.loading" @click="loadAging('ar')">查询</el-button>
          <el-button @click="resetAging('ar')">重置</el-button>
          <el-button @click="exportAging('ar')">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="agingStates.ar.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`应收账龄查询失败：${agingStates.ar.error}`"
        >
          <el-button size="small" @click="loadAging('ar')">重试</el-button>
        </el-alert>

        <PageTable
          :rows="agingStates.ar.rows"
          :loading="agingStates.ar.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!agingStates.ar.error"
          empty-text="这个观察日没有未结应收"
          empty-hint="只统计未作废且余额 > 0 的应收；可换一个观察日再试。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              应收账龄 · 截止 {{ agingStates.ar.asOf }}
              <span class="text-muted">{{ agingStates.ar.rows.length }} 个客户</span>
            </div>
            <div v-if="agingStates.ar.summary" class="totals-line">
              余额 <span class="amount">¥ {{ fenToYuan(agingStates.ar.summary.totalBalance) }}</span> ·
              0-30 <span class="qty">¥ {{ fenToYuan(agingStates.ar.summary.bucket0) }}</span> ·
              31-60 <span class="qty">¥ {{ fenToYuan(agingStates.ar.summary.bucket1) }}</span> ·
              61-90 <span class="qty">¥ {{ fenToYuan(agingStates.ar.summary.bucket2) }}</span> ·
              90+ <span class="qty">¥ {{ fenToYuan(agingStates.ar.summary.bucket3) }}</span>
              <span class="text-muted">四桶合计 = 余额（后端保证）</span>
            </div>
          </template>

          <el-table-column label="客户编码" prop="partnerCode" min-width="130" />
          <el-table-column label="客户名称" prop="partnerName" min-width="180" />
          <el-table-column label="余额" width="130" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ fenToYuan(row.balanceAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="0-30" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.bucket0) }}</template>
          </el-table-column>
          <el-table-column label="31-60" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.bucket1) }}</template>
          </el-table-column>
          <el-table-column label="61-90" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.bucket2) }}</template>
          </el-table-column>
          <el-table-column label="90+" width="120" align="right">
            <template #default="{ row }">
              <span :class="{ 'delta-out': row.bucket3 > 0 }">¥ {{ fenToYuan(row.bucket3) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="桶合计" width="130" align="right">
            <template #default="{ row }">
              <span class="qty" :class="{ 'identity-bad': agingTotal(row) !== row.balanceAmount }">
                ¥ {{ fenToYuan(agingTotal(row)) }}
              </span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 应付账龄 ─────────── -->
      <el-tab-pane label="应付账龄" name="ap">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          title="应付账龄：供应商无约定到期日，按应付单业务日期 biz_date 起算"
          description="只统计未作废且余额 > 0 的应付；四桶合计必须等于余额（行级与汇总级）。账龄以观察日 asOf 为基准。"
        />

        <div class="filters no-print">
          <el-date-picker
            v-model="agingStates.ap.asOf"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="观察日"
            class="filter-date"
            @change="loadAging('ap')"
          />
          <el-button type="primary" :loading="agingStates.ap.loading" @click="loadAging('ap')">查询</el-button>
          <el-button @click="resetAging('ap')">重置</el-button>
          <el-button @click="exportAging('ap')">导出 CSV</el-button>
          <el-button @click="printPage">打印</el-button>
        </div>

        <el-alert
          v-if="agingStates.ap.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16 no-print"
          :title="`应付账龄查询失败：${agingStates.ap.error}`"
        >
          <el-button size="small" @click="loadAging('ap')">重试</el-button>
        </el-alert>

        <PageTable
          :rows="agingStates.ap.rows"
          :loading="agingStates.ap.loading"
          :paginated="false"
          :filtered="true"
          :load-failed="!!agingStates.ap.error"
          empty-text="这个观察日没有未结应付"
          empty-hint="只统计余额 > 0 的应付；可换一个观察日再试。"
          table-size="small"
        >
          <template #header>
            <div class="table-title">
              应付账龄 · 截止 {{ agingStates.ap.asOf }}
              <span class="text-muted">{{ agingStates.ap.rows.length }} 个供应商</span>
            </div>
            <div v-if="agingStates.ap.summary" class="totals-line">
              余额 <span class="amount">¥ {{ fenToYuan(agingStates.ap.summary.totalBalance) }}</span> ·
              0-30 <span class="qty">¥ {{ fenToYuan(agingStates.ap.summary.bucket0) }}</span> ·
              31-60 <span class="qty">¥ {{ fenToYuan(agingStates.ap.summary.bucket1) }}</span> ·
              61-90 <span class="qty">¥ {{ fenToYuan(agingStates.ap.summary.bucket2) }}</span> ·
              90+ <span class="qty">¥ {{ fenToYuan(agingStates.ap.summary.bucket3) }}</span>
              <span class="text-muted">四桶合计 = 余额（后端保证）</span>
            </div>
          </template>

          <el-table-column label="供应商编码" prop="partnerCode" min-width="130" />
          <el-table-column label="供应商名称" prop="partnerName" min-width="180" />
          <el-table-column label="余额" width="130" align="right">
            <template #default="{ row }"><span class="amount">¥ {{ fenToYuan(row.balanceAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="0-30" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.bucket0) }}</template>
          </el-table-column>
          <el-table-column label="31-60" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.bucket1) }}</template>
          </el-table-column>
          <el-table-column label="61-90" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.bucket2) }}</template>
          </el-table-column>
          <el-table-column label="90+" width="120" align="right">
            <template #default="{ row }">
              <span :class="{ 'delta-out': row.bucket3 > 0 }">¥ {{ fenToYuan(row.bucket3) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="桶合计" width="130" align="right">
            <template #default="{ row }">
              <span class="qty" :class="{ 'identity-bad': agingTotal(row) !== row.balanceAmount }">
                ¥ {{ fenToYuan(agingTotal(row)) }}
              </span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.rpt-tabs {
  margin-top: 4px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-daterange {
  width: 300px;
}

.filter-date {
  width: 180px;
}

.filter-select-sm {
  width: 170px;
}

.filter-number {
  width: 120px;
}

.filter-inline {
  font-size: 13px;
  color: #606266;
}

.table-title {
  font-weight: 600;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.totals-line {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: #606266;
}

.qty {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.amount {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.delta-in {
  color: #67c23a;
}

.delta-out {
  color: #f56c6c;
}

.identity-bad {
  color: #f56c6c;
}

.print-only {
  display: none;
}

@media print {
  .no-print,
  .page-header,
  .page-hint,
  .filters,
  .el-alert,
  :deep(.el-tabs__header) {
    display: none !important;
  }

  .print-only {
    display: block;
  }

  .print-head {
    margin-bottom: 12px;
    font-size: 18px;
    font-weight: 700;
    text-align: center;
  }

  .page {
    padding: 0;
  }

  .totals-line {
    color: #000;
  }

  :deep(.el-table) {
    width: 100% !important;
    font-size: 12px;
  }

  :deep(.el-table th),
  :deep(.el-table td) {
    padding: 4px 0;
  }

  @page {
    size: A4 landscape;
    margin: 10mm;
  }
}

@media (max-width: 768px) {
  .filters {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-daterange,
  .filter-date,
  .filter-select-sm,
  .filter-number {
    width: 100%;
  }
}
</style>
