<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  invApi,
  mdmApi,
  fenToYuan,
  type InventoryPageRow,
  type LedgerEntry,
  type Warehouse,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'

/**
 * 库存查询（FR-INV-01 余额 + FR-INV-02 流水）。
 *
 * ★ 这一页唯一的难点是**语义**，不是数据量：
 *   实存（onHandQty）是"仓库里有多少"，预占（reservedQty）是"已经被单据占走多少"，
 *   可用（availableQty）才是"还能卖多少"。把它们混为一谈是库存系统最常见的误用，
 *   所以三列各自用固定的颜色 + 表头说明，而不是只放三个数字。
 *
 * ★ 前端不做任何判断（计划书 §0）：
 *   availableQty 由后端返回，这里不校验"是否等于 onHand − reserved"；
 *   够不够卖、能不能下单，全部是后端的规则。
 *
 * ★ <b>余额必须服务端分页</b>（2026-10-27 改）：
 *   原来一次性拉全量（演示库 2000+ 行）再前端渲染，打开页面要等好几秒。
 *   现在走 /inventory/page：一页 50 行、关键字过滤，首屏只查一次。
 *   顺带把"名称翻译"挪到后端 —— 之前前端拉 200 条 SKU 字典做映射，
 *   而 SKU 有 600 个，字典外的行只能显示 SKU#123。
 */

const activeTab = ref<'balance' | 'ledger'>('balance')

// ─────────────────────────── 仓库字典 ───────────────────────────

/**
 * 仓库只有十来个，仍然值得拉一份做下拉。
 *
 * 刻意用 onlyEnabled=false：余额/流水里的仓库可能是已停用的仓，
 * 过滤掉就查不出名字、也没法按它筛选。
 */
const warehouses = ref<Warehouse[]>([])

async function loadWarehouses() {
  await mdmApi.listWarehouses(false).then(
    (list) => {
      warehouses.value = list
    },
    () => {
      /* 仓库字典失败不影响查库存：后端会直接返回仓库名 */
    },
  )
}

/** 拦截器已经弹过一次 ElMessage，这里只是把消息留在页面上（刷新后仍可回看）。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

// ─────────────────────────── 库存余额 ───────────────────────────

const balanceFilters = reactive({
  keyword: '',
  warehouseId: undefined as number | undefined,
  batchNo: '',
  onlyAvailable: false,
})

const balances = ref<InventoryPageRow[]>([])
const balanceTotal = ref(0)
const balancePage = ref(1)
const balancePageSize = ref(50)
const balanceLoading = ref(false)
const balanceError = ref('')
const balanceFiltered = computed(
  () =>
    balanceFilters.keyword.trim() !== '' ||
    balanceFilters.warehouseId !== undefined ||
    balanceFilters.batchNo.trim() !== '' ||
    balanceFilters.onlyAvailable,
)

async function loadBalances() {
  balanceLoading.value = true
  balanceError.value = ''
  try {
    const data = await invApi.page({
      keyword: balanceFilters.keyword.trim() || undefined,
      warehouseId: balanceFilters.warehouseId,
      batchNo: balanceFilters.batchNo.trim() || undefined,
      onlyAvailable: balanceFilters.onlyAvailable,
      page: balancePage.value,
      size: balancePageSize.value,
    })
    balances.value = data.items
    balanceTotal.value = data.total
    cacheFromBalances(data.items)
  } catch (error) {
    balances.value = []
    balanceTotal.value = 0
    balanceError.value = errorMessage(error)
  } finally {
    balanceLoading.value = false
  }
}

function searchBalances() {
  balancePage.value = 1
  loadBalances()
}

function resetBalanceFilters() {
  balanceFilters.keyword = ''
  balanceFilters.warehouseId = undefined
  balanceFilters.batchNo = ''
  balanceFilters.onlyAvailable = false
  searchBalances()
}

function onBalancePageChange(page: number, pageSize: number) {
  balancePage.value = page
  balancePageSize.value = pageSize
  loadBalances()
}

// ─────────────────────────── 名称缓存（流水页用） ───────────────────────────

/**
 * 余额行自带名称（后端 join 出来的），顺手存进缓存；
 * 流水行只有 skuId / warehouseId，需要靠这份缓存翻译。
 *
 * 字典是**懒加载**的：只有切到流水页才拉（SKU 600 条要三页）。
 * 余额页因此完全不依赖字典 —— 这正是把 2000 行一次性拉慢的旧路径。
 */
