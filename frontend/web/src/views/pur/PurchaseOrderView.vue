<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  purApi,
  fenToYuan,
  yuanToFen,
  type OrderLine,
  type Product,
  type PurchaseOrder,
  type PurchaseOrderCreate,
  type Sku,
  type Supplier,
  type Warehouse,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import StatusTag from '@/components/StatusTag.vue'
import {
  DOCUMENT_STATUSES,
  nextActions,
  progressText,
  statusMeta,
  type ActionMeta,
  type DocumentAction,
} from '@/utils/documentStatus'
import { useSessionStore } from '@/stores/session'

/**
 * 进货单（FR-PUR-01 建单/提交/审核 + FR-PUR-02 分批入库）。
 *
 * ★ 这一页的两条设计约束，都来自计划书：
 *
 *   1. **按钮显隐只问 `utils/documentStatus.ts`，模板里不出现状态字面量**
 *      （§3.2）。七态 × 六个动作若散写成 `v-if="status === 'DRAFT'"`，
 *      改一次规则要改十几处，漏掉的那处的表现是"用户点了收到 409"。
 *      这张表还是后端能力的**镜像**，由 `npm run check:status-parity` 守住。
 *      REJECTED 只有「作废」（G2：后端无 /reopen）、PARTIAL 没有「作废」
 *      （G4：已入库的作废一律被业务层拒绝，需红冲属 M2）—— 少一个按钮不是偷懒，
 *      是"没有接口的按钮点了必然 405"。
 *
 *   2. **前端不做任何判定**（§0）。超量入库在这里只是**提示**：
 *      填超了会给一行黄字，但不拦住提交，最终结论由后端的
 *      `PUR_OVER_RECEIVE` 给出（它的消息含单号、行号、已入、本次、超出多少，
 *      比前端任何自算都有用）。同理"能不能审核"由后端状态机判定，
 *      前端收到 409 就原样展示。
 *
 * ★ 金额一律整数分（§3.4 / ADR-0005）：输入框收元、提交前 `yuanToFen`、
 *   展示时 `fenToYuan`。行金额与合计只做**录入时的实时预览**，
 *   落库金额以后端返回的 `amount` / `totalAmount` 为准。
 */

const session = useSessionStore()
const canCreate = computed(() => session.hasPermission('pur:create'))

// ─────────────────────────── 列表 ───────────────────────────

