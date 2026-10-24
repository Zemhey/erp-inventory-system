<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  mdmApi,
  type Product,
  type Sku,
  type Warehouse,
} from '@/api/client'
import { shipmentApi, type Shipment, type ShipmentLine } from '@/api/salesV23'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 拣货 / 发货（FR-SAL-06）。
 *
 * ★ 三层数量：计划量（来自 ACTIVE 预占）→ 扫码量（不得超过计划）→ 实际出库（确认时才扣库存）。
 *   因此「扫码」只是作业记录，用户点「确认发货」才会真正扣减库存与消费预占。
 */

const session = useSessionStore()
const canPick = computed(() => session.hasPermission('sal:pick'))
const canShip = computed(() => session.hasPermission('sal:ship'))

interface ShipmentStatusMeta {
  label: string
  type: 'info' | 'primary' | 'success' | 'warning' | 'danger'
}
const SHIPMENT_STATUS: Record<string, ShipmentStatusMeta> = {
  DRAFT: { label: '待拣货', type: 'info' },
  PICKING: { label: '拣货中', type: 'warning' },
  PICKED: { label: '已拣齐', type: 'primary' },
  COMPLETED: { label: '已发货', type: 'success' },
  VOIDED: { label: '已作废', type: 'danger' },
}
function shipmentStatus(status: string): ShipmentStatusMeta {
  return SHIPMENT_STATUS[status] ?? { label: status, type: 'info' }
}

const shipments = ref<Shipment[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const orderFilter = ref<number | undefined>(undefined)
const loading = ref(false)
const listError = ref('')
const filtered = computed(() => statusFilter.value !== '' || orderFilter.value !== undefined)

const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])
const warehouseNames = computed(() => new Map(warehouses.value.map((w) => [w.id, w.name])))
const skuNames = computed(() => {
  const productNames = new Map(products.value.map((p) => [p.id, p.name]))
  const map = new Map<number, string>()
  for (const s of skus.value) {
    map.set(s.id, `${productNames.get(s.productId) ?? `商品#${s.productId}`} · ${s.color}-${s.size}`)
  }
  return map
})
function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}
function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

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

async function loadShipments() {
  loading.value = true
  listError.value = ''
  try {
    const data = await shipmentApi.list({
      status: statusFilter.value || undefined,
      salesOrderId: orderFilter.value,
      page: page.value,
      size: pageSize.value,
    })
    shipments.value = data.items
    total.value = data.total
  } catch (error) {
    shipments.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadShipments()
}
function resetFilters() {
  statusFilter.value = ''
  orderFilter.value = undefined
  search()
}
function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  loadShipments()
}

function plannedQty(shipment: Shipment): number {
  return shipment.lines.reduce((sum, l) => sum + l.plannedQty, 0)
}
function pickedQty(shipment: Shipment): number {
  return shipment.lines.reduce((sum, l) => sum + l.pickedQty, 0)
}

// ─────────────────────────── 生成计划 ───────────────────────────

const planDialog = ref(false)
const planOrderId = ref<number | undefined>(undefined)
const planLoading = ref(false)
const planError = ref('')

function openPlan() {
  planOrderId.value = undefined
  planError.value = ''
  planDialog.value = true
}

async function createPlan() {
  if (!planOrderId.value) {
    planError.value = '请输入已审核的出货单 ID'
    return
  }
  planLoading.value = true
  planError.value = ''
  try {
    const plan = await shipmentApi.createPlan(planOrderId.value)
    ElMessage.success(
      `发货计划已生成：${plan.shipments.length} 张发货单（${plan.shipments.map((s) => warehouseText(s.warehouseId)).join('、')}）`,
    )
    planDialog.value = false
    page.value = 1
    await loadShipments()
  } catch (error) {
    planError.value = errorMessage(error)
  } finally {
    planLoading.value = false
  }
}

// ─────────────────────────── 扫码 ───────────────────────────

const scanDialog = ref(false)
const scanTarget = ref<Shipment | null>(null)
const scanForm = reactive({
  shipmentLineId: undefined as number | undefined,
  qty: 1,
  barcode: '',
})
const scanError = ref('')
const scanning = ref(false)

