<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  invApi,
  mdmApi,
  fenToYuan,
  type InventoryBalance,
  type LedgerEntry,
  type Product,
  type Sku,
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
 * ★ 名称字典：后端余额与流水只返回 skuId / warehouseId，界面必须可读，
 *   因此用主数据接口取一份字典做**显示映射**（不是业务计算）。
 *   取不到名字时退回显示 ID —— 宁可不完整，也不要显示成空白让人以为没数据。
 */

const activeTab = ref<'balance' | 'ledger'>('balance')

// ─────────────────────────── 主数据字典 ───────────────────────────

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])
/** 后端 /skus、/products 的 size 上限是 200，超出部分字典里没有 → 界面要说明。 */
const dictTruncated = ref(false)

/** 停用仓库也要能显示名字：这里刻意用 onlyEnabled=false。 */
async function loadDictionary() {
  const [wh, sku, prod] = await Promise.allSettled([
    // 余额/流水里的仓库可能是已停用的仓，过滤掉就查不出名字了
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

const warehouseNames = computed(() => {
  const map = new Map<number, string>()
  for (const w of warehouses.value) map.set(w.id, w.enabled ? w.name : `${w.name}（已停用）`)
  return map
})

const skuNames = computed(() => {
  const productNames = new Map<number, string>(products.value.map((p) => [p.id, p.name]))
  const map = new Map<number, string>()
  for (const s of skus.value) {
    const product = productNames.get(s.productId) ?? `商品#${s.productId}`
    map.set(s.id, `${product} · ${s.color}-${s.size}`)
  }
  return map
})

function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}

function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

/** 拦截器已经弹过一次 ElMessage，这里只是把消息留在页面上（刷新后仍可回看）。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

// ─────────────────────────── 库存余额 ───────────────────────────

const balanceFilters = reactive({
  skuId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  batchNo: '',
  onlyAvailable: false,
})

const balances = ref<InventoryBalance[]>([])
const balanceLoading = ref(false)
const balanceError = ref('')
const balanceFiltered = computed(
  () =>
    balanceFilters.skuId !== undefined ||
    balanceFilters.warehouseId !== undefined ||
    balanceFilters.batchNo.trim() !== '' ||
    balanceFilters.onlyAvailable,
)

async function loadBalances() {
  balanceLoading.value = true
  balanceError.value = ''
  try {
    balances.value = await invApi.query({
      skuId: balanceFilters.skuId,
      warehouseId: balanceFilters.warehouseId,
      batchNo: balanceFilters.batchNo.trim() || undefined,
      onlyAvailable: balanceFilters.onlyAvailable,
    })
  } catch (error) {
    balances.value = []
    balanceError.value = errorMessage(error)
  } finally {
    balanceLoading.value = false
  }
}

function resetBalanceFilters() {
  balanceFilters.skuId = undefined
  balanceFilters.warehouseId = undefined
  balanceFilters.batchNo = ''
  balanceFilters.onlyAvailable = false
  loadBalances()
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
  if (name === 'ledger' && !ledgerLoaded.value) loadLedger()
}

/**
 * 从余额行跳到流水：带上同一 SKU、同一仓库。
 * 批次没法带过去 —— 后端 /inventory/ledger 没有 batchNo 参数（已记为缺口）。
 */
function inspectBalance(row: InventoryBalance) {
  ledgerFilters.skuId = row.skuId
  ledgerFilters.warehouseId = row.warehouseId
  ledgerFilters.bizRefNo = ''
  ledgerFilters.range = null
  ledgerPage.value = 1
  activeTab.value = 'ledger'
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
  await loadDictionary()
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

    <el-alert
      v-if="dictTruncated"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="部分 SKU / 商品名称未能显示"
      description="主数据查询接口每页最多 200 条，且后端没有按 ID 批量取名的接口；字典外的行会显示 SKU#编号、仓库#编号。"
    />

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
          :paginated="false"
          :filtered="balanceFiltered"
          empty-text="还没有库存"
          empty-hint="库存由进货单入库产生；也可以放宽筛选条件再试。"
          table-size="small"
        >
          <template #filters>
            <el-select
              v-model="balanceFilters.skuId"
              placeholder="全部 SKU"
              filterable
              clearable
              class="filter-select"
              @change="loadBalances"
            >
              <el-option v-for="s in skus" :key="s.id" :label="skuText(s.id)" :value="s.id" />
            </el-select>
            <el-select
              v-model="balanceFilters.warehouseId"
              placeholder="全部仓库"
              clearable
              class="filter-select-sm"
              @change="loadBalances"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-input
              v-model="balanceFilters.batchNo"
              placeholder="批次号（精确匹配）"
              clearable
              class="filter-input"
              @keyup.enter="loadBalances"
              @clear="loadBalances"
            />
            <el-checkbox v-model="balanceFilters.onlyAvailable" @change="loadBalances">
              只看有可用量的行
            </el-checkbox>
            <el-button type="primary" @click="loadBalances">查询</el-button>
            <el-button @click="resetBalanceFilters">重置</el-button>
          </template>

          <template #header>
            <div class="table-title">
              库存行（SKU × 仓库 × 批次）
              <span class="text-muted">共 {{ balances.length }} 行</span>
            </div>
            <div class="qty-legend">
              <span><i class="qty-dot dot-onhand" />实存：仓库里实际有多少</span>
              <span><i class="qty-dot dot-reserved" />预占：已被单据占用、还没出库</span>
              <span><i class="qty-dot dot-available" />可用：还能卖 / 还能下单</span>
            </div>
          </template>

          <el-table-column label="SKU" min-width="200">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="仓库" min-width="140">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
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
              <el-option v-for="s in skus" :key="s.id" :label="skuText(s.id)" :value="s.id" />
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