const skuCache = ref(new Map<number, string>())
const warehouseCache = ref(new Map<number, string>())
/** 流水页的 SKU 下拉选项：字典拉完后才可用（此前只有 id 没有名字，筛了也看不懂）。 */
const skuOptions = ref<{ id: number; name: string }[]>([])
let dictLoaded = false

function cacheFromBalances(rows: InventoryPageRow[]) {
  const nextSku = new Map(skuCache.value)
  const nextWh = new Map(warehouseCache.value)
  for (const r of rows) {
    if (r.skuName) nextSku.set(r.skuId, r.skuName)
    if (r.warehouseName) nextWh.set(r.warehouseId, r.warehouseName)
  }
  skuCache.value = nextSku
  warehouseCache.value = nextWh
}

/** 拉一份全量 SKU/商品字典（分页拉完，最多 8 页）。只在首次进入流水页时发生。 */
async function ensureNameDict() {
  if (dictLoaded) return
  dictLoaded = true
  try {
    const products = await mdmApi.listProducts({ page: 1, size: 200 })
    const productNames = new Map(products.items.map((p) => [p.id, p.name]))
    const nextSku = new Map(skuCache.value)
    for (let page = 1; page <= 8; page++) {
      const res = await mdmApi.listSkus({ page, pageSize: 200 })
      for (const s of res.items) {
        const product = productNames.get(s.productId) ?? `商品#${s.productId}`
        nextSku.set(s.id, `${product} · ${s.color}-${s.size}`)
      }
      if (res.items.length === 0 || nextSku.size >= res.total) break
    }
    skuCache.value = nextSku
    skuOptions.value = [...nextSku]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch {
    // 字典拉不到就退回显示 ID —— 宁可不完整，也不要让流水页打不开
    dictLoaded = false
  }
}

function skuText(id: number): string {
  return skuCache.value.get(id) ?? `SKU#${id}`
}

function warehouseText(id: number): string {
  const fromCache = warehouseCache.value.get(id)
  if (fromCache) return fromCache
  const w = warehouses.value.find((x) => x.id === id)
  if (!w) return `仓库#${id}`
  return w.enabled ? w.name : `${w.name}（已停用）`
}

// ─────────────────────────── 库存流水 ───────────────────────────

const ledgerFilters = reactive({
  skuId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  bizRefNo: '',
  /** el-date-picker 给的是 Date；发给后端前转成 ISO-8601 带时区字符串 */
  range: null as [Date, Date] | null,
})

const ledger = ref<LedgerEntry[]>([])
const ledgerTotal = ref(0)
const ledgerPage = ref(1)
const ledgerPageSize = ref(20)
const ledgerLoading = ref(false)
const ledgerError = ref('')
const ledgerLoaded = ref(false)
const ledgerFiltered = computed(
  () =>
    ledgerFilters.skuId !== undefined ||
    ledgerFilters.warehouseId !== undefined ||
    ledgerFilters.bizRefNo.trim() !== '' ||
    ledgerFilters.range !== null,
)

async function loadLedger() {
  ledgerLoading.value = true
  ledgerError.value = ''
  try {
    const data = await invApi.ledger({
      skuId: ledgerFilters.skuId,
      warehouseId: ledgerFilters.warehouseId,
      bizRefNo: ledgerFilters.bizRefNo.trim() || undefined,
      // toISOString() 产出 UTC 的 Z 后缀，是后端 OffsetDateTime 能解析的 ISO-8601
      from: ledgerFilters.range?.[0]?.toISOString(),
      to: ledgerFilters.range?.[1]?.toISOString(),
      page: ledgerPage.value,
      size: ledgerPageSize.value,
    })
    ledger.value = data.items
    ledgerTotal.value = data.total
  } catch (error) {
    ledger.value = []
    ledgerTotal.value = 0
    ledgerError.value = errorMessage(error)
  } finally {
    ledgerLoading.value = false
    ledgerLoaded.value = true
  }
}

function searchLedger() {
  ledgerPage.value = 1
  loadLedger()
}

function resetLedgerFilters() {
  ledgerFilters.skuId = undefined
  ledgerFilters.warehouseId = undefined
  ledgerFilters.bizRefNo = ''
  ledgerFilters.range = null
  searchLedger()
}

function onLedgerPageChange(page: number, pageSize: number) {
  ledgerPage.value = page
  ledgerPageSize.value = pageSize
  loadLedger()
}

function onTabChange(name: string | number) {
  if (name !== 'ledger') return
  // 流水页要翻译 SKU 名称，字典在这里才拉（余额页不依赖它）
  void ensureNameDict()
  if (!ledgerLoaded.value) loadLedger()
}

/**
 * 从余额行跳到流水：带上同一 SKU、同一仓库。
 * 批次没法带过去 —— 后端 /inventory/ledger 没有 batchNo 参数（已记为缺口）。
 */
function inspectBalance(row: InventoryPageRow) {
  ledgerFilters.skuId = row.skuId
  ledgerFilters.warehouseId = row.warehouseId
  ledgerFilters.bizRefNo = ''
  ledgerFilters.range = null
  ledgerPage.value = 1
  activeTab.value = 'ledger'
  void ensureNameDict()
  loadLedger()
}

/** 按来源单据号反查：把该单据产生的所有出入库拉出来。 */
function traceByRefNo(bizRefNo: string) {
  if (!bizRefNo) return
  ledgerFilters.bizRefNo = bizRefNo
  ledgerPage.value = 1
  loadLedger()
}

/** 业务类型 → 中文标签（纯文案映射）。未知代码原样显示，绝不显示成空白。 */
const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_IN: '进货入库',
  SALES_OUT: '销售出库',
  RETURN_IN: '退货入库',
  TRANSFER_IN: '调拨入库',
  TRANSFER_OUT: '调拨出库',
  ADJUST_IN: '调整入库',
  ADJUST_OUT: '调整出库',
}