function remaining(line: ShipmentLine): number {
  return Math.max(0, line.plannedQty - line.pickedQty)
}
function openScan(row: Shipment) {
  scanTarget.value = row
  const first = row.lines.find((l) => remaining(l) > 0)
  scanForm.shipmentLineId = first?.id
  scanForm.qty = first ? remaining(first) : 1
  scanForm.barcode = ''
  scanError.value = ''
  scanDialog.value = true
}
function onScanLineChange() {
  const line = scanTarget.value?.lines.find((l) => l.id === scanForm.shipmentLineId)
  if (line) scanForm.qty = remaining(line) || 1
}

async function doScan() {
  if (!scanTarget.value || scanForm.shipmentLineId == null) {
    scanError.value = '请选择拣货行'
    return
  }
  scanning.value = true
  scanError.value = ''
  try {
    const updated = await shipmentApi.scan(scanTarget.value.id, {
      shipmentLineId: scanForm.shipmentLineId,
      qty: scanForm.qty,
      barcode: scanForm.barcode.trim() || undefined,
    })
    scanTarget.value = updated
    ElMessage.success(
      updated.status === 'PICKED'
        ? `发货单 ${updated.shipmentNo} 已全部拣齐，可以确认发货`
        : `已扫码 ${pickedQty(updated)}/${plannedQty(updated)}`,
    )
    const next = updated.lines.find((l) => remaining(l) > 0)
    scanForm.shipmentLineId = next?.id
    scanForm.qty = next ? remaining(next) : 1
    scanForm.barcode = ''
    await loadShipments()
  } catch (error) {
    scanError.value = errorMessage(error)
  } finally {
    scanning.value = false
  }
}

// ─────────────────────────── 确认发货 ───────────────────────────