const orders = ref<PurchaseOrder[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const supplierFilter = ref<number | undefined>(undefined)
const listLoading = ref(false)
const listError = ref('')

const filtered = computed(
  () => statusFilter.value !== '' || supplierFilter.value !== undefined,
)

/** 状态筛选候选：直接取状态机镜像表的七个状态，不在页面里另列一份。 */
const statusOptions = DOCUMENT_STATUSES.map((value) => ({
  value,
  label: statusMeta(value).label,
}))

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadOrders() {
  listLoading.value = true
  listError.value = ''
  try {
    const data = await purApi.list({
      status: statusFilter.value || undefined,
      supplierId: supplierFilter.value,
      page: page.value,
      size: pageSize.value,
    })
    orders.value = data.items
    total.value = data.total
  } catch (error) {
    orders.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    listLoading.value = false
  }
}

function searchOrders() {
  page.value = 1
  loadOrders()
}

function resetFilters() {
  statusFilter.value = ''
  supplierFilter.value = undefined
  searchOrders()
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  loadOrders()
}

/**
 * 进度列（§7 决策 3："这单还差多少没到货"）。
 *
 * `totalQty` 与每行 `receivedQty` 都由后端返回，这里只做汇总显示；
 * 后端在列表接口里就把明细一并带回（`PurchaseOrderRepository#page` → findLines），
 * 所以不需要为进度再逐单查详情。
 */
function progress(row: PurchaseOrder): { text: string; open: number } | null {
  if (!row.lines?.length) return null
  const received = row.lines.reduce((sum, l) => sum + (l.receivedQty ?? 0), 0)
  const ordered = row.totalQty ?? 0
  return { text: progressText('PURCHASE', received, ordered), open: ordered - received }
}

// ─────────────────────────── 主数据字典（供应商 / 仓库 / SKU） ───────────────────────────

const suppliers = ref<Supplier[]>([])
/** 名称字典含停用项：历史单据的供应商/仓库停用后仍要显示得出名字。 */
const warehouses = ref<Warehouse[]>([])
/** 建单下拉用服务端的 onlyEnabled 过滤结果，不在前端猜。 */
const enabledWarehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
/** SKU 只显示"颜色-尺码"会同名撞车（多个商品都有白色-L），所以要带商品名。 */
const products = ref<Product[]>([])
const dictTruncated = ref(false)

async function loadDictionary() {
  const [sup, wh, whEnabled, sku, prod] = await Promise.allSettled([
    mdmApi.listSuppliers({ page: 1, size: 200 }),
    mdmApi.listWarehouses(false),
    mdmApi.listWarehouses(true),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (sup.status === 'fulfilled') suppliers.value = sup.value.items
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (whEnabled.status === 'fulfilled') enabledWarehouses.value = whEnabled.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
  dictTruncated.value =
    (sup.status === 'fulfilled' && sup.value.total > sup.value.items.length) ||
    (sku.status === 'fulfilled' && sku.value.total > sku.value.items.length) ||
    (prod.status === 'fulfilled' && prod.value.total > prod.value.items.length)
}

/**
 * 可下单的供应商 / SKU：按接口返回的 `enabled` 字段筛掉停用项。
 *
 * 后端 `/suppliers`、`/skus` 没有 `onlyEnabled` 参数（仓库有），
 * 所以这两处只能在**已取回的数据**上筛。这不是业务判定：
 * 建单时后端仍会校验（`requireSupplierEnabled` / `requireSkuEnabled`），
 * 万一筛漏了，返回的错误消息会原样展示。
 */
const supplierOptions = computed(() => suppliers.value.filter((s) => s.enabled))
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
    const product = productNames.get(s.productId) ?? `商品#${s.productId}`
    map.set(s.id, `${product} · ${s.color}-${s.size}`)
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

// ─────────────────────────── 状态机驱动的动作按钮 ───────────────────────────

/**
 * 无状态字面量的按钮表：可见性完全由 `nextActions` 决定
 * （状态 → 动作，再按当前账号权限码过滤）。
 */
function actionsFor(row: PurchaseOrder): ActionMeta[] {
  return nextActions('PURCHASE', row.status, undefined, (p) => session.hasPermission(p))
}

/** 只有「提交/审核/驳回/作废」是点一下就发的动作；入库要填明细，走另一条路。 */
const SIMPLE_RUNNERS: Partial<Record<DocumentAction, (id: number) => Promise<PurchaseOrder>>> = {
  submit: (id) => purApi.submit(id),
  approve: (id) => purApi.approve(id),
  reject: (id) => purApi.reject(id),
  void: (id) => purApi.voidOrder(id),
}

/** 正在执行的动作（单号:动作），用于按钮 loading 并顺带防止连点。 */
const pendingKey = ref('')

function isPending(rowId: number, action: DocumentAction): boolean {
  return pendingKey.value === `${rowId}:${action}`
}

async function runAction(row: PurchaseOrder, meta: ActionMeta) {
  if (meta.needsInput) {
    await openReceive(row)
    return
  }
  const runner = SIMPLE_RUNNERS[meta.action]
  if (!runner) return

  if (meta.needsConfirm) {
    try {
      await ElMessageBox.confirm(
        meta.confirmText ?? `确定${meta.label}单据 ${row.orderNo} 吗？`,
        `${meta.label}进货单`,
        { type: 'warning', confirmButtonText: meta.label, cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }

  pendingKey.value = `${row.id}:${meta.action}`
  try {
    const updated = await runner(row.id)
    ElMessage.success(`${meta.label}成功：${updated.orderNo} · ${statusMeta(updated.status).label}`)
    await loadOrders()
    if (detail.value?.id === updated.id) detail.value = updated
  } catch {
    // 拦截器已经弹了后端的原始消息（含"已有入库，作废需先红冲"这类定位信息），
    // 页面不重复弹；这里只把列表刷新一次，让界面对齐后端真相。
    await loadOrders()
  } finally {
    pendingKey.value = ''
  }
}

// ─────────────────────────── 建单 ───────────────────────────

interface LineDraft {
  skuId: number | undefined
  qty: number | undefined
  priceYuan: string
}

const createDialog = ref(false)
const createFormRef = ref<FormInstance>()
const saving = ref(false)
const createError = ref('')
const createForm = reactive({
  supplierId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  bizDate: '',
  remark: '',
})

const createRules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择入库仓库', trigger: 'change' }],
}

function todayText(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function emptyLine(): LineDraft {
  return { skuId: undefined, qty: undefined, priceYuan: '' }
}

const lines = ref<LineDraft[]>([])

function openCreate() {
  Object.assign(createForm, {
    supplierId: undefined,
    warehouseId: undefined,
    bizDate: todayText(),
    remark: '',
  })
  // 起始只给一行空行：给两行的话，用户填完第一行点「添加一行」会多出两行空的
  lines.value = [emptyLine()]
  createError.value = ''
  createDialog.value = true
}

/**
 * 已选中的 SKU 从下拉里去掉（§7 决策 2）。
 *
 * 同一 SKU 拆两行会让"按行入库"的语义混乱 —— 后端不会报错，
 * 但入库时"这一行还差多少"要跨两行算，用户根本分不清。
 */
function skuOptionsFor(row: LineDraft) {
  const picked = new Set(
    lines.value.filter((l) => l !== row && l.skuId != null).map((l) => l.skuId as number),
  )
  return skuOptions.value.filter((s) => !picked.has(s.id))
}

function addLine() {
  lines.value.push(emptyLine())
}

function removeLine(index: number) {
  lines.value.splice(index, 1)
  if (!lines.value.length) lines.value.push(emptyLine())
}

/** 整行没填 = 用户还没用到它；部分填写 = 要提醒补全。 */
function isBlank(row: LineDraft): boolean {
  return row.skuId == null && row.qty == null && row.priceYuan.trim() === ''
}

function lineAmountFen(row: LineDraft): number {
  const qty = row.qty ?? 0
  if (!row.skuId || qty <= 0) return 0
  return qty * yuanToFen(row.priceYuan)
}

const previewTotal = computed(() => lines.value.reduce((sum, l) => sum + lineAmountFen(l), 0))
const previewQty = computed(() =>
  lines.value.reduce((sum, l) => sum + (l.skuId ? (l.qty ?? 0) : 0), 0),
)

async function saveOrder() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  const filled = lines.value.filter((l) => !isBlank(l))
  if (!filled.length) {
    createError.value = '至少填写一行明细：SKU、数量、采购单价'
    return
  }
  const badIndex = filled.findIndex(
    (l) => l.skuId == null || !l.qty || l.qty < 1 || l.priceYuan.trim() === '',
  )
  if (badIndex >= 0) {
    createError.value = `第 ${badIndex + 1} 行明细没填完：需要 SKU、数量（正整数）与采购单价`
    return
  }

  const body: PurchaseOrderCreate = {
    supplierId: createForm.supplierId!,
    warehouseId: createForm.warehouseId!,
    bizDate: createForm.bizDate || undefined,
    remark: createForm.remark.trim() || undefined,
    lines: filled.map((l) => ({
      skuId: l.skuId!,
      qty: l.qty!,
      unitPriceAmount: yuanToFen(l.priceYuan),
    })),
  }

  saving.value = true
  createError.value = ''
  try {
    const created = await purApi.create(body)
    ElMessage.success(`进货单 ${created.orderNo} 已创建（草稿），共 ${created.totalQty} 件`)
    createDialog.value = false
    page.value = 1
    await loadOrders()
  } catch (error) {
    // 建单失败整单不生效（后端在一个事务里），保留弹窗内容让用户改
    createError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

// ─────────────────────────── 详情抽屉 ───────────────────────────

const detailDrawer = ref(false)
const detail = ref<PurchaseOrder | null>(null)
const detailLoading = ref(false)

async function openDetail(row: PurchaseOrder) {
  detailDrawer.value = true
  await refreshDetail(row.id)
}

async function refreshDetail(id: number) {
  detailLoading.value = true
  try {
    detail.value = await purApi.get(id)
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailDrawer.value = false
  detail.value = null
}

// ─────────────────────────── 分批入库 ───────────────────────────

interface ReceiveRow {
  lineId: number
  lineNo: number
  skuText: string
  qty: number
  receivedQty: number
  /** 后端返回的未入库量（`openQty`），前端不自算 */
  openQty: number
  /** 本次入库量，由用户填 */
  inputQty: number | undefined
  batchNo: string
  productionDate: string
  expiryDate: string
}

const receiveDialog = ref(false)
const receiveOrder = ref<PurchaseOrder | null>(null)
const receiveRows = ref<ReceiveRow[]>([])
const receiving = ref(false)
const receiveError = ref('')

/** 填超了的那几行：只做提示，**判定权在后端**。 */
const overRows = computed(() => receiveRows.value.filter((r) => (r.inputQty ?? 0) > r.openQty))
const receiveTotal = computed(() => receiveRows.value.reduce((sum, r) => sum + (r.inputQty ?? 0), 0))

/**
 * 打开入库表单前先拉一次详情。
 *
 * 为什么不用列表里那份 lines：`receivedQty` 可能是上一次刷新时的旧值，
 * 拿旧值显示"未入库 10"、实际只剩 4，用户填 6 就会被后端拒绝 ——
 * 看起来像界面算错了。以服务端为唯一真相（§0）。
 */
async function openReceive(row: PurchaseOrder) {
  receiveOrder.value = null
  receiveError.value = ''
  receiveRows.value = []
  receiveDialog.value = true
  receiving.value = true
  try {
    const fresh = await purApi.get(row.id)
    receiveOrder.value = fresh
    receiveRows.value = (fresh.lines ?? []).map((l: OrderLine) => ({
      lineId: l.id,
      lineNo: l.lineNo,
      skuText: skuText(l.skuId),
      qty: l.qty,
      receivedQty: l.receivedQty ?? 0,
      openQty: l.openQty ?? 0,
      inputQty: undefined,
      batchNo: '',
      productionDate: '',
      expiryDate: '',
    }))
  } catch (error) {
    receiveError.value = errorMessage(error)
  } finally {
    receiving.value = false
  }
}

async function submitReceive() {
  const order = receiveOrder.value
  if (!order) return

  const payload = receiveRows.value
    .filter((r) => (r.inputQty ?? 0) > 0)
    .map((r) => ({
      lineId: r.lineId,
      qty: r.inputQty!,
      batchNo: r.batchNo.trim() || undefined,
      productionDate: r.productionDate || undefined,
      expiryDate: r.expiryDate || undefined,
    }))

  if (!payload.length) {
    // 这是表单完整性检查，不是业务规则：后端对空 lines 返回 400，
    // 提前拦住省一次往返，也不会在界面上留下"点了没反应"的错觉。
    receiveError.value = '请至少填写一行的本次入库量'
    return
  }

  receiving.value = true
  receiveError.value = ''
  try {
    const updated = await purApi.receive(order.id, { lines: payload })
    ElMessage.success(
      `本次入库 ${receiveTotal.value} 件：${updated.orderNo} · ${statusMeta(updated.status).label}`,
    )
    receiveDialog.value = false
    await loadOrders()
    if (detail.value?.id === updated.id) detail.value = updated
  } catch (error) {
    // 超量入库在这里被后端拒绝（PUR_OVER_RECEIVE），消息含单号/行号/已入/本次/超出，
    // 原样展示并**保持弹窗打开**，用户改了数字就能直接再提交。
    // 事务整批回滚：任何一行失败，其余行的库存也不会变动。
    receiveError.value = errorMessage(error)
    await loadOrders()
  } finally {
    receiving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadDictionary(), loadOrders()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">进货单</h2>
      <div>
        <el-button @click="loadOrders">刷新</el-button>
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建进货单</el-button>
      </div>
    </div>
    <p class="page-hint">
      流程：建单（草稿）→ 提交 → 审核 → 入库。入库支持分批，累计入库量是否超出单据数量由后端判定。
      按钮只在该状态真的能做时才出现；作废是终态，已有入库的单据作废会被拒绝（需红冲，属 M2）。
    </p>

    <el-alert
      v-if="dictTruncated"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="部分供应商 / SKU 未能显示名称"
      description="主数据查询接口每页最多 200 条，且后端没有按 ID 批量取名的接口；字典外的行会显示 供应商#编号、SKU#编号。"
    />

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`进货单查询失败：${listError}`"
    >
      <el-button size="small" @click="loadOrders">重试</el-button>
    </el-alert>

    <PageTable
      :rows="orders"
      :loading="listLoading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!listError"
      :can-create="canCreate"
      create-text="新建第一张进货单"
      empty-text="还没有进货单"
      empty-hint="进货单是本系统第一条业务闭环：建好后走提交 → 审核 → 入库，库存才会真的增加。"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
      @create="openCreate"
    >
      <template #filters>
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          class="filter-select-sm"
          @change="searchOrders"
        >
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select
          v-model="supplierFilter"
          placeholder="全部供应商"
          filterable
          clearable
          class="filter-select"
          @change="searchOrders"
        >
          <el-option v-for="s in suppliers" :key="s.id" :label="supplierText(s.id)" :value="s.id" />
        </el-select>
        <el-button type="primary" @click="searchOrders">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <el-table-column prop="orderNo" label="单据号" min-width="170" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tooltip :content="statusMeta(row.status).description" placement="top">
            <span>
              <StatusTag :status="row.status" size="small" />
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="供应商" min-width="150">
        <template #default="{ row }">{{ supplierText(row.supplierId) }}</template>
      </el-table-column>
      <el-table-column label="仓库" min-width="130">
        <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column prop="bizDate" label="业务日期" width="120" />
      <el-table-column prop="totalQty" label="单据数量" width="100" align="right" />
      <el-table-column label="入库进度" min-width="150">
        <template #default="{ row }">
          <span v-if="progress(row)" :class="{ 'text-muted': progress(row)!.open === 0 }">
            {{ progress(row)!.text }} · 未入 {{ progress(row)!.open }}
          </span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="120" align="right">
        <template #default="{ row }">
          <span class="amount">¥ {{ fenToYuan(row.totalAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="140">
        <template #default="{ row }">{{ row.remark || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button
            v-for="a in actionsFor(row)"
            :key="a.action"
            link
            :type="a.buttonType"
            :loading="isPending(row.id, a.action)"
            :disabled="pendingKey !== '' && !isPending(row.id, a.action)"
            @click="runAction(row, a)"
          >
            {{ a.label }}
          </el-button>
          <el-tooltip v-if="!actionsFor(row).length" :content="statusMeta(row.status).description">
            <span class="text-muted">无可用动作</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </PageTable>

    <!-- ─────────── 建单 ─────────── -->
    <el-dialog
      v-model="createDialog"
      title="新建进货单"
      width="900px"
      :close-on-click-modal="false"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="供应商" prop="supplierId">
          <el-select
            v-model="createForm.supplierId"
            placeholder="选择供应商（只列启用中的）"
            filterable
            class="full"
          >
            <el-option v-for="s in supplierOptions" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库仓库" prop="warehouseId">
          <el-select
            v-model="createForm.warehouseId"
            placeholder="选择仓库（只列启用中的）"
            class="full"
          >
            <el-option v-for="w in enabledWarehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker
            v-model="createForm.bizDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="默认今天"
            class="w-200"
          />
          <span class="field-hint">单据号按业务日期取号，不依赖系统时间</span>
        </el-form-item>
        <el-form-item label="明细">
          <el-table :data="lines" size="small" border class="lines-table">
            <el-table-column label="SKU" min-width="240">
              <template #default="{ row }">
                <el-select
                  v-model="row.skuId"
                  placeholder="选择 SKU"
                  filterable
                  class="full"
                >
                  <el-option
                    v-for="s in skuOptionsFor(row)"
                    :key="s.id"
                    :label="`${skuText(s.id)} · 零售 ¥${fenToYuan(s.retailPriceAmount)}`"
                    :value="s.id"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="1" :step="1" controls-position="right" class="full" />
              </template>
            </el-table-column>
            <el-table-column label="采购单价" width="150">
              <template #default="{ row }">
                <el-input v-model="row.priceYuan" placeholder="如 88.00">
                  <template #append>元</template>
                </el-input>
              </template>
            </el-table-column>
            <el-table-column label="行金额" width="110" align="right">
              <template #default="{ row }">
                <span class="amount">{{ fenToYuan(lineAmountFen(row)) }}</span>
              </template>
            </el-table-column>
            <el-table-column width="70" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeLine($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="lines-foot">
            <el-button size="small" @click="addLine">添加一行</el-button>
            <span class="lines-total">
              合计 {{ previewQty }} 件 ·
              <span class="amount">¥ {{ fenToYuan(previewTotal) }}</span>
              <span class="field-hint">预览值，实际金额以后端返回为准</span>
            </span>
          </div>
          <div class="field-hint">
            同一 SKU 只能成一行：已选过的 SKU 会从后面那行的下拉里消失。
            单价不会自动取零售价 —— 采购价与零售价本来就不该相同。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可选"
          />
        </el-form-item>
      </el-form>

      <el-alert v-if="createError" type="error" show-icon :closable="false" :title="createError" />

      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveOrder">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- ─────────── 详情 ─────────── -->
    <el-drawer v-model="detailDrawer" size="70%" :with-header="true" @close="closeDetail">
      <template #header>
        <div class="drawer-header">
          <div>
            <div class="drawer-title">{{ detail?.orderNo || '进货单详情' }}</div>
            <div v-if="detail" class="drawer-sub">
              <StatusTag :status="detail.status" size="small" />
              <span>{{ supplierText(detail.supplierId) }}</span>
              <span>{{ warehouseText(detail.warehouseId) }}</span>
              <span>{{ detail.bizDate }}</span>
            </div>
          </div>
          <el-button v-if="detail" size="small" @click="refreshDetail(detail.id)">
            刷新
          </el-button>
        </div>
      </template>

      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="detail">
          <div class="detail-grid">
            <div><span class="dt">单据数量</span>{{ detail.totalQty }}</div>
            <div>
              <span class="dt">金额</span>
              <span class="amount">¥ {{ fenToYuan(detail.totalAmount) }}</span>
            </div>
            <div>
              <span class="dt">入库进度</span>
              <span v-if="progress(detail)">{{ progress(detail)!.text }} · 未入 {{ progress(detail)!.open }}</span>
              <span v-else>-</span>
            </div>
            <div><span class="dt">期间</span>{{ detail.period || '-' }}</div>
            <div class="span-2"><span class="dt">备注</span>{{ detail.remark || '-' }}</div>
          </div>

          <el-alert
            type="info"
            show-icon
            :closable="false"
            class="mb-16"
            :title="statusMeta(detail.status).description"
          />

          <el-table :data="detail.lines" size="small" border>
            <el-table-column prop="lineNo" label="行号" width="70" />
            <el-table-column label="SKU" min-width="200">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="单据数量" width="100" align="right" />
            <el-table-column prop="receivedQty" label="已入库" width="100" align="right" />
            <el-table-column label="未入库" width="100" align="right">
              <template #default="{ row }">
                <span :class="{ 'text-muted': !row.openQty }">{{ row.openQty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="单价" width="110" align="right">
              <template #default="{ row }">
                <span class="amount">{{ fenToYuan(row.unitPriceAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="行金额" width="120" align="right">
              <template #default="{ row }">
                <span class="amount">{{ fenToYuan(row.amount) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="actionsFor(detail).length" class="drawer-actions">
            <el-button
              v-for="a in actionsFor(detail)"
              :key="a.action"
              :type="a.buttonType"
              :loading="isPending(detail.id, a.action)"
              :disabled="pendingKey !== '' && !isPending(detail.id, a.action)"
              @click="runAction(detail, a)"
            >
              {{ a.label }}
            </el-button>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="没有取到单据内容" />
      </div>
    </el-drawer>

    <!-- ─────────── 分批入库 ─────────── -->
    <el-dialog
      v-model="receiveDialog"
      :title="receiveOrder ? `入库确认 · ${receiveOrder.orderNo}` : '入库确认'"
      width="1000px"
      :close-on-click-modal="false"
    >
      <div v-loading="receiving && !receiveRows.length">
        <p class="receive-hint">
          只填本次实际到货的数量，未填的行不会提交。批次号与效期可选，
          效期是后续出货按 FEFO 拆批次的依据。
        </p>

        <el-table :data="receiveRows" size="small" border>
          <el-table-column prop="lineNo" label="行号" width="60" />
          <el-table-column label="SKU" min-width="170">
            <template #default="{ row }">{{ row.skuText }}</template>
          </el-table-column>
          <el-table-column prop="qty" label="单据数量" width="90" align="right" />
          <el-table-column prop="receivedQty" label="已入库" width="80" align="right" />
          <el-table-column label="未入库" width="80" align="right">
            <template #default="{ row }">
              <span :class="{ 'text-muted': row.openQty === 0 }">{{ row.openQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本次入库" width="150">
            <template #default="{ row }">
              <el-input-number
                v-model="row.inputQty"
                :min="1"
                :step="1"
                :disabled="row.openQty === 0"
                controls-position="right"
                class="full"
              />
            </template>
          </el-table-column>
          <el-table-column label="批次号" width="150">
            <template #default="{ row }">
              <el-input v-model="row.batchNo" maxlength="64" placeholder="可选" :disabled="row.openQty === 0" />
            </template>
          </el-table-column>
          <el-table-column label="生产日期" width="150">
            <template #default="{ row }">
              <el-date-picker
                v-model="row.productionDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="可选"
                class="full"
                :disabled="row.openQty === 0"
              />
            </template>
          </el-table-column>
          <el-table-column label="效期" width="150">
            <template #default="{ row }">
              <el-date-picker
                v-model="row.expiryDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="可选"
                class="full"
                :disabled="row.openQty === 0"
              />
            </template>
          </el-table-column>
        </el-table>

        <div class="receive-foot">
          <span>
            本次合计 <b>{{ receiveTotal }}</b> 件
          </span>
          <span v-if="overRows.length" class="over-tip">
            第 {{ overRows.map((r) => r.lineNo).join('、') }} 行填写量超过未入库数量 ——
            仍可提交，是否超量由后端判定。
          </span>
        </div>

        <el-alert v-if="receiveError" type="error" show-icon :closable="false" :title="receiveError" />
      </div>

      <template #footer>
        <el-button @click="receiveDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="receiving"
          :disabled="!receiveOrder"
          @click="submitReceive"
        >
          确认入库
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-select {
  width: 220px;
}

.filter-select-sm {
  width: 150px;
}

.full {
  width: 100%;
}

.w-200 {
  width: 200px;
}

.field-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.lines-table {
  width: 100%;
}

.lines-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.lines-total {
  font-size: 13px;
}

.drawer-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.drawer-sub {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.drawer-body {
  min-height: 200px;
}

.drawer-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 24px;
  margin-bottom: 16px;
  font-size: 13px;
}

.detail-grid .span-2 {
  grid-column: span 2;
}

.dt {
  display: inline-block;
  min-width: 76px;
  color: #909399;
}

.receive-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: #909399;
}

.receive-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 10px;
  font-size: 13px;
}

.over-tip {
  color: #e6a23c;
}

@media (max-width: 768px) {
  .filter-select,
  .filter-select-sm,
  .w-200 {
    width: 100%;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }

  .detail-grid .span-2 {
    grid-column: span 1;
  }
}
</style>
