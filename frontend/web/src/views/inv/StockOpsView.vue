<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  invOpsApi,
  mdmApi,
  type AlertType,
  type BalanceDimensionRow,
  type BalanceDimensionTotals,
  type FreezeStatus,
  type InventoryAdjustment,
  type InventoryAlertRow,
  type InventoryFreeze,
  type Product,
  type ReplenishmentSuggestion,
  type Sku,
  type StockStatus,
  type Stocktake,
  type StocktakeStatus,
  type Warehouse,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'
import PageTable from '@/components/PageTable.vue'

/**
 * 库存作业第二页：盘点 / 调整 / 冻结 / 预警 / 补货建议。
 *
 * ★ 为什么合并成一页而不是五页：
 *   这五件事单独出现的频率都不高（盘点按月、调整偶发、预警与补货是"看"），
 *   拆成五个菜单只会让侧边栏变长、每次都要多点一次。
 *   用 Tab 合并后，仓管员的日常路径是"打开库存作业 → 看预警 → 需要时补货"。
 *
 * ★ 前端不做任何库存规则判断：
 *   差异由后端生成列计算（diff = counted − book）；
 *   补货建议是否重复由后端排除"已下单未到货"；
 *   预警的临期/滞销边界由后端注入时钟计算。页面只展示。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('inv:write'))
const activeTab = ref<'stocktake' | 'adjustment' | 'freeze' | 'dimension' | 'alert' | 'replenishment'>(
  'stocktake',
)

// ─────────────────────────── 主数据字典 ───────────────────────────

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])

async function loadDictionary() {
  const [wh, sku, prod] = await Promise.allSettled([
    mdmApi.listWarehouses(false),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
}

const warehouseNames = computed(() => new Map(warehouses.value.map((w) => [w.id, w.name])))
const productNames = computed(() => new Map(products.value.map((p) => [p.id, p.name])))
const skuNames = computed(() => {
  const map = new Map<number, string>()
  for (const s of skus.value) {
    map.set(s.id, `${productNames.value.get(s.productId) ?? `商品#${s.productId}`} · ${s.color}-${s.size}`)
  }
  return map
})
function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}
function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}
function dateTimeText(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

// ─────────────────────────── 盘点 ───────────────────────────

const stocktakeFilters = reactive({
  status: undefined as StocktakeStatus | undefined,
  warehouseId: undefined as number | undefined,
})
const stocktakes = ref<Stocktake[]>([])
const stocktakeTotal = ref(0)
const stocktakePage = ref(1)
const stocktakePageSize = ref(20)
const stocktakeLoading = ref(false)
const stocktakeError = ref('')
const stocktakeFiltered = computed(
  () => stocktakeFilters.status !== undefined || stocktakeFilters.warehouseId !== undefined,
)

async function loadStocktakes() {
  stocktakeLoading.value = true
  stocktakeError.value = ''
  try {
    const data = await invOpsApi.listStocktakes({
      status: stocktakeFilters.status,
      warehouseId: stocktakeFilters.warehouseId,
      page: stocktakePage.value,
      size: stocktakePageSize.value,
    })
    stocktakes.value = data.items
    stocktakeTotal.value = data.total
  } catch (error) {
    stocktakes.value = []
    stocktakeTotal.value = 0
    stocktakeError.value = errorMessage(error)
  } finally {
    stocktakeLoading.value = false
  }
}

function searchStocktakes() {
  stocktakePage.value = 1
  loadStocktakes()
}

interface StocktakeLineForm {
  skuId: number | undefined
  batchNo: string
  countedQty: number
}
const stocktakeDialog = ref(false)
const stocktakeSubmitting = ref(false)
const stocktakeForm = reactive({
  warehouseId: undefined as number | undefined,
  remark: '',
  lines: [] as StocktakeLineForm[],
})

function openStocktakeDialog() {
  stocktakeForm.warehouseId = undefined
  stocktakeForm.remark = ''
  stocktakeForm.lines = [{ skuId: undefined, batchNo: '', countedQty: 0 }]
  stocktakeDialog.value = true
}

function addStocktakeLine() {
  stocktakeForm.lines.push({ skuId: undefined, batchNo: '', countedQty: 0 })
}
function removeStocktakeLine(index: number) {
  if (stocktakeForm.lines.length === 1) return
  stocktakeForm.lines.splice(index, 1)
}

async function submitStocktake() {
  if (!stocktakeForm.warehouseId) {
    ElMessage.warning('请选择盘点仓库')
    return
  }
  const lines = stocktakeForm.lines.filter((l) => l.skuId !== undefined)
  if (lines.length === 0) {
    ElMessage.warning('至少填写一行盘点明细')
    return
  }
  if (lines.some((l) => l.countedQty < 0)) {
    ElMessage.warning('实盘数量不能为负')
    return
  }
  stocktakeSubmitting.value = true
  try {
    await invOpsApi.createStocktake({
      warehouseId: stocktakeForm.warehouseId,
      remark: stocktakeForm.remark.trim() || undefined,
      lines: lines.map((l) => ({
        skuId: l.skuId as number,
        batchNo: l.batchNo.trim() || undefined,
        countedQty: l.countedQty,
      })),
    })
    ElMessage.success('盘点单已创建（账面量已快照）')
    stocktakeDialog.value = false
    searchStocktakes()
  } catch {
    // 拦截器已提示
  } finally {
    stocktakeSubmitting.value = false
  }
}

async function postStocktake(row: Stocktake) {
  // 差异合计可能为 0（+2 与 -2 相抵），但只要有非 0 行就仍可过账。
  const hasDifference = row.lines.some((line) => line.diffQty !== 0)
  const diff = row.totalDiffQty
  try {
    await ElMessageBox.confirm(
      !hasDifference
        ? '该盘点单全部差异为 0，后端会拒绝过账。请先核对实盘数量。'
        : `将对 ${row.lines.filter((l) => l.diffQty !== 0).length} 行差异合计 ${diff > 0 ? '+' : ''}${diff} 生成调整流水，过账后不可撤销。`,
      '确认过账',
      { confirmButtonText: '确认过账', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await invOpsApi.postStocktake(row.id)
    ElMessage.success('盘点已过账，差异已生成调整流水')
    loadStocktakes()
  } catch {
    // 后端会给出 INV_STOCKTAKE_NO_DIFFERENCE 等明确原因
  }
}

// ─────────────────────────── 调整 ───────────────────────────

const adjustmentFilters = reactive({
  skuId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
})
const adjustments = ref<InventoryAdjustment[]>([])
const adjustmentTotal = ref(0)
const adjustmentPage = ref(1)
const adjustmentPageSize = ref(20)
const adjustmentLoading = ref(false)
const adjustmentError = ref('')
const adjustmentFiltered = computed(
  () => adjustmentFilters.skuId !== undefined || adjustmentFilters.warehouseId !== undefined,
)

async function loadAdjustments() {
  adjustmentLoading.value = true
  adjustmentError.value = ''
  try {
    const data = await invOpsApi.listAdjustments({
      skuId: adjustmentFilters.skuId,
      warehouseId: adjustmentFilters.warehouseId,
      page: adjustmentPage.value,
      size: adjustmentPageSize.value,
    })
    adjustments.value = data.items
    adjustmentTotal.value = data.total
  } catch (error) {
    adjustments.value = []
    adjustmentTotal.value = 0
    adjustmentError.value = errorMessage(error)
  } finally {
    adjustmentLoading.value = false
  }
}
function searchAdjustments() {
  adjustmentPage.value = 1
  loadAdjustments()
}

const adjustmentDialog = ref(false)
const adjustmentSubmitting = ref(false)
const adjustmentForm = reactive({
  skuId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  batchNo: '',
  changeQty: 1,
  reason: '',
})

function openAdjustmentDialog() {
  adjustmentForm.skuId = undefined
  adjustmentForm.warehouseId = undefined
  adjustmentForm.batchNo = ''
  adjustmentForm.changeQty = 1
  adjustmentForm.reason = ''
  adjustmentDialog.value = true
}

async function submitAdjustment() {
  if (!adjustmentForm.skuId || !adjustmentForm.warehouseId) {
    ElMessage.warning('请选择 SKU 与仓库')
    return
  }
  if (!adjustmentForm.reason.trim()) {
    ElMessage.warning('必须填写调整原因（审计依据）')
    return
  }
  if (adjustmentForm.changeQty === 0) {
    ElMessage.warning('调整数量不能为 0')
    return
  }
  adjustmentSubmitting.value = true
  try {
    await invOpsApi.createAdjustment({
      skuId: adjustmentForm.skuId,
      warehouseId: adjustmentForm.warehouseId,
      batchNo: adjustmentForm.batchNo.trim() || undefined,
      changeQty: adjustmentForm.changeQty,
      reason: adjustmentForm.reason.trim(),
    })
    ElMessage.success('库存调整已记账')
    adjustmentDialog.value = false
    searchAdjustments()
  } catch {
    // 拦截器已提示
  } finally {
    adjustmentSubmitting.value = false
  }
}

// ─────────────────────────── 冻结 ───────────────────────────

const freezeFilters = reactive({
  status: undefined as FreezeStatus | undefined,
  warehouseId: undefined as number | undefined,
})
const freezes = ref<InventoryFreeze[]>([])
const freezeTotal = ref(0)
const freezePage = ref(1)
const freezePageSize = ref(20)
const freezeLoading = ref(false)
const freezeError = ref('')
const freezeFiltered = computed(
  () => freezeFilters.status !== undefined || freezeFilters.warehouseId !== undefined,
)

async function loadFreezes() {
  freezeLoading.value = true
  freezeError.value = ''
  try {
    const data = await invOpsApi.listFreezes({
      status: freezeFilters.status,
      warehouseId: freezeFilters.warehouseId,
      page: freezePage.value,
      size: freezePageSize.value,
    })
    freezes.value = data.items
    freezeTotal.value = data.total
  } catch (error) {
    freezes.value = []
    freezeTotal.value = 0
    freezeError.value = errorMessage(error)
  } finally {
    freezeLoading.value = false
  }
}
function searchFreezes() {
  freezePage.value = 1
  loadFreezes()
}

const freezeDialog = ref(false)
const freezeSubmitting = ref(false)
const freezeForm = reactive({
  skuId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  batchNo: '',
  qty: 1,
  reason: '',
})

function openFreezeDialog() {
  freezeForm.skuId = undefined
  freezeForm.warehouseId = undefined
  freezeForm.batchNo = ''
  freezeForm.qty = 1
  freezeForm.reason = ''
  freezeDialog.value = true
}

async function submitFreeze() {
  if (!freezeForm.skuId || !freezeForm.warehouseId) {
    ElMessage.warning('请选择 SKU 与仓库')
    return
  }
  if (freezeForm.qty <= 0) {
    ElMessage.warning('冻结数量必须为正')
    return
  }
  freezeSubmitting.value = true
  try {
    await invOpsApi.createFreeze({
      skuId: freezeForm.skuId,
      warehouseId: freezeForm.warehouseId,
      batchNo: freezeForm.batchNo.trim() || undefined,
      qty: freezeForm.qty,
      reason: freezeForm.reason.trim() || undefined,
    })
    ElMessage.success('库存已冻结，冻结量不再计入可用量')
    freezeDialog.value = false
    searchFreezes()
  } catch {
    // 后端会给出"可用量不足"的定位信息
  } finally {
    freezeSubmitting.value = false
  }
}

async function releaseFreeze(row: InventoryFreeze) {
  try {
    await ElMessageBox.confirm(
      `解冻 ${skuText(row.skuId)} × ${row.qty}，解冻后这部分数量重新计入可用量。`,
      '确认解冻',
      { confirmButtonText: '确认解冻', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await invOpsApi.releaseFreeze(row.id)
    ElMessage.success('已解冻')
    loadFreezes()
  } catch {
    // 拦截器已提示
  }
}

// ─────────────────────────── 五维结存（FR-INV-11） ───────────────────────────

const dimensionFilters = reactive({
  skuId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  batchNo: '',
  status: undefined as StockStatus | undefined,
})
const dimensions = ref<BalanceDimensionRow[]>([])
const dimensionTotals = ref<BalanceDimensionTotals>({
  onHandQty: 0,
  byStatus: { AVAILABLE: 0, RESERVED: 0, FROZEN: 0 },
})
const dimensionLoading = ref(false)
const dimensionError = ref('')

async function loadDimensions() {
  dimensionLoading.value = true
  dimensionError.value = ''
  try {
    const data = await invOpsApi.balanceDimensions({
      skuId: dimensionFilters.skuId,
      warehouseId: dimensionFilters.warehouseId,
      batchNo: dimensionFilters.batchNo.trim() || undefined,
      status: dimensionFilters.status,
    })
    dimensions.value = data.items
    dimensionTotals.value = data.totals
  } catch (error) {
    dimensions.value = []
    dimensionTotals.value = { onHandQty: 0, byStatus: { AVAILABLE: 0, RESERVED: 0, FROZEN: 0 } }
    dimensionError.value = errorMessage(error)
  } finally {
    dimensionLoading.value = false
  }
}

function stockStatusText(status: StockStatus): string {
  if (status === 'AVAILABLE') return '可用'
  if (status === 'RESERVED') return '预占'
  return '冻结'
}

// ─────────────────────────── 预警 / 补货 ───────────────────────────

const alertFilters = reactive({
  warehouseId: undefined as number | undefined,
  type: undefined as AlertType | undefined,
})
const alerts = ref<InventoryAlertRow[]>([])
const alertLoading = ref(false)
const alertError = ref('')

async function loadAlerts() {
  alertLoading.value = true
  alertError.value = ''
  try {
    const data = await invOpsApi.alerts({
      warehouseId: alertFilters.warehouseId,
      type: alertFilters.type,
    })
    alerts.value = data.items
  } catch (error) {
    alerts.value = []
    alertError.value = errorMessage(error)
  } finally {
    alertLoading.value = false
  }
}

const replenishmentWarehouseId = ref<number | undefined>(undefined)
const replenishments = ref<ReplenishmentSuggestion[]>([])
const replenishmentLoading = ref(false)
const replenishmentError = ref('')

async function loadReplenishment() {
  replenishmentLoading.value = true
  replenishmentError.value = ''
  try {
    const data = await invOpsApi.replenishment({ warehouseId: replenishmentWarehouseId.value })
    replenishments.value = data.items
  } catch (error) {
    replenishments.value = []
    replenishmentError.value = errorMessage(error)
  } finally {
    replenishmentLoading.value = false
  }
}

function alertTypeText(type: AlertType): string {
  if (type === 'SAFETY') return '安全库存'
  if (type === 'SLOW_MOVING') return '滞销'
  return '临期'
}
function alertTypeTag(type: AlertType): 'danger' | 'warning' | 'info' {
  if (type === 'SAFETY') return 'danger'
  if (type === 'EXPIRING') return 'warning'
  return 'info'
}
function freezeStatusText(status: FreezeStatus): string {
  if (status === 'ACTIVE') return '冻结中'
  if (status === 'RELEASED') return '已解冻'
  return '已超时解冻'
}
function freezeStatusTag(status: FreezeStatus): 'warning' | 'success' | 'info' {
  if (status === 'ACTIVE') return 'warning'
  if (status === 'RELEASED') return 'success'
  return 'info'
}
function stocktakeStatusText(status: StocktakeStatus): string {
  return status === 'DRAFT' ? '待过账' : '已过账'
}

onMounted(async () => {
  await loadDictionary()
  await Promise.all([
    loadStocktakes(),
    loadAdjustments(),
    loadFreezes(),
    loadDimensions(),
    loadAlerts(),
    loadReplenishment(),
  ])
})
</script>

<template>
  <div class="stock-ops-page">
    <el-tabs v-model="activeTab" type="border-card">
      <!-- ─────────── 盘点 ─────────── -->
      <el-tab-pane label="盘点" name="stocktake">
        <PageTable
          :rows="stocktakes"
          :loading="stocktakeLoading"
          :total="stocktakeTotal"
          :page="stocktakePage"
          :page-size="stocktakePageSize"
          :filtered="stocktakeFiltered"
          :load-failed="!!stocktakeError"
          :can-create="canWrite"
          create-text="新建第一张盘点单"
          empty-text="还没有盘点单"
          empty-hint="盘点建单时快照账面量；过账时只对差异行生成调整流水，差异为 0 不产生流水。"
          @update:page="stocktakePage = $event"
          @update:page-size="stocktakePageSize = $event"
          @change="(p, s) => { stocktakePage = p; stocktakePageSize = s; loadStocktakes() }"
          @create="openStocktakeDialog"
        >
          <template #filters>
            <el-select
              v-model="stocktakeFilters.warehouseId"
              placeholder="仓库"
              clearable
              style="width: 160px"
              @change="searchStocktakes"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-select
              v-model="stocktakeFilters.status"
              placeholder="状态"
              clearable
              style="width: 130px"
              @change="searchStocktakes"
            >
              <el-option label="待过账" value="DRAFT" />
              <el-option label="已过账" value="POSTED" />
            </el-select>
            <el-button type="primary" :disabled="!canWrite" @click="openStocktakeDialog">新建盘点</el-button>
            <span v-if="stocktakeError" class="error-text">{{ stocktakeError }}</span>
          </template>

          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-lines">
                <el-table :data="row.lines" size="small" border>
                  <el-table-column label="行号" prop="lineNo" width="70" />
                  <el-table-column label="SKU" min-width="200">
                    <template #default="{ row: line }">{{ skuText(line.skuId) }}</template>
                  </el-table-column>
                  <el-table-column label="批次" prop="batchNo" width="120" />
                  <el-table-column label="账面量" prop="bookQty" width="100" />
                  <el-table-column label="实盘量" prop="countedQty" width="100" />
                  <el-table-column label="差异" width="100">
                    <template #default="{ row: line }">
                      <el-tag v-if="line.diffQty !== 0" :type="line.diffQty > 0 ? 'success' : 'danger'" size="small">
                        {{ line.diffQty > 0 ? '+' : '' }}{{ line.diffQty }}
                      </el-tag>
                      <span v-else>0</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="盘点单号" prop="stocktakeNo" min-width="170" />
          <el-table-column label="仓库" min-width="120">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'POSTED' ? 'success' : 'warning'" size="small">
                {{ stocktakeStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="差异合计" prop="totalDiffQty" width="100" />
          <el-table-column label="业务日期" prop="bizDate" width="120" />
          <el-table-column label="过账时间" width="170">
            <template #default="{ row }">{{ dateTimeText(row.postedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'DRAFT'"
                type="primary"
                link
                :disabled="!canWrite"
                @click="postStocktake(row)"
              >
                过账
              </el-button>
              <span v-else class="text-muted">已完成</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 调整 ─────────── -->
      <el-tab-pane label="调整" name="adjustment">
        <PageTable
          :rows="adjustments"
          :loading="adjustmentLoading"
          :total="adjustmentTotal"
          :page="adjustmentPage"
          :page-size="adjustmentPageSize"
          :filtered="adjustmentFiltered"
          :load-failed="!!adjustmentError"
          :can-create="canWrite"
          create-text="新建第一笔调整"
          empty-text="还没有库存调整"
          empty-hint="调整是偶发的库存变动：必须填写原因，调减不得低于已预占/冻结的数量。"
          @update:page="adjustmentPage = $event"
          @update:page-size="adjustmentPageSize = $event"
          @change="(p, s) => { adjustmentPage = p; adjustmentPageSize = s; loadAdjustments() }"
          @create="openAdjustmentDialog"
        >
          <template #filters>
            <el-select
              v-model="adjustmentFilters.warehouseId"
              placeholder="仓库"
              clearable
              style="width: 160px"
              @change="searchAdjustments"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-select
              v-model="adjustmentFilters.skuId"
              placeholder="SKU"
              clearable
              filterable
              style="width: 240px"
              @change="searchAdjustments"
            >
              <el-option v-for="s in skus" :key="s.id" :label="skuText(s.id)" :value="s.id" />
            </el-select>
            <el-button type="primary" :disabled="!canWrite" @click="openAdjustmentDialog">新建调整</el-button>
            <span v-if="adjustmentError" class="error-text">{{ adjustmentError }}</span>
          </template>

          <el-table-column label="调整单号" prop="adjustNo" min-width="170" />
          <el-table-column label="SKU" min-width="200">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="仓库" min-width="120">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="批次" prop="batchNo" width="120" />
          <el-table-column label="数量" width="100">
            <template #default="{ row }">
              <el-tag :type="row.changeQty > 0 ? 'success' : 'danger'" size="small">
                {{ row.changeQty > 0 ? '+' : '' }}{{ row.changeQty }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因" prop="reason" min-width="180" />
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ dateTimeText(row.createdAt) }}</template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 冻结 ─────────── -->
      <el-tab-pane label="冻结" name="freeze">
        <PageTable
          :rows="freezes"
          :loading="freezeLoading"
          :total="freezeTotal"
          :page="freezePage"
          :page-size="freezePageSize"
          :filtered="freezeFiltered"
          :load-failed="!!freezeError"
          :can-create="canWrite"
          create-text="新建第一笔冻结"
          empty-text="没有冻结记录"
          empty-hint="冻结量不参与可用量，也不会被预占/出库/调拨扣减；解冻后恢复。"
          @update:page="freezePage = $event"
          @update:page-size="freezePageSize = $event"
          @change="(p, s) => { freezePage = p; freezePageSize = s; loadFreezes() }"
          @create="openFreezeDialog"
        >
          <template #filters>
            <el-select
              v-model="freezeFilters.warehouseId"
              placeholder="仓库"
              clearable
              style="width: 160px"
              @change="searchFreezes"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-select
              v-model="freezeFilters.status"
              placeholder="状态"
              clearable
              style="width: 150px"
              @change="searchFreezes"
            >
              <el-option label="冻结中" value="ACTIVE" />
              <el-option label="已解冻" value="RELEASED" />
              <el-option label="已超时解冻" value="EXPIRED" />
            </el-select>
            <el-button type="primary" :disabled="!canWrite" @click="openFreezeDialog">新建冻结</el-button>
            <span v-if="freezeError" class="error-text">{{ freezeError }}</span>
          </template>

          <el-table-column label="SKU" min-width="200">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="仓库" min-width="120">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column label="批次" prop="batchNo" width="120" />
          <el-table-column label="冻结量" prop="qty" width="100" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="freezeStatusTag(row.status)" size="small">
                {{ freezeStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原因" prop="reason" min-width="150" />
          <el-table-column label="到期时间" width="170">
            <template #default="{ row }">{{ dateTimeText(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="解冻时间" width="170">
            <template #default="{ row }">{{ dateTimeText(row.releasedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'ACTIVE'"
                type="primary"
                link
                :disabled="!canWrite"
                @click="releaseFreeze(row)"
              >
                解冻
              </el-button>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ─────────── 五维结存 ─────────── -->
      <el-tab-pane label="五维结存" name="dimension">
        <el-card shadow="never">
          <div class="filters">
            <el-select
              v-model="dimensionFilters.warehouseId"
              placeholder="全部仓库"
              clearable
              style="width: 160px"
              @change="loadDimensions"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-select
              v-model="dimensionFilters.skuId"
              placeholder="全部 SKU"
              clearable
              filterable
              style="width: 240px"
              @change="loadDimensions"
            >
              <el-option v-for="s in skus" :key="s.id" :label="skuText(s.id)" :value="s.id" />
            </el-select>
            <el-input
              v-model="dimensionFilters.batchNo"
              placeholder="批次（可空）"
              clearable
              style="width: 150px"
              @change="loadDimensions"
            />
            <el-select
              v-model="dimensionFilters.status"
              placeholder="全部状态"
              clearable
              style="width: 130px"
              @change="loadDimensions"
            >
              <el-option label="可用" value="AVAILABLE" />
              <el-option label="预占" value="RESERVED" />
              <el-option label="冻结" value="FROZEN" />
            </el-select>
            <el-button @click="loadDimensions">刷新</el-button>
            <span v-if="dimensionError" class="error-text">{{ dimensionError }}</span>
          </div>
          <div class="dimension-totals">
            <el-tag type="info">实存合计 {{ dimensionTotals.onHandQty }}</el-tag>
            <el-tag type="success">可用 {{ dimensionTotals.byStatus.AVAILABLE ?? 0 }}</el-tag>
            <el-tag type="warning">预占 {{ dimensionTotals.byStatus.RESERVED ?? 0 }}</el-tag>
            <el-tag type="danger">冻结 {{ dimensionTotals.byStatus.FROZEN ?? 0 }}</el-tag>
            <span class="text-muted">对任一仓库 × SKU × 批次：可用 + 预占 + 冻结 == 实存</span>
          </div>
          <el-table v-loading="dimensionLoading" :data="dimensions" border stripe>
            <el-table-column label="仓库" min-width="120">
              <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
            </el-table-column>
            <el-table-column label="SKU" min-width="200">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="批次" width="130">
              <template #default="{ row }">{{ row.batchNo || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  :type="row.stockStatus === 'AVAILABLE' ? 'success' : row.stockStatus === 'RESERVED' ? 'warning' : 'danger'"
                  size="small"
                >
                  {{ stockStatusText(row.stockStatus) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="权属" width="100">
              <template #default="{ row }">{{ row.ownership === 'SELF' ? '自有' : row.ownership }}</template>
            </el-table-column>
            <el-table-column label="数量" prop="qty" width="100" />
          </el-table>
          <div v-if="!dimensionLoading && dimensions.length === 0" class="empty-hint">
            没有符合条件的结存
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ─────────── 预警 ─────────── -->
      <el-tab-pane label="预警" name="alert">
        <el-card shadow="never">
          <div class="filters">
            <el-select
              v-model="alertFilters.warehouseId"
              placeholder="全部仓库"
              clearable
              style="width: 160px"
              @change="loadAlerts"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-select
              v-model="alertFilters.type"
              placeholder="全部类型"
              clearable
              style="width: 150px"
              @change="loadAlerts"
            >
              <el-option label="安全库存" value="SAFETY" />
              <el-option label="滞销" value="SLOW_MOVING" />
              <el-option label="临期" value="EXPIRING" />
            </el-select>
            <el-button @click="loadAlerts">刷新</el-button>
            <span class="text-muted">临期/滞销边界由服务端时钟计算（默认临期 30 天、滞销 90 天）</span>
            <span v-if="alertError" class="error-text">{{ alertError }}</span>
          </div>
          <el-table v-loading="alertLoading" :data="alerts" border stripe>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag :type="alertTypeTag(row.type)" size="small">{{ alertTypeText(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="SKU" min-width="200">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="仓库" min-width="120">
              <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
            </el-table-column>
            <el-table-column label="批次" width="120">
              <template #default="{ row }">{{ row.batchNo || '-' }}</template>
            </el-table-column>
            <el-table-column label="实存" prop="onHandQty" width="90" />
            <el-table-column label="可用" prop="availableQty" width="90" />
            <el-table-column label="阈值" width="110">
              <template #default="{ row }">
                {{ row.type === 'SAFETY' ? row.threshold : `${row.threshold} 天` }}
              </template>
            </el-table-column>
            <el-table-column label="效期 / 最后出库" min-width="170">
              <template #default="{ row }">
                {{ row.expiryDate || dateTimeText(row.lastMovementAt) }}
              </template>
            </el-table-column>
            <el-table-column label="仓库优先级" prop="warehousePriority" width="110" />
          </el-table>
          <div v-if="!alertLoading && alerts.length === 0" class="empty-hint">当前没有预警</div>
        </el-card>
      </el-tab-pane>

      <!-- ─────────── 补货建议 ─────────── -->
      <el-tab-pane label="补货建议" name="replenishment">
        <el-card shadow="never">
          <div class="filters">
            <el-select
              v-model="replenishmentWarehouseId"
              placeholder="全部仓库"
              clearable
              style="width: 160px"
              @change="loadReplenishment"
            >
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
            <el-button @click="loadReplenishment">刷新</el-button>
            <span class="text-muted">
              可用量低于安全库存时补到上限；已有未到货进货单的 SKU 不会重复建议。
            </span>
            <span v-if="replenishmentError" class="error-text">{{ replenishmentError }}</span>
          </div>
          <el-table v-loading="replenishmentLoading" :data="replenishments" border stripe>
            <el-table-column label="SKU" min-width="220">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column label="仓库" min-width="130">
              <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
            </el-table-column>
            <el-table-column label="可用量" prop="availableQty" width="100" />
            <el-table-column label="安全下限" prop="minLevel" width="110" />
            <el-table-column label="补货上限" prop="maxLevel" width="110" />
            <el-table-column label="建议补货" width="120">
              <template #default="{ row }">
                <el-tag type="primary" size="small">{{ row.suggestedQty }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="仓库优先级" prop="warehousePriority" width="110" />
          </el-table>
          <div v-if="!replenishmentLoading && replenishments.length === 0" class="empty-hint">
            当前没有补货建议
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ─────────── 新建盘点 ─────────── -->
    <el-dialog v-model="stocktakeDialog" title="新建盘点单" width="820px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="仓库" required>
          <el-select v-model="stocktakeForm.warehouseId" placeholder="选择盘点仓库" style="width: 100%">
            <el-option
              v-for="w in warehouses.filter((x) => x.enabled)"
              :key="w.id"
              :label="w.name"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="stocktakeForm.remark" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="明细" required>
          <div class="line-editor">
            <div v-for="(line, index) in stocktakeForm.lines" :key="index" class="line-row">
              <el-select v-model="line.skuId" placeholder="选择 SKU" filterable style="flex: 1">
                <el-option
                  v-for="s in skus"
                  :key="s.id"
                  :label="skuText(s.id)"
                  :value="s.id"
                  :disabled="!s.enabled"
                />
              </el-select>
              <el-input v-model="line.batchNo" placeholder="批次（可空）" style="width: 140px" />
              <el-input-number v-model="line.countedQty" :min="0" :precision="0" style="width: 130px" />
              <el-button
                link
                type="danger"
                :disabled="stocktakeForm.lines.length === 1"
                @click="removeStocktakeLine(index)"
              >
                删除
              </el-button>
            </div>
            <el-button link type="primary" @click="addStocktakeLine">+ 添加明细</el-button>
          </div>
        </el-form-item>
      </el-form>
      <div class="dialog-hint">
        账面量在提交时快照；之后库存再变动不会改写这张盘点单。过账时只对有差异的行生成调整流水。
      </div>
      <template #footer>
        <el-button @click="stocktakeDialog = false">取消</el-button>
        <el-button type="primary" :loading="stocktakeSubmitting" @click="submitStocktake">
          创建盘点单
        </el-button>
      </template>
    </el-dialog>

    <!-- ─────────── 新建调整 ─────────── -->
    <el-dialog v-model="adjustmentDialog" title="库存调整" width="620px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="SKU" required>
          <el-select v-model="adjustmentForm.skuId" placeholder="选择 SKU" filterable style="width: 100%">
            <el-option v-for="s in skus" :key="s.id" :label="skuText(s.id)" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select v-model="adjustmentForm.warehouseId" placeholder="选择仓库" style="width: 100%">
            <el-option
              v-for="w in warehouses.filter((x) => x.enabled)"
              :key="w.id"
              :label="w.name"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="批次">
          <el-input v-model="adjustmentForm.batchNo" placeholder="可空" maxlength="64" />
        </el-form-item>
        <el-form-item label="调整数量" required>
          <el-input-number v-model="adjustmentForm.changeQty" :precision="0" style="width: 200px" />
          <span class="inline-hint">正数=调增，负数=调减，不能为 0</span>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input
            v-model="adjustmentForm.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="例如：破损报废 / 期初建账 / 供应商补发"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustmentDialog = false">取消</el-button>
        <el-button type="primary" :loading="adjustmentSubmitting" @click="submitAdjustment">
          提交调整
        </el-button>
      </template>
    </el-dialog>

    <!-- ─────────── 新建冻结 ─────────── -->
    <el-dialog v-model="freezeDialog" title="冻结库存" width="600px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="SKU" required>
          <el-select v-model="freezeForm.skuId" placeholder="选择 SKU" filterable style="width: 100%">
            <el-option v-for="s in skus" :key="s.id" :label="skuText(s.id)" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select v-model="freezeForm.warehouseId" placeholder="选择仓库" style="width: 100%">
            <el-option
              v-for="w in warehouses.filter((x) => x.enabled)"
              :key="w.id"
              :label="w.name"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="批次">
          <el-input v-model="freezeForm.batchNo" placeholder="可空" maxlength="64" />
        </el-form-item>
        <el-form-item label="冻结数量" required>
          <el-input-number v-model="freezeForm.qty" :min="1" :precision="0" style="width: 200px" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="freezeForm.reason" maxlength="200" show-word-limit placeholder="例如：质量抽检" />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">冻结量立即从可用量中扣除，但实存不变；解冻或到期后自动归还。</div>
      <template #footer>
        <el-button @click="freezeDialog = false">取消</el-button>
        <el-button type="primary" :loading="freezeSubmitting" @click="submitFreeze">确认冻结</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stock-ops-page {
  display: flex;
  flex-direction: column;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.expand-lines {
  padding: 8px 24px 12px;
  background: #fafafa;
}
.line-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.line-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.dialog-hint {
  margin-top: 4px;
  padding: 8px 12px;
  background: #f4f4f5;
  color: #606266;
  font-size: 12px;
  border-radius: 4px;
}
.inline-hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.empty-hint {
  padding: 24px 0;
  text-align: center;
  color: #909399;
}
.dimension-totals {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
.error-text {
  color: #f56c6c;
  font-size: 13px;
}
</style>
