<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  mdmApi,
  purchaseReceiptApi,
  purchaseReturnApi,
  purApi,
  requisitionApi,
  fenToYuan,
  yuanToFen,
  type Product,
  type PurchaseOrder,
  type PurchaseReceipt,
  type PurchaseReceiptLine,
  type PurchaseRequisition,
  type PurchaseReturn,
  type Sku,
  type Supplier,
  type Warehouse,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import StatusTag from '@/components/StatusTag.vue'
import { statusMeta } from '@/utils/documentStatus'
import { useSessionStore } from '@/stores/session'

/**
 * 请购与到货质检（FR-PUR-04 / FR-PUR-05），附采购退货入口（FR-PUR-06）。
 *
 * 三个页签共享同一份主数据字典；每一块只做"展示 + 把用户输入原样交给后端"：
 * - 请购转单：数量可下调，但行不能丢；后端会再校验一次。
 * - 到货质检：默认合格量 = 到货量，操作员可下调；只有合格量入库存与应付。
 * - 采购退货：只让选原到货行与数量，金额由后端派生。
 */

const session = useSessionStore()
const canCreate = computed(() => session.hasPermission('pur:create'))
const canSubmit = computed(() => session.hasPermission('pur:submit'))
const canApprove = computed(() => session.hasPermission('pur:approve'))
const canReceive = computed(() => session.hasPermission('pur:receive'))
const canVoid = computed(() => session.hasPermission('pur:void'))

const activeTab = ref('requisitions')

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

// ─────────────────────────── 主数据字典 ───────────────────────────

const suppliers = ref<Supplier[]>([])
const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])

const supplierOptions = computed(() => suppliers.value.filter((s) => s.enabled))
const warehouseOptions = computed(() => warehouses.value.filter((w) => w.enabled))
const skuOptions = computed(() => skus.value.filter((s) => s.enabled))

const supplierNames = computed(() => {
  const map = new Map<number, string>()
  for (const s of suppliers.value) map.set(s.id, s.enabled ? s.name : `${s.name}（已停用）`)
  return map
})

const warehouseNames = computed(() => {
  const map = new Map<number, string>()
  for (const w of warehouses.value) map.set(w.id, w.enabled ? w.name : `${w.name}（已停用）`)
  return map
})

const skuNames = computed(() => {
  const productNames = new Map<number, string>(products.value.map((p) => [p.id, p.name]))
  const map = new Map<number, string>()
  for (const s of skus.value) {
    map.set(s.id, `${productNames.get(s.productId) ?? `商品#${s.productId}`} · ${s.color}-${s.size}`)
  }
  return map
})

function supplierText(id: number): string {
  return supplierNames.value.get(id) ?? `供应商#${id}`
}

function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}

function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

