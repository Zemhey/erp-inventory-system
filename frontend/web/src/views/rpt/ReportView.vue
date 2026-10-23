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
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 报表（FR-RPT-01/02/03，计划书 §3.5 / §3.8）。
 *
 * ★ 这一页真正要防的是**口径混用**（§6 坑 4），不是渲染：
 *   进货 / 出货统计按**单据业务日期 `biz_date`**（回答"业务单据算哪一天"），
 *   进销存汇总按**库存流水 `occurred_at`**（回答"货什么时候真的动了"）。
 *   同一笔跨天分批执行的业务，两张表会落在不同日期上 —— 这不是 bug，是口径。
 *   所以每个标签页都把自己那一条口径写在筛选区下方，合计也只在本口径内相加。
 *
 * ★ `orderCount` 是各分组内部按单据去重的结果（COUNT DISTINCT），
 *   跨行相加会把"一张单同时属于两个分组"重复计数，因此这里刻意**不**给它做合计。
 *
 * ★ 报表只读：不产生单据、不写流水、不动结算行（后端用 ArchUnit 钉死了这条）。
 *   页面也不做任何判断 —— 期初+入−出=期末由后端在一条聚合 SQL 里分别求和保证（TC-RPT-006），
 *   这里不重算、不校验，只把四个数摊开给人看。
 */

const activeTab = ref<'purchase' | 'sales' | 'inventory'>('purchase')

// ─────────────────────────── 主数据字典（只为显示名称） ───────────────────────────

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])
/** 后端 /skus、/products 每页最多 200 条，超出部分字典里没有 → 界面要说明。 */
const dictTruncated = ref(false)

/**
 * 名称字典来自 /warehouses、/skus、/products（要 `mdm:read`），
 * 而 FINANCE 只有 `rpt:read`：报表本身能出数，名字出不来。
 * 先问权限再发请求，否则每次进页面都换一个 403 红条，页面反而说不清"为什么只有编号"。
 * （缺口已登记计划书 §6，S7 定夺：补 `('FINANCE','mdm:read')` 或让报表内联名称）
 */
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

// ─────────────────────────── 进货 / 出货统计（同构，只差默认分组维度） ───────────────────────────

type DocTab = 'purchase' | 'sales'

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

function refresh() {
  if (activeTab.value === 'inventory') loadInventorySummary()
  else loadDoc(activeTab.value)
}

// ─────────────────────────── 首次加载 ───────────────────────────

onMounted(async () => {
  await loadDictionary()
  await loadDoc('purchase')
})

function onTabChange(name: string | number) {
  if (name === 'purchase' && !docStates.purchase.loaded) loadDoc('purchase')
  if (name === 'sales' && !docStates.sales.loaded) loadDoc('sales')
  if (name === 'inventory' && !invState.loaded) loadInventorySummary()
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">报表</h2>
      <div>
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>
    <p class="page-hint">
      只读页面：报表不产生单据、不写流水、不动结算行。 <b>两张表两种口径</b>：
      进货 / 出货统计按<b>单据业务日期 biz_date</b>，进销存汇总按<b>库存流水发生时间 occurred_at</b>；
      同一笔跨天分批执行的业务在两边可能落到不同日期上，合计绝不跨口径相加。
    </p>

    <el-alert
      v-if="!canReadMaster"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="当前账号读不到主数据名称字典（需要 mdm:read）"
      description="报表照常出数，但进销存汇总会显示「SKU#编号」「仓库#编号」而不是商品名与仓库名，仓库筛选下拉也是空的（只能看全部仓库）；名称与候选来自 /warehouses、/skus、/products 接口。"
    />

    <el-alert
      v-if="dictTruncated"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
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
          class="mb-16"
          title="口径：单据业务日期 biz_date（闭区间），只统计有入库量的进货单"
          description="数量 = 累计入库量 received_qty，金额 = 单价 × 入库量；与进销存汇总的流水发生时间口径不同，两者的合计不可互相核对。"
        />

        <div class="filters">
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
        </div>

        <el-alert
          v-if="docStates.purchase.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
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
          class="mb-16"
          title="口径：单据业务日期 biz_date（闭区间），只统计有出货量的出货单，不含退货"
          description="数量 = 累计出货量 shipped_qty，金额 = 单价 × 出货量 × 行折扣（万分比）；退货在结算页单独列示，本表展示的是毛出货。"
        />

        <div class="filters">
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
        </div>

        <el-alert
          v-if="docStates.sales.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
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
          class="mb-16"
          title="口径：库存流水发生时间 occurred_at（按账期整月），不是单据业务日期"
          description="期初 = 期间开始前的流水净和，入库 / 出库 = 期间内的正 / 负变动，期末 = 期初 + 入 − 出（不变量 RPT-03，由后端一条聚合 SQL 分别求和保证）。本表与上面两张统计的合计互不相等是口径差异，不是数据错。"
        />

        <div class="filters">
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
          <span class="text-muted">本表按（SKU × 仓库）一行，参数是账期 period（yyyy-MM），不是任意日期区间</span>
        </div>

        <el-alert
          v-if="invState.error"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
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

.delta-in {
  color: #67c23a;
}

.delta-out {
  color: #f56c6c;
}

@media (max-width: 768px) {
  .filters {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-daterange,
  .filter-date,
  .filter-select-sm {
    width: 100%;
  }
}
</style>