const confirmPending = ref<number | null>(null)
async function confirm(row: Shipment) {
  try {
    await ElMessageBox.confirm(
      `确认发货 ${row.shipmentNo}？将真实扣减 ${warehouseText(row.warehouseId)} 的库存并消费预占，不可撤销。`,
      '确认发货',
      { type: 'warning', confirmButtonText: '确认发货', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  confirmPending.value = row.id
  try {
    const done = await shipmentApi.confirm(row.id)
    ElMessage.success(`发货单 ${done.shipmentNo} 已发货`)
    await loadShipments()
  } catch {
    await loadShipments()
  } finally {
    confirmPending.value = null
  }
}

// ─────────────────────────── 详情 ───────────────────────────

const detailDrawer = ref(false)
const detail = ref<Shipment | null>(null)

onMounted(async () => {
  await Promise.all([loadDictionary(), loadShipments()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">拣货 / 发货</h2>
      <div>
        <el-button @click="loadShipments">刷新</el-button>
        <el-button v-if="canPick" type="primary" @click="openPlan">生成发货计划</el-button>
      </div>
    </div>
    <p class="page-hint">
      发货计划按订单的 ACTIVE 预占生成：<b>单仓足够只有一张发货单，跨仓不足才拆单</b>。
      拣货行扫码累计不得超过计划量；<b>确认发货</b>才真实扣库存并登记应收。
    </p>

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`发货单查询失败：${listError}`"
    >
      <el-button size="small" @click="loadShipments">重试</el-button>
    </el-alert>

    <PageTable
      :rows="shipments"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!listError"
      :can-create="canPick"
      create-text="生成第一个发货计划"
      empty-text="还没有发货单"
      empty-hint="先在「出货单」审核通过，再输入订单 ID 生成发货计划。"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
      @create="openPlan"
    >
      <template #filters>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable class="filter-select-sm" @change="search">
          <el-option v-for="(meta, key) in SHIPMENT_STATUS" :key="key" :label="meta.label" :value="key" />
        </el-select>
        <el-input-number v-model="orderFilter" :min="1" placeholder="出货单 ID" controls-position="right" class="filter-order" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <el-table-column prop="shipmentNo" label="发货单号" min-width="170" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="shipmentStatus(row.status).type" size="small" disable-transitions>
            {{ shipmentStatus(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="出货单" width="100">
        <template #default="{ row }">#{{ row.salesOrderId }}</template>
      </el-table-column>
      <el-table-column label="仓库" min-width="130">
        <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column label="拣货进度" min-width="150">
        <template #default="{ row }">
          <span :class="{ 'text-muted': pickedQty(row) >= plannedQty(row) }">
            {{ pickedQty(row) }} / {{ plannedQty(row) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="明细行数" width="100" align="right">
        <template #default="{ row }">
          <span class="text-muted">{{ row.lines.length }} 行</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="detail = row; detailDrawer = true">详情</el-button>
          <el-button v-if="canPick && (row.status === 'DRAFT' || row.status === 'PICKING')" link type="primary" @click="openScan(row)">
            扫码
          </el-button>
          <el-button
            v-if="canShip && row.status === 'PICKED'"
            link
            type="success"
            :loading="confirmPending === row.id"
            @click="confirm(row)"
          >
            确认发货
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <!-- 生成计划 -->
    <el-dialog v-model="planDialog" title="生成发货计划" width="520px" :close-on-click-modal="false">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="mb-16"
        title="计划来自订单的 ACTIVE 预占"
        description="一张计划可能拆成多张按仓发货单；单仓足够时只有一张。同一幂等键重放返回首次计划。"
      />
      <el-form label-width="110px">
        <el-form-item label="出货单 ID">
          <el-input-number v-model="planOrderId" :min="1" controls-position="right" class="full" />
          <div class="field-hint">订单需已审核（APPROVED）或部分发货（PARTIAL）</div>
        </el-form-item>
      </el-form>
      <el-alert v-if="planError" type="error" show-icon :closable="false" :title="planError" />
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" :loading="planLoading" @click="createPlan">生成计划</el-button>
      </template>
    </el-dialog>

    <!-- 扫码 -->
    <el-dialog v-model="scanDialog" title="扫码拣货" width="620px" :close-on-click-modal="false">
      <template v-if="scanTarget">
        <div class="scan-head">
          {{ scanTarget.shipmentNo }} · {{ warehouseText(scanTarget.warehouseId) }} ·
          进度 {{ pickedQty(scanTarget) }}/{{ plannedQty(scanTarget) }}
        </div>
        <el-form label-width="90px">
          <el-form-item label="拣货行">
            <el-select v-model="scanForm.shipmentLineId" class="full" @change="onScanLineChange">
              <el-option
                v-for="l in scanTarget.lines"
                :key="l.id"
                :label="`行${l.lineNo} · ${skuText(l.skuId)} · 批次 ${l.batchNo || '无'} · 计划 ${l.plannedQty} / 已扫 ${l.pickedQty}`"
                :value="l.id"
                :disabled="remaining(l) <= 0"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="扫码数量">
            <el-input-number v-model="scanForm.qty" :min="1" controls-position="right" class="full" />
          </el-form-item>
          <el-form-item label="条码">
            <el-input v-model="scanForm.barcode" placeholder="可选：扫描枪输入，必须与该行 SKU 条码一致" clearable />
          </el-form-item>
        </el-form>
        <el-alert v-if="scanError" type="error" show-icon :closable="false" :title="scanError" />
      </template>
      <template #footer>
        <el-button @click="scanDialog = false">关闭</el-button>
        <el-button type="primary" :loading="scanning" @click="doScan">确认扫码</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailDrawer" size="60%">
      <template #header>
        <div class="drawer-title">{{ detail?.shipmentNo || '发货单详情' }}</div>
      </template>
      <template v-if="detail">
        <div class="detail-grid">
          <div><span class="dt">状态</span>{{ shipmentStatus(detail.status).label }}</div>
          <div><span class="dt">出货单</span>#{{ detail.salesOrderId }}</div>
          <div><span class="dt">仓库</span>{{ warehouseText(detail.warehouseId) }}</div>
          <div><span class="dt">进度</span>{{ pickedQty(detail) }} / {{ plannedQty(detail) }}</div>
        </div>
        <el-table :data="detail.lines" size="small" border>
          <el-table-column prop="lineNo" label="行号" width="70" />
          <el-table-column label="SKU" min-width="190">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column prop="batchNo" label="批次" width="110" />
          <el-table-column prop="plannedQty" label="计划量" width="90" align="right" />
          <el-table-column prop="pickedQty" label="已扫码" width="90" align="right" />
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-select-sm {
  width: 150px;
}
.filter-order {
  width: 160px;
}
.full {
  width: 100%;
}
.field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.scan-head {
  margin-bottom: 12px;
  font-size: 13px;
  color: #606266;
}
.drawer-title {
  font-size: 16px;
  font-weight: 600;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 24px;
  margin-bottom: 16px;
  font-size: 13px;
}
.dt {
  display: inline-block;
  min-width: 76px;
  color: #909399;
}
@media (max-width: 768px) {
  .filter-select-sm,
  .filter-order {
    width: 100%;
  }
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