async function loadDictionary() {
  const [sup, wh, sku, prod] = await Promise.allSettled([
    mdmApi.listSuppliers({ page: 1, size: 200 }),
    mdmApi.listWarehouses(false),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (sup.status === 'fulfilled') suppliers.value = sup.value.items
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
}

// ─────────────────────────── 请购单 ───────────────────────────

const reqRows = ref<PurchaseRequisition[]>([])
const reqTotal = ref(0)
const reqPage = ref(1)
const reqPageSize = ref(20)
const reqStatus = ref('')
const reqSupplier = ref<number | undefined>(undefined)
const reqLoading = ref(false)
const reqError = ref('')
const reqFiltered = computed(
  () => reqStatus.value !== '' || reqSupplier.value !== undefined,
)

async function loadRequisitions() {
  reqLoading.value = true
  reqError.value = ''
  try {
    const data = await requisitionApi.list({
      status: reqStatus.value || undefined,
      supplierId: reqSupplier.value,
      page: reqPage.value,
      size: reqPageSize.value,
    })
    reqRows.value = data.items
    reqTotal.value = data.total
  } catch (error) {
    reqRows.value = []
    reqTotal.value = 0
    reqError.value = errorMessage(error)
  } finally {
    reqLoading.value = false
  }
}

function searchRequisitions() {
  reqPage.value = 1
  loadRequisitions()
}

function resetReqFilters() {
  reqStatus.value = ''
  reqSupplier.value = undefined
  searchRequisitions()
}

function onReqPageChange(nextPage: number, nextSize: number) {
  reqPage.value = nextPage
  reqPageSize.value = nextSize
  loadRequisitions()
}

interface RequisitionLineInput {
  skuId: number | undefined
  qty: number
  unitPriceYuan: string
}

const reqCreateOpen = ref(false)
const reqCreating = ref(false)
const reqForm = reactive({
  supplierId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  bizDate: today(),
  remark: '',
  lines: [] as RequisitionLineInput[],
})

function openCreateRequisition() {
  reqForm.supplierId = supplierOptions.value[0]?.id
  reqForm.warehouseId = warehouseOptions.value[0]?.id
  reqForm.bizDate = today()
  reqForm.remark = ''
  reqForm.lines = [{ skuId: skuOptions.value[0]?.id, qty: 1, unitPriceYuan: '' }]
  reqCreateOpen.value = true
}

function addReqLine() {
  reqForm.lines.push({ skuId: skuOptions.value[0]?.id, qty: 1, unitPriceYuan: '' })
}

function removeReqLine(index: number) {
  reqForm.lines.splice(index, 1)
}

async function submitRequisition() {
  if (!reqForm.supplierId || !reqForm.warehouseId) {
    ElMessage.warning('请选择供应商与仓库')
    return
  }
  if (reqForm.lines.length === 0) {
    ElMessage.warning('至少需要一行明细')
    return
  }
  for (const line of reqForm.lines) {
    if (!line.skuId || line.qty <= 0) {
      ElMessage.warning('每行都必须选择 SKU 且数量为正')
      return
    }
  }
  reqCreating.value = true
  try {
    const created = await requisitionApi.create({
      supplierId: reqForm.supplierId,
      warehouseId: reqForm.warehouseId,
      bizDate: reqForm.bizDate || undefined,
      remark: reqForm.remark || undefined,
      lines: reqForm.lines.map((l) => ({
        skuId: l.skuId as number,
        qty: l.qty,
        unitPriceAmount: yuanToFen(l.unitPriceYuan),
      })),
    })
    ElMessage.success(`请购单已创建：${created.orderNo}`)
    reqCreateOpen.value = false
    await loadRequisitions()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    reqCreating.value = false
  }
}

async function runRequisitionAction(row: PurchaseRequisition, action: 'submit' | 'approve') {
  const label = action === 'submit' ? '提交' : '审核'
  try {
    await ElMessageBox.confirm(`确定${label}请购单 ${row.orderNo} 吗？`, `${label}请购单`, {
      type: 'warning',
      confirmButtonText: label,
    })
  } catch {
    return
  }
  try {
    const updated =
      action === 'submit' ? await requisitionApi.submit(row.id) : await requisitionApi.approve(row.id)
    ElMessage.success(`${label}成功：${updated.orderNo} · ${statusMeta(updated.status).label}`)
    await loadRequisitions()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

interface ConvertLineInput {
  lineId: number
  lineNo: number
  skuId: number
  requestedQty: number
  qty: number
}

const convertOpen = ref(false)
const converting = ref(false)
const convertRow = ref<PurchaseRequisition | null>(null)
const convertForm = reactive({ bizDate: today(), remark: '', lines: [] as ConvertLineInput[] })

function openConvert(row: PurchaseRequisition) {
  convertRow.value = row
  convertForm.bizDate = today()
  convertForm.remark = `由 ${row.orderNo} 转单`
  convertForm.lines = row.lines.map((l) => ({
    lineId: l.id,
    lineNo: l.lineNo,
    skuId: l.skuId,
    requestedQty: l.qty,
    qty: l.qty,
  }))
  convertOpen.value = true
}

async function submitConvert() {
  const row = convertRow.value
  if (!row) return
  for (const line of convertForm.lines) {
    if (line.qty <= 0 || line.qty > line.requestedQty) {
      ElMessage.warning(`第 ${line.lineNo} 行数量必须在 1..${line.requestedQty} 之间`)
      return
    }
  }
  converting.value = true
  try {
    const order = await requisitionApi.convert(row.id, {
      bizDate: convertForm.bizDate || undefined,
      remark: convertForm.remark || undefined,
      lines: convertForm.lines.map((l) => ({ lineId: l.lineId, qty: l.qty })),
    })
    ElMessage.success(`转单成功：${order.orderNo}`)
    convertOpen.value = false
    await Promise.all([loadRequisitions(), loadPurchaseOrders()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    converting.value = false
  }
}

// ─────────────────────────── 到货质检 ───────────────────────────

const poRows = ref<PurchaseOrder[]>([])
const poTotal = ref(0)
const poPage = ref(1)
const poPageSize = ref(20)
const poStatus = ref('')
const poLoading = ref(false)
const poError = ref('')

async function loadPurchaseOrders() {
  poLoading.value = true
  poError.value = ''
  try {
    const data = await purApi.list({
      status: poStatus.value || undefined,
      page: poPage.value,
      size: poPageSize.value,
    })
    poRows.value = data.items
    poTotal.value = data.total
  } catch (error) {
    poRows.value = []
    poTotal.value = 0
    poError.value = errorMessage(error)
  } finally {
    poLoading.value = false
  }
}

function searchPurchaseOrders() {
  poPage.value = 1
  loadPurchaseOrders()
}

function onPoPageChange(nextPage: number, nextSize: number) {
  poPage.value = nextPage
  poPageSize.value = nextSize
  loadPurchaseOrders()
}

function receivedQty(row: PurchaseOrder): number {
  return (row.lines ?? []).reduce((sum, l) => sum + (l.receivedQty ?? 0), 0)
}

const QC_META: Record<string, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  NONE: { label: '未质检', type: 'info' },
  PASSED: { label: '全部合格', type: 'success' },
  PARTIAL: { label: '部分合格', type: 'warning' },
  FAILED: { label: '全部不合格', type: 'danger' },
}

function qcMeta(status: string | undefined) {
  return QC_META[status ?? 'NONE'] ?? { label: status ?? '-', type: 'info' as const }
}

interface ReceiveLineInput {
  lineId: number
  lineNo: number
  skuId: number
  openQty: number
  qty: number
  qcQty: number
  batchNo: string
  qcNote: string
}

const receiveOpen = ref(false)
const receiveLoading = ref(false)
const receiving = ref(false)
const receiveRow = ref<PurchaseOrder | null>(null)
const receiveLines = ref<ReceiveLineInput[]>([])

async function openReceive(row: PurchaseOrder) {
  receiveRow.value = row
  receiveOpen.value = true
  receiveLoading.value = true
  receiveLines.value = []
  try {
    const detail = await purApi.get(row.id)
    receiveRow.value = detail
    receiveLines.value = (detail.lines ?? [])
      .filter((l) => (l.openQty ?? 0) > 0)
      .map((l) => ({
        lineId: l.id,
        lineNo: l.lineNo,
        skuId: l.skuId,
        openQty: l.openQty ?? 0,
        qty: l.openQty ?? 0,
        qcQty: l.openQty ?? 0,
        batchNo: '',
        qcNote: '',
      }))
    if (receiveLines.value.length === 0) {
      ElMessage.info('该进货单没有待收货数量')
      receiveOpen.value = false
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
    receiveOpen.value = false
  } finally {
    receiveLoading.value = false
  }
}

async function submitReceive() {
  const row = receiveRow.value
  if (!row) return
  const lines = receiveLines.value.filter((l) => l.qty > 0)
  if (lines.length === 0) {
    ElMessage.warning('至少填写一行本次到货数量')
    return
  }
  for (const l of lines) {
    if (l.qty < 0 || l.qty > l.openQty) {
      ElMessage.warning(`第 ${l.lineNo} 行到货数量必须在 1..${l.openQty} 之间`)
      return
    }
    if (l.qcQty < 0 || l.qcQty > l.qty) {
      ElMessage.warning(`第 ${l.lineNo} 行合格数量必须在 0..到货量之间`)
      return
    }
  }
  receiving.value = true
  try {
    const updated = await purApi.receive(row.id, {
      lines: lines.map((l) => ({
        lineId: l.lineId,
        qty: l.qty,
        qcQty: l.qcQty,
        batchNo: l.batchNo || undefined,
        qcNote: l.qcNote || undefined,
      })),
    })
    ElMessage.success(
      `到货确认完成：${updated.orderNo} · ${statusMeta(updated.status).label}`,
    )
    receiveOpen.value = false
    await Promise.all([loadPurchaseOrders(), loadReturns()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    receiving.value = false
  }
}

const receiptsOpen = ref(false)
const receiptsLoading = ref(false)
const receipts = ref<PurchaseReceipt[]>([])

async function openReceipts(row: PurchaseOrder) {
  receiptsOpen.value = true
  receiptsLoading.value = true
  receipts.value = []
  try {
    receipts.value = await purchaseReceiptApi.listByOrder(row.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    receiptsLoading.value = false
  }
}

// ─────────────────────────── 采购退货 ───────────────────────────

const returnRows = ref<PurchaseReturn[]>([])
const returnTotal = ref(0)
const returnPage = ref(1)
const returnPageSize = ref(20)
const returnStatus = ref('')
const returnLoading = ref(false)
const returnError = ref('')

async function loadReturns() {
  returnLoading.value = true
  returnError.value = ''
  try {
    const data = await purchaseReturnApi.list({
      status: returnStatus.value || undefined,
      page: returnPage.value,
      size: returnPageSize.value,
    })
    returnRows.value = data.items
    returnTotal.value = data.total
  } catch (error) {
    returnRows.value = []
    returnTotal.value = 0
    returnError.value = errorMessage(error)
  } finally {
    returnLoading.value = false
  }
}

function searchReturns() {
  returnPage.value = 1
  loadReturns()
}

function onReturnPageChange(nextPage: number, nextSize: number) {
  returnPage.value = nextPage
  returnPageSize.value = nextSize
  loadReturns()
}

interface ReturnLineInput {
  receiptLine: PurchaseReceiptLine
  qty: number
}

const returnCreateOpen = ref(false)
const returnCreating = ref(false)
const returnForm = reactive({
  purchaseOrderId: undefined as number | undefined,
  bizDate: today(),
  remark: '',
  lines: [] as ReturnLineInput[],
})

function openCreateReturn() {
  returnForm.purchaseOrderId = undefined
  returnForm.bizDate = today()
  returnForm.remark = ''
  returnForm.lines = []
  returnCreateOpen.value = true
}

async function onReturnOrderChange(orderId: number | undefined) {
  returnForm.lines = []
  if (!orderId) return
  try {
    const list = await purchaseReceiptApi.listByOrder(orderId)
    const lines: ReturnLineInput[] = []
    for (const receipt of list) {
      for (const line of receipt.lines) {
        if (line.qcQty > 0) {
          lines.push({ receiptLine: line, qty: 0 })
        }
      }
    }
    returnForm.lines = lines
    if (lines.length === 0) {
      ElMessage.info('该进货单没有可退的合格到货行')
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function submitReturn() {
  if (!returnForm.purchaseOrderId) {
    ElMessage.warning('请选择原进货单')
    return
  }
  const lines = returnForm.lines.filter((l) => l.qty > 0)
  if (lines.length === 0) {
    ElMessage.warning('至少填写一行退货数量')
    return
  }
  for (const l of lines) {
    if (l.qty > l.receiptLine.qcQty) {
      ElMessage.warning(`到货行 ${l.receiptLine.id} 最多可退 ${l.receiptLine.qcQty}`)
      return
    }
  }
  returnCreating.value = true
  try {
    const created = await purchaseReturnApi.create({
      purchaseOrderId: returnForm.purchaseOrderId,
      bizDate: returnForm.bizDate || undefined,
      remark: returnForm.remark || undefined,
      lines: lines.map((l) => ({ receiptLineId: l.receiptLine.id, qty: l.qty })),
    })
    ElMessage.success(`退货单已创建：${created.returnNo}`)
    returnCreateOpen.value = false
    await loadReturns()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    returnCreating.value = false
  }
}

async function runReturnAction(row: PurchaseReturn, action: 'approve' | 'void') {
  const label = action === 'approve' ? '执行退货' : '作废退货单'
  try {
    await ElMessageBox.confirm(
      action === 'approve'
        ? `执行后将扣减库存并冲减应付，确定执行 ${row.returnNo} 吗？`
        : `确定作废退货单 ${row.returnNo} 吗？`,
      label,
      { type: 'warning', confirmButtonText: label },
    )
  } catch {
    return
  }
  try {
    const updated =
      action === 'approve'
        ? await purchaseReturnApi.approve(row.id)
        : await purchaseReturnApi.voidReturn(row.id)
    ElMessage.success(`${label}成功：${updated.returnNo} · ${updated.status}`)
    await loadReturns()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function returnStatusText(status: string): string {
  return status === 'DRAFT' ? '草稿' : status === 'COMPLETED' ? '已执行' : '已作废'
}

onMounted(() => {
  void loadDictionary()
  void loadRequisitions()
  void loadPurchaseOrders()
  void loadReturns()
})
</script>

<template>
  <div class="purchase-loop-view">
    <el-tabs v-model="activeTab" type="border-card">
      <!-- ═══════════════════════ 请购单 ═══════════════════════ -->
      <el-tab-pane label="请购单" name="requisitions">
        <PageTable
          :rows="reqRows"
          :loading="reqLoading"
          :total="reqTotal"
          :page="reqPage"
          :page-size="reqPageSize"
          :filtered="reqFiltered"
          :load-failed="!!reqError"
          row-key="id"
          @update:page="reqPage = $event"
          @update:page-size="reqPageSize = $event"
          @change="onReqPageChange"
        >
          <template #filters>
            <el-select
              v-model="reqStatus"
              placeholder="全部状态"
              clearable
              style="width: 150px"
              @change="searchRequisitions"
            >
              <el-option label="草稿" value="DRAFT" />
              <el-option label="已提交" value="SUBMITTED" />
              <el-option label="已审核" value="APPROVED" />
              <el-option label="已转化" value="COMPLETED" />
            </el-select>
            <el-select
              v-model="reqSupplier"
              placeholder="全部供应商"
              clearable
              filterable
              style="width: 220px"
              @change="searchRequisitions"
            >
              <el-option
                v-for="s in suppliers"
                :key="s.id"
                :label="s.enabled ? s.name : `${s.name}（已停用）`"
                :value="s.id"
              />
            </el-select>
            <el-button @click="resetReqFilters">重置</el-button>
            <el-button v-if="canCreate" type="primary" @click="openCreateRequisition">
              新建请购单
            </el-button>
          </template>

          <template #header>
            <span>请购单（{{ reqTotal }}）</span>
            <span class="text-muted">转化后只读；数量可下调，行不能丢</span>
          </template>

          <el-table-column prop="orderNo" label="请购单号" min-width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><StatusTag :status="row.status" size="small" /></template>
          </el-table-column>
          <el-table-column label="供应商" min-width="160">
            <template #default="{ row }">{{ supplierText(row.supplierId) }}</template>
          </el-table-column>
          <el-table-column label="仓库" min-width="120">
            <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
          </el-table-column>
          <el-table-column prop="bizDate" label="业务日期" width="110" />
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ row.totalQty }}</template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">{{ fenToYuan(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column label="关联订单" width="150">
            <template #default="{ row }">#{{ row.convertedOrderId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canSubmit && row.status === 'DRAFT'"
                link
                type="primary"
                @click="runRequisitionAction(row, 'submit')"
              >
                提交
              </el-button>
              <el-button
                v-if="canApprove && row.status === 'SUBMITTED'"
                link
                type="primary"
                @click="runRequisitionAction(row, 'approve')"
              >
                审核
              </el-button>
              <el-button
                v-if="canApprove && row.status === 'APPROVED'"
                link
                type="success"
                @click="openConvert(row)"
              >
                转订单
              </el-button>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ═══════════════════════ 到货质检 ═══════════════════════ -->
      <el-tab-pane label="到货质检" name="qc">
        <PageTable
          :rows="poRows"
          :loading="poLoading"
          :total="poTotal"
          :page="poPage"
          :page-size="poPageSize"
          :load-failed="!!poError"
          row-key="id"
          @update:page="poPage = $event"
          @update:page-size="poPageSize = $event"
          @change="onPoPageChange"
        >
          <template #filters>
            <el-select
              v-model="poStatus"
              placeholder="全部状态"
              clearable
              style="width: 150px"
              @change="searchPurchaseOrders"
            >
              <el-option label="已审核" value="APPROVED" />
              <el-option label="部分到货" value="PARTIAL" />
              <el-option label="已完成" value="COMPLETED" />
            </el-select>
            <el-button @click="searchPurchaseOrders">刷新</el-button>
          </template>

          <template #header>
            <span>进货单到货（{{ poTotal }}）</span>
            <span class="text-muted">合格量才入库存与应付；不合格量仅记录</span>
          </template>

          <el-table-column prop="orderNo" label="进货单号" min-width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><StatusTag :status="row.status" size="small" /></template>
          </el-table-column>
          <el-table-column label="供应商" min-width="160">
            <template #default="{ row }">{{ supplierText(row.supplierId) }}</template>
          </el-table-column>
          <el-table-column label="到货进度" width="140">
            <template #default="{ row }">
              {{ receivedQty(row) }} / {{ row.totalQty }}
            </template>
          </el-table-column>
          <el-table-column label="明细质检状态" min-width="220">
            <template #default="{ row }">
              <el-tag
                v-for="line in row.lines"
                :key="line.id"
                :type="qcMeta(line.qcStatus).type"
                size="small"
                class="line-qc-tag"
              >
                第{{ line.lineNo }}行 {{ qcMeta(line.qcStatus).label }}（{{ line.qcQty ?? 0 }}/{{ line.receivedQty ?? 0 }}）
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canReceive && (row.status === 'APPROVED' || row.status === 'PARTIAL')"
                link
                type="primary"
                @click="openReceive(row)"
              >
                到货质检
              </el-button>
              <el-button link type="info" @click="openReceipts(row)">到货记录</el-button>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- ═══════════════════════ 采购退货 ═══════════════════════ -->
      <el-tab-pane label="采购退货" name="returns">
        <PageTable
          :rows="returnRows"
          :loading="returnLoading"
          :total="returnTotal"
          :page="returnPage"
          :page-size="returnPageSize"
          :load-failed="!!returnError"
          row-key="id"
          @update:page="returnPage = $event"
          @update:page-size="returnPageSize = $event"
          @change="onReturnPageChange"
        >
          <template #filters>
            <el-select
              v-model="returnStatus"
              placeholder="全部状态"
              clearable
              style="width: 150px"
              @change="searchReturns"
            >
              <el-option label="草稿" value="DRAFT" />
              <el-option label="已执行" value="COMPLETED" />
              <el-option label="已作废" value="VOIDED" />
            </el-select>
            <el-button @click="searchReturns">刷新</el-button>
            <el-button v-if="canCreate" type="primary" @click="openCreateReturn">
              新建退货单
            </el-button>
          </template>

          <template #header>
            <span>采购退货（{{ returnTotal }}）</span>
            <span class="text-muted">执行时按原到货批次扣库存并冲减应付</span>
          </template>

          <el-table-column prop="returnNo" label="退货单号" min-width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag
                :type="row.status === 'COMPLETED' ? 'success' : row.status === 'VOIDED' ? 'info' : 'warning'"
                size="small"
              >
                {{ returnStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原进货单" width="110">
            <template #default="{ row }">#{{ row.purchaseOrderId }}</template>
          </el-table-column>
          <el-table-column prop="bizDate" label="业务日期" width="110" />
          <el-table-column label="数量" width="90" align="right">
            <template #default="{ row }">{{ row.totalQty }}</template>
          </el-table-column>
          <el-table-column label="金额" width="110" align="right">
            <template #default="{ row }">{{ fenToYuan(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column label="明细" min-width="240">
            <template #default="{ row }">
              <span v-for="(line, idx) in row.lines" :key="line.receiptLineId" class="text-muted">
                {{ idx > 0 ? '；' : '' }}{{ skuText(line.skuId) }} × {{ line.qty }}（批次 {{ line.batchNo || '无' }}）
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canApprove && row.status === 'DRAFT'"
                link
                type="success"
                @click="runReturnAction(row, 'approve')"
              >
                执行
              </el-button>
              <el-button
                v-if="canVoid && row.status === 'DRAFT'"
                link
                type="danger"
                @click="runReturnAction(row, 'void')"
              >
                作废
              </el-button>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>
    </el-tabs>

    <!-- ═══════════════════════ 新建请购单 ═══════════════════════ -->
    <el-dialog v-model="reqCreateOpen" title="新建请购单" width="760px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="供应商">
          <el-select v-model="reqForm.supplierId" filterable style="width: 100%">
            <el-option
              v-for="s in supplierOptions"
              :key="s.id"
              :label="s.name"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库">
          <el-select v-model="reqForm.warehouseId" style="width: 100%">
            <el-option
              v-for="w in warehouseOptions"
              :key="w.id"
              :label="w.name"
              :value="w.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker v-model="reqForm.bizDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reqForm.remark" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="明细">
          <div class="line-editor">
            <div v-for="(line, idx) in reqForm.lines" :key="idx" class="line-row">
              <el-select v-model="line.skuId" filterable placeholder="SKU" style="flex: 2">
                <el-option
                  v-for="s in skuOptions"
                  :key="s.id"
                  :label="skuText(s.id)"
                  :value="s.id"
                />
              </el-select>
              <el-input-number v-model="line.qty" :min="1" :step="1" controls-position="right" />
              <el-input v-model="line.unitPriceYuan" placeholder="单价（元）" style="flex: 1">
                <template #append>元</template>
              </el-input>
              <el-button link type="danger" @click="removeReqLine(idx)">删除</el-button>
            </div>
            <el-button link type="primary" @click="addReqLine">+ 增加一行</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reqCreateOpen = false">取消</el-button>
        <el-button type="primary" :loading="reqCreating" @click="submitRequisition">
          创建
        </el-button>
      </template>
    </el-dialog>

    <!-- ═══════════════════════ 转订单 ═══════════════════════ -->
    <el-dialog v-model="convertOpen" title="请购转订单" width="680px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        class="mb-12"
        title="必须保留全部明细行；每行数量可在 1..申请数量之间下调，单价由请购单派生。"
      />
      <el-form label-width="90px">
        <el-form-item label="业务日期">
          <el-date-picker v-model="convertForm.bizDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="convertForm.remark" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <el-table :data="convertForm.lines" size="small" border>
        <el-table-column prop="lineNo" label="行号" width="70" />
        <el-table-column label="SKU" min-width="200">
          <template #default="{ row }">{{ skuText(row.skuId) }}</template>
        </el-table-column>
        <el-table-column label="申请数量" width="100" align="right">
          <template #default="{ row }">{{ row.requestedQty }}</template>
        </el-table-column>
        <el-table-column label="转单数量" width="170">
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="1"
              :max="row.requestedQty"
              controls-position="right"
            />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="convertOpen = false">取消</el-button>
        <el-button type="primary" :loading="converting" @click="submitConvert">
          生成进货单
        </el-button>
      </template>
    </el-dialog>

    <!-- ═══════════════════════ 到货质检 ═══════════════════════ -->
    <el-dialog v-model="receiveOpen" title="到货质检" width="960px" destroy-on-close>
      <div v-loading="receiveLoading">
        <el-alert
          type="warning"
          :closable="false"
          class="mb-12"
          title="合格量才会入库存并生成应付；不合格量只记录在到货单，不产生库存与应付。"
        />
        <el-table :data="receiveLines" size="small" border>
          <el-table-column prop="lineNo" label="行号" width="65" />
          <el-table-column label="SKU" min-width="180">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column label="待收" width="80" align="right">
            <template #default="{ row }">{{ row.openQty }}</template>
          </el-table-column>
          <el-table-column label="本次到货" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0" :max="row.openQty" controls-position="right" />
            </template>
          </el-table-column>
          <el-table-column label="合格数量" width="150">
            <template #default="{ row }">
              <el-input-number v-model="row.qcQty" :min="0" :max="row.qty" controls-position="right" />
            </template>
          </el-table-column>
          <el-table-column label="批次" width="140">
            <template #default="{ row }">
              <el-input v-model="row.batchNo" maxlength="64" placeholder="可空" />
            </template>
          </el-table-column>
          <el-table-column label="质检备注" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.qcNote" maxlength="500" placeholder="不合格原因等" />
            </template>
          </el-table-column>
        </el-table>
        <div class="text-muted mt-8">
          不合格数量 = 本次到货 − 合格数量；该部分不会写入库存流水，也不会生成应付。
        </div>
      </div>
      <template #footer>
        <el-button @click="receiveOpen = false">取消</el-button>
        <el-button type="primary" :loading="receiving" @click="submitReceive">
          确认到货
        </el-button>
      </template>
    </el-dialog>

    <!-- ═══════════════════════ 到货记录 ═══════════════════════ -->
    <el-dialog v-model="receiptsOpen" title="到货质检记录" width="900px" destroy-on-close>
      <div v-loading="receiptsLoading">
        <el-empty v-if="receipts.length === 0" description="尚无到货记录" />
        <div v-for="receipt in receipts" :key="receipt.id" class="receipt-block">
          <div class="receipt-head">
            <strong>{{ receipt.receiptNo }}</strong>
            <span class="text-muted">
              {{ receipt.bizDate }} · 到货 {{ receipt.totalQty }} · 合格 {{ receipt.acceptedQty }} ·
              不合格 {{ receipt.rejectedQty }} · 合格金额 {{ fenToYuan(receipt.acceptedAmount) }} 元
            </span>
          </div>
          <el-table :data="receipt.lines" size="small" border>
            <el-table-column prop="lineNo" label="行号" width="60" />
            <el-table-column label="SKU" min-width="160">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column prop="batchNo" label="批次" width="120" />
            <el-table-column prop="receivedQty" label="到货" width="80" align="right" />
            <el-table-column prop="qcQty" label="合格" width="80" align="right" />
            <el-table-column prop="rejectedQty" label="不合格" width="80" align="right" />
            <el-table-column label="质检状态" width="110">
              <template #default="{ row }">
                <el-tag :type="qcMeta(row.qcStatus).type" size="small">
                  {{ qcMeta(row.qcStatus).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="qcNote" label="备注" min-width="140" />
          </el-table>
        </div>
      </div>
    </el-dialog>

    <!-- ═══════════════════════ 新建退货单 ═══════════════════════ -->
    <el-dialog v-model="returnCreateOpen" title="新建采购退货单" width="900px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="原进货单">
          <el-select
            v-model="returnForm.purchaseOrderId"
            filterable
            placeholder="选择已到货的进货单"
            style="width: 100%"
            @change="onReturnOrderChange"
          >
            <el-option
              v-for="o in poRows"
              :key="o.id"
              :label="`${o.orderNo}（${o.status}）`"
              :value="o.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker v-model="returnForm.bizDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="returnForm.remark" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <el-table :data="returnForm.lines" size="small" border>
        <el-table-column label="SKU" min-width="180">
          <template #default="{ row }">{{ skuText(row.receiptLine.skuId) }}</template>
        </el-table-column>
        <el-table-column prop="receiptLine.batchNo" label="原批次" width="120" />
        <el-table-column prop="receiptLine.qcQty" label="合格到货" width="100" align="right" />
        <el-table-column prop="receiptLine.rejectedQty" label="不合格" width="90" align="right" />
        <el-table-column label="退货数量" width="170">
          <template #default="{ row }">
            <el-input-number
              v-model="row.qty"
              :min="0"
              :max="row.receiptLine.qcQty"
              controls-position="right"
            />
          </template>
        </el-table-column>
      </el-table>
      <div class="text-muted mt-8">
        只能退合格到货中的数量；执行退货时会扣减原批次库存并冲减该到货单的应付。
      </div>
      <template #footer>
        <el-button @click="returnCreateOpen = false">取消</el-button>
        <el-button type="primary" :loading="returnCreating" @click="submitReturn">
          创建退货单
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.purchase-loop-view {
  min-height: 400px;
}

.line-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.line-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.line-qc-tag + .line-qc-tag {
  margin-left: 4px;
}

.receipt-block + .receipt-block {
  margin-top: 16px;
}

.receipt-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}

.mb-12 {
  margin-bottom: 12px;
}

.mt-8 {
  margin-top: 8px;
}

@media (max-width: 768px) {
  .line-row {
    flex-wrap: wrap;
  }
}
</style>