function bizTypeLabel(code: string): string {
  return BIZ_TYPE_LABELS[code] ?? code
}

/** 正负号只是把数字自带的方向显示出来，不做任何判断。 */
function signed(qty: number): string {
  return qty > 0 ? `+${qty}` : String(qty)
}

function dateTimeText(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

onMounted(async () => {
  // 首屏只查一页库存 + 一份仓库下拉；SKU 字典等切到流水页再拉
  await loadWarehouses()
  await loadBalances()
})

function refresh() {
  if (activeTab.value === 'balance') loadBalances()
  else loadLedger()
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">库存查询</h2>
      <el-button @click="refresh">刷新</el-button>
    </div>
    <p class="page-hint">
      只读页面：库存由进货单入库、出货单出库产生，这里不修改任何数量。
      可用量是下单依据，实存与预占的差额由后端计算并返回。
    </p>

    <el-tabs v-model="activeTab" class="inv-tabs" @tab-change="onTabChange">
      <!-- ─────────── 库存余额 ─────────── -->
      <el-tab-pane label="库存余额" name="balance">
        <el-alert
          v-if="balanceError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`库存查询失败：${balanceError}`"
        >
          <el-button size="small" @click="loadBalances">重试</el-button>
        </el-alert>

        <PageTable
          :rows="balances"
          :loading="balanceLoading"
          :total="balanceTotal"
          :page="balancePage"
          :page-size="balancePageSize"
          :filtered="balanceFiltered"
          :load-failed="!!balanceError"
          empty-text="还没有库存"
          empty-hint="库存由进货单入库产生；也可以放宽筛选条件再试。"
          table-size="small"
          @update:page="balancePage = $event"
          @update:page-size="balancePageSize = $event"
          @change="onBalancePageChange"
        >
          <template #filters>
            <el-input
              v-model="balanceFilters.keyword"
              placeholder="搜商品编码/名称、条码、批次、仓库"
              clearable
              class="filter-keyword"
              @keyup.enter="searchBalances"
              @clear="searchBalances"
            />
            <el-select
              v-model="balanceFilters.warehouseId"
              placeholder="全部仓库"
              clearable
              class="filter-select-sm"
              @change="searchBalances"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-input
              v-model="balanceFilters.batchNo"
              placeholder="批次号（精确匹配）"
              clearable
              class="filter-input"
              @keyup.enter="searchBalances"
              @clear="searchBalances"
            />
            <el-checkbox v-model="balanceFilters.onlyAvailable" @change="searchBalances">
              只看有可用量的行
            </el-checkbox>
            <el-button type="primary" @click="searchBalances">查询</el-button>
            <el-button @click="resetBalanceFilters">重置</el-button>
          </template>

          <template #header>
            <div class="table-title">
              库存行（SKU × 仓库 × 批次）
              <span class="text-muted">共 {{ balanceTotal }} 行，按页加载</span>
            </div>
            <div class="qty-legend">
              <span><i class="qty-dot dot-onhand" />实存：仓库里实际有多少</span>
              <span><i class="qty-dot dot-reserved" />预占：已被单据占用、还没出库</span>
              <span><i class="qty-dot dot-available" />可用：还能卖 / 还能下单</span>
            </div>
          </template>

          <el-table-column label="SKU" min-width="240">
            <template #default="{ row }">
              <div class="sku-cell">
                <span class="sku-name">{{ row.skuName || `SKU#${row.skuId}` }}</span>
                <span v-if="row.productCode" class="sku-code">{{ row.productCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="仓库" min-width="140">
            <template #default="{ row }">{{ row.warehouseName || warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="批次" min-width="120">
            <template #default="{ row }">
              <el-tag v-if="!row.batchNo" type="info" size="small">无批次</el-tag>
              <span v-else>{{ row.batchNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="效期" width="110">
            <template #default="{ row }">{{ row.expiryDate || '-' }}</template>
          </el-table-column>
          <el-table-column width="110" align="right">
            <template #header>
              <div class="qty-head">
                <i class="qty-dot dot-onhand" />
                <span>实存</span>
              </div>
            </template>
            <template #default="{ row }">
              <span class="qty qty-onhand-text">{{ row.onHandQty }}</span>
            </template>
          </el-table-column>
          <el-table-column width="110" align="right">
            <template #header>
              <div class="qty-head">
                <i class="qty-dot dot-reserved" />
                <span>预占</span>
              </div>
            </template>
            <template #default="{ row }">
              <span class="qty qty-reserved-text">{{ row.reservedQty }}</span>
            </template>
          </el-table-column>
          <el-table-column width="120" align="right">
            <template #header>
              <div class="qty-head">
                <i class="qty-dot dot-available" />
                <span>可用</span>
              </div>
            </template>
            <template #default="{ row }">
              <span class="qty qty-available-text">{{ row.availableQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="inspectBalance(row)">看流水</el-button>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 库存流水 ─────────── -->
      <el-tab-pane label="库存流水" name="ledger">
        <el-alert
          v-if="ledgerError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`流水查询失败：${ledgerError}`"
        >
          <el-button size="small" @click="loadLedger">重试</el-button>
        </el-alert>

        <PageTable
          :rows="ledger"
          :loading="ledgerLoading"
          :total="ledgerTotal"
          :page="ledgerPage"
          :page-size="ledgerPageSize"
          :filtered="ledgerFiltered"
          :load-failed="!!ledgerError"
          empty-text="还没有流水"
          empty-hint="流水只在实存发生变动时写入；预占与释放不改变实存，因此不会出现在这里。"
          table-size="small"
          @update:page="ledgerPage = $event"
          @update:page-size="ledgerPageSize = $event"
          @change="onLedgerPageChange"
        >
          <template #filters>
            <el-select
              v-model="ledgerFilters.skuId"
              placeholder="全部 SKU"
              filterable
              clearable
              class="filter-select"
              @change="searchLedger"
            >
              <el-option v-for="s in skuOptions" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-select
              v-model="ledgerFilters.warehouseId"
              placeholder="全部仓库"
              clearable
              class="filter-select-sm"
              @change="searchLedger"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-input
              v-model="ledgerFilters.bizRefNo"
              placeholder="来源单据号（精确匹配）"
              clearable
              class="filter-input"
              @keyup.enter="searchLedger"
              @clear="searchLedger"
            />
            <el-date-picker
              v-model="ledgerFilters.range"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              format="YYYY-MM-DD HH:mm"
              class="filter-daterange"
              @change="searchLedger"
            />
            <el-button type="primary" @click="searchLedger">查询</el-button>
            <el-button @click="resetLedgerFilters">重置</el-button>
          </template>

          <template #header>
            <div class="table-title">
              出入库流水（不可变）
              <span class="text-muted">共 {{ ledgerTotal }} 条</span>
            </div>
            <div class="text-muted">
              点「来源单据号」可反查该单据产生的全部出入库；后端按发生时间倒序返回。
            </div>
          </template>

          <el-table-column label="发生时间" width="170">
            <template #default="{ row }">{{ dateTimeText(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column label="SKU" min-width="180">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="仓库" min-width="120">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="批次" min-width="110">
            <template #default="{ row }">
              <el-tag v-if="!row.batchNo" type="info" size="small">无批次</el-tag>
              <span v-else>{{ row.batchNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="变动数量" width="110" align="right">
            <template #default="{ row }">
              <span :class="row.changeQty > 0 ? 'delta-in' : 'delta-out'">
                {{ signed(row.changeQty) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="业务类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" type="info">{{ bizTypeLabel(row.bizType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源单据号" min-width="180">
            <template #default="{ row }">
              <el-button link type="primary" @click="traceByRefNo(row.bizRefNo)">
                {{ row.bizRefNo }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="单位成本" width="110" align="right">
            <template #default="{ row }">
              <span class="amount">{{ fenToYuan(row.unitCostAmount) }}</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.inv-tabs {
  margin-top: 4px;
}

.filter-keyword {
  width: 300px;
}

/* 流水页的 SKU 下拉仍用这个宽度 */
.filter-select {
  width: 260px;
}

.filter-select-sm {
  width: 160px;
}

.filter-input {
  width: 200px;
}

.filter-daterange {
  width: 340px;
}

.table-title {
  font-weight: 600;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

/* SKU 两行：主名 + 商品编码，避免长名字把表格撑歪 */
.sku-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.sku-name {
  font-weight: 500;
}

.sku-code {
  font-size: 12px;
  color: #909399;
  font-variant-numeric: tabular-nums;
}

/* 三个数字的语义靠"同色的点 + 同色的数"绑定，避免只看数字猜是哪个 */
.qty-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.qty-head {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.qty-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 4px;
  border-radius: 50%;
  vertical-align: middle;
}

.dot-onhand {
  background: #606266;
}

.dot-reserved {
  background: #e6a23c;
}

.dot-available {
  background: #67c23a;
}

.qty {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.qty-onhand-text {
  color: #606266;
}

.qty-reserved-text {
  color: #e6a23c;
}

.qty-available-text {
  color: #67c23a;
}

.delta-in {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: #67c23a;
}

.delta-out {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: #f56c6c;
}

@media (max-width: 768px) {
  .filter-select,
  .filter-select-sm,
  .filter-input,
  .filter-daterange {
    width: 100%;
  }
}
</style>
