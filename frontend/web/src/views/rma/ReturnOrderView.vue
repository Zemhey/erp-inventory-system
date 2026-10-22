<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  discountLabel,
  fenToYuan,
  mdmApi,
  rmaApi,
  salApi,
  type Customer,
  type Product,
  type ReturnOrder,
  type ReturnOrderCreate,
  type SalesOrder,
  type Sku,
  type Warehouse,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import StatusTag from '@/components/StatusTag.vue'
import {
  RETURN_STATUSES,
  nextActions,
  statusMeta,
  type ActionMeta,
  type DocumentAction,
} from '@/utils/documentStatus'
import { useSessionStore } from '@/stores/session'

/**
 * 退货单（FR-RMA-01，计划书 §3.3 / §3.8）。
 *
 * ★ 与进货、出货两页最要紧的差别：这张表单**没有单价和折扣输入**。
 *   退货金额由后端从原出货行派生（原单价 × 退货量 × 原行折扣），
 *   请求体只有 originLineId / qty / 批次 / 效期 —— 契约的
 *   `ReturnOrderCreate` 就是这么写的（packages/api-contract/openapi.yaml）。
 *   这不是偷懒：若退货侧能传价格，每开一张退货单就多一次改价机会，
 *   冲销金额会和原出货记录对不上，日清与对账随之失真。
 *
 * ★ 第二处差别：「剩余可退」= 原出货行的 shippedQty − returnedQty。
 *   这个减法只用于**提示与默认值**，不是判定 —— 真正的守卫是入库时
 *   `registerReturn` 的条件 UPDATE（§6 坑 5：先查后改必有竞态）。
 *   所以数量框刻意不设上限、提交前也不拦"超退"：填超了照样提交，
 *   由后端 `RMA_QTY_EXCEED_ORIGIN` 定音，消息原样显示在弹窗里。
 *
 * ★ 入库是**整单一次**回补（退货单没有 PARTIAL），
 *   所以二次确认文案必须写清后果：加实存 + 写 RETURN_IN 流水 + 累加原行已退数量。
 *   按钮显隐一律问 `nextActions('RETURN', ...)`，模板里没有状态字面量；
 *   这张表由 `npm run check:status-parity` 对着 ReturnOrderController 校验。
 */

const session = useSessionStore()
const canCreate = computed(() => session.hasPermission('rma:create'))

// ─────────────────────────── 列表 ───────────────────────────

const orders = ref<ReturnOrder[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const customerFilter = ref<number | undefined>(undefined)
const originFilter = ref<number | undefined>(undefined)
const listLoading = ref(false)
const listError = ref('')

const filtered = computed(
  () =>
    statusFilter.value !== '' ||
    customerFilter.value !== undefined ||
    originFilter.value !== undefined,
)

/** 状态候选取 RETURN_STATUSES（七态去掉 PARTIAL）；依据由 parity 校验盯着。 */
const statusOptions = RETURN_STATUSES.map((value) => ({
  value,
  label: statusMeta(value).label,
}))

interface ErrorInfo {
  message: string
  details?: Record<string, unknown>
}

function errorInfo(error: unknown): ErrorInfo {
  const e = error as {
    response?: { data?: { message?: string; details?: Record<string, unknown> } }
    message?: string
  }
  return {
    message: e?.response?.data?.message || e?.message || '请求失败',
    details: e?.response?.data?.details,
  }
}

function errorMessage(error: unknown): string {
  return errorInfo(error).message
}

async function loadOrders() {
  listLoading.value = true
  listError.value = ''
  try {
    const data = await rmaApi.list({
      status: statusFilter.value || undefined,
      customerId: customerFilter.value,
      originSalesOrderId: originFilter.value,
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
  customerFilter.value = undefined
  originFilter.value = undefined
  searchOrders()
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  loadOrders()
}

// ─────────────────────────── 主数据字典（客户 / 仓库 / SKU / 已出货单） ───────────────────────────

const customers = ref<Customer[]>([])
/** 名称字典含停用项：历史单据的客户 / 仓库停用后仍要显示得出名字。 */
const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
/** SKU 只显示"颜色-尺码"会同名撞车（多个商品都有白色-L），所以要带商品名。 */
const products = ref<Product[]>([])
const dictTruncated = ref(false)

async function loadDictionary() {
  const [cus, wh, sku, prod] = await Promise.allSettled([
    mdmApi.listCustomers({ page: 1, size: 200 }),
    mdmApi.listWarehouses(false),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (cus.status === 'fulfilled') customers.value = cus.value.items
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
  dictTruncated.value =
    (cus.status === 'fulfilled' && cus.value.total > cus.value.items.length) ||
    (sku.status === 'fulfilled' && sku.value.total > sku.value.items.length) ||
    (prod.status === 'fulfilled' && prod.value.total > prod.value.items.length)
}

const customerNames = computed(() => {
  const map = new Map<number, string>()
  for (const c of customers.value) map.set(c.id, c.enabled ? c.name : `${c.name}（已停用）`)
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

function customerText(id: number): string {
  return customerNames.value.get(id) ?? `客户#${id}`
}

/** 回补仓库由原出货单派生，界面只能显示、不能选。 */
function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}

function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

/**
 * 可作退货来源的出货单：状态 PARTIAL / COMPLETED。
 *
 * 两次分页各取 200 条（后端 MAX_SIZE = 200，且没有按 id 批量取单号的接口）。
 * 取满即认为可能不全，用 shippedTruncated 明说 —— 建单是"选单"而不是搜索，
 * 列表外的单子必须让用户知道这里可能筛不到，而不是以为系统里没有。
 */
const shippedOrders = ref<SalesOrder[]>([])
const shippedTruncated = ref(false)
/**
 * 取不到可退单据的原因（后端原始消息）。
 *
 * 建单页要跨模块读出货单（选原单 + 拉可退明细），所以**只有 rma:\* 而没有 sal:read 的角色**
 * 会在这里吃到 403。不把它显示出来的话，界面看起来像"系统里没有可退的单"，
 * 用户会去怀疑数据本身 —— 那比报错糟得多。（缺口已登记进 M2 计划书 §6。）
 */
const shippedError = ref('')

async function loadShippedOrders() {
  const [completed, partial] = await Promise.allSettled([
    salApi.list({ status: 'COMPLETED', page: 1, size: 200 }),
    salApi.list({ status: 'PARTIAL', page: 1, size: 200 }),
  ])
  const items: SalesOrder[] = []
  let truncated = false
  let firstError = ''
  for (const result of [completed, partial]) {
    if (result.status === 'fulfilled') {
      items.push(...result.value.items)
      truncated = truncated || result.value.total > result.value.items.length
    } else if (!firstError) {
      firstError = errorMessage(result.reason)
    }
  }
  shippedOrders.value = items.sort((a, b) => b.id - a.id)
  // 任一路失败都意味着这份名单不全：既报原因，也按"可能不全"提示
  shippedError.value = firstError
  shippedTruncated.value = truncated || firstError !== ''
}

/** 至少还有一行可退的出货单才进建单下拉：全退完的单子在列表里也是白列。 */
const returnableOrders = computed(() =>
  shippedOrders.value.filter((o) =>
    (o.lines ?? []).some((l) => remainingOf(l.shippedQty, l.returnedQty) > 0),
  ),
)

function remainingOf(shippedQty: number | undefined, returnedQty: number | undefined): number {
  return (shippedQty ?? 0) - (returnedQty ?? 0)
}

/** 原出货单显示成单号；字典取不到（超出 200 条）时退回 id，不留空白。 */
function originText(id: number): string {
  return shippedOrders.value.find((o) => o.id === id)?.orderNo ?? `出货单#${id}`
}

function originOptionLabel(order: SalesOrder): string {
  const returnableQty = (order.lines ?? []).reduce(
    (sum, l) => sum + Math.max(0, remainingOf(l.shippedQty, l.returnedQty)),
    0,
  )
  return `${order.orderNo} · ${customerText(order.customerId)} · 可退 ${returnableQty} 件`
}

// ─────────────────────────── 状态机驱动的动作按钮 ───────────────────────────

/** 无状态字面量的按钮表：可见性完全由 `nextActions` 决定（含权限过滤）。 */
function actionsFor(row: ReturnOrder): ActionMeta[] {
  return nextActions('RETURN', row.status, undefined, (p) => session.hasPermission(p))
}

/** 退货的五个动作都不需要额外输入：数量与批次在建单时已定，入库无请求体。 */
const RUNNERS: Partial<Record<DocumentAction, (id: number) => Promise<ReturnOrder>>> = {
  submit: (id) => rmaApi.submit(id),
  approve: (id) => rmaApi.approve(id),
  reject: (id) => rmaApi.reject(id),
  void: (id) => rmaApi.voidOrder(id),
  receive: (id) => rmaApi.receive(id),
}

/** 正在执行的动作（单据 id:动作），用于按钮 loading 并顺带防止连点。 */
const pendingKey = ref('')

function isPending(rowId: number, action: DocumentAction): boolean {
  return pendingKey.value === `${rowId}:${action}`
}

async function runAction(row: ReturnOrder, meta: ActionMeta) {
  const runner = RUNNERS[meta.action]
  if (!runner) return

  if (meta.needsConfirm) {
    try {
      await ElMessageBox.confirm(
        meta.confirmText ?? `确定${meta.label}退货单 ${row.orderNo} 吗？`,
        `${meta.label}退货单`,
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
    await refreshAll()
    if (detail.value?.id === updated.id) detail.value = updated
  } catch {
    // 拦截器已经弹了后端原始消息（状态非法 / 已入库不能作废等），页面不重复弹；
    // 这里只刷新列表，让界面对齐后端真相。
    await refreshAll()
  } finally {
    pendingKey.value = ''
  }
}

/**
 * 入库会改原出货行的已退数量，建单下拉里的"可退"跟着变。
 * 所以任何动作之后都要一起刷新，否则下一次建单会按旧的可退量填数。
 */
async function refreshAll() {
  await Promise.all([loadOrders(), loadShippedOrders()])
}

// ─────────────────────────── 建单（选已出货单 → 填可退明细） ───────────────────────────

interface ReturnLineDraft {
  /** 原出货单明细行 id：提交时唯一的行标识 */
  originLineId: number
  lineNo: number
  skuId: number
  qty: number
  shippedQty: number
  returnedQty: number
  remainingQty: number
  /** 以下两项**只读展示**，来自原出货行，绝不进请求体 */
  unitPriceAmount: number
  discountRate: number
  inputQty: number | undefined
  batchNo: string
  productionDate: string
  expiryDate: string
}

const createDialog = ref(false)
const createFormRef = ref<FormInstance>()
const saving = ref(false)
const createError = ref('')
const originLoading = ref(false)
const createForm = reactive({
  originSalesOrderId: undefined as number | undefined,
  bizDate: '',
  remark: '',
})

const createRules: FormRules = {
  originSalesOrderId: [{ required: true, message: '请选择原出货单', trigger: 'change' }],
}

const originOrder = ref<SalesOrder | null>(null)
const lines = ref<ReturnLineDraft[]>([])

/** 填超了的行：只做提示，**判定权在后端**。 */
const overRows = computed(() =>
  lines.value.filter((l) => (l.inputQty ?? 0) > l.remainingQty && (l.inputQty ?? 0) > 0),
)
const previewQty = computed(() => lines.value.reduce((sum, l) => sum + (l.inputQty ?? 0), 0))

/**
 * 冲销金额预览（只用于界面）。
 *
 * 与后端同一个整数公式 round(qty × 单价分 × 折扣率 / 10000)；
 * 落库金额一律以后端返回的 amount / totalAmount 为准（ADR-0005）。
 */
function lineAmountFen(row: ReturnLineDraft): number {
  const qty = row.inputQty ?? 0
  if (qty <= 0) return 0
  const rate = row.discountRate ?? 10000
  return Math.round((qty * row.unitPriceAmount * rate) / 10000)
}

const previewTotal = computed(() => lines.value.reduce((sum, l) => sum + lineAmountFen(l), 0))

function todayText(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function openCreate() {
  Object.assign(createForm, {
    originSalesOrderId: undefined,
    bizDate: todayText(),
    remark: '',
  })
  originOrder.value = null
  lines.value = []
  createError.value = ''
  createDialog.value = true
  // 每次打开都重取可退量：列表里那份 lines 可能是上一次刷新时的旧值，
  // 拿旧值显示"可退 10"、实际只剩 4，用户填 6 就会被后端拒绝（§0 服务端为唯一真相）。
  await loadShippedOrders()
}

/**
 * 选中原出货单后拉一次详情，把明细摊成可填的行。
 *
 * 未出货（shippedQty = 0）与已退完（剩余 0）的行**照样列出来但禁填** ——
 * 操作员需要对得上"这单到底退到哪了"，隐藏行会让他以为系统少算了。
 */
async function onOriginChange() {
  createError.value = ''
  originOrder.value = null
  lines.value = []
  const id = createForm.originSalesOrderId
  if (id == null) return

  originLoading.value = true
  try {
    const fresh = await salApi.get(id)
    originOrder.value = fresh
    lines.value = (fresh.lines ?? []).map((l) => {
      const shipped = l.shippedQty ?? 0
      const returned = l.returnedQty ?? 0
      return {
        originLineId: l.id,
        lineNo: l.lineNo,
        skuId: l.skuId,
        qty: l.qty,
        shippedQty: shipped,
        returnedQty: returned,
        remainingQty: shipped - returned,
        unitPriceAmount: l.unitPriceAmount,
        discountRate: l.discountRate ?? 10000,
        inputQty: undefined,
        batchNo: '',
        productionDate: '',
        expiryDate: '',
      }
    })
  } catch (error) {
    // 原单加载失败：弹窗留着，把后端消息原样显示，用户可换个单子再试。
    createError.value = errorMessage(error)
  } finally {
    originLoading.value = false
  }
}

/** 一键按"剩余可退"填满：全退是退货里最常见的一种，不该让人逐行敲数字。 */
function fillRemaining() {
  for (const line of lines.value) {
    if (line.remainingQty > 0) line.inputQty = line.remainingQty
  }
}

function clearInput() {
  for (const line of lines.value) line.inputQty = undefined
}

async function saveOrder() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  const filled = lines.value.filter((l) => (l.inputQty ?? 0) > 0)
  if (!filled.length) {
    createError.value = '请至少填写一行的本次退货数量（该单没有可退明细时无法建单）'
    return
  }
  if (originOrder.value == null) {
    createError.value = '原出货单还没加载成功，无法建单：请重新选择或关闭弹窗后重试'
    return
  }

  // 请求体里没有 unitPriceAmount / discountRate / customerId / warehouseId：
  // 这四样全部由后端从原出货单派生（契约 ReturnOrderCreate）。
  const body: ReturnOrderCreate = {
    originSalesOrderId: createForm.originSalesOrderId!,
    bizDate: createForm.bizDate || undefined,
    remark: createForm.remark.trim() || undefined,
    lines: filled.map((l) => ({
      originLineId: l.originLineId,
      qty: l.inputQty!,
      batchNo: l.batchNo.trim() || undefined,
      productionDate: l.productionDate || undefined,
      expiryDate: l.expiryDate || undefined,
    })),
  }

  saving.value = true
  createError.value = ''
  try {
    const created = await rmaApi.create(body)
    ElMessage.success(
      `退货单 ${created.orderNo} 已创建（草稿）：${created.totalQty} 件，冲销金额 ¥${fenToYuan(created.totalAmount)}`,
    )
    createDialog.value = false
    page.value = 1
    await loadOrders()
  } catch (error) {
    // 建单校验（原单未出货 / 同一原行重复 / 超退）都在后端的一个事务里，
    // 失败时库存、已退数量、单据都不留痕迹。消息含原单号与行号，原样展示。
    createError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

// ─────────────────────────── 详情抽屉 ───────────────────────────

const detailDrawer = ref(false)
const detail = ref<ReturnOrder | null>(null)
const detailId = ref<number | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

async function openDetail(row: ReturnOrder) {
  detailId.value = row.id
  detailDrawer.value = true
  await refreshDetail(row.id)
}

async function refreshDetail(id: number) {
  detailId.value = id
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await rmaApi.get(id)
  } catch (error) {
    detail.value = null
    detailError.value = errorMessage(error)
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailDrawer.value = false
  detail.value = null
  detailId.value = null
  detailError.value = ''
}

onMounted(async () => {
  await Promise.all([loadDictionary(), loadShippedOrders(), loadOrders()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">退货单</h2>
      <div>
        <el-button @click="refreshAll">刷新</el-button>
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建退货单</el-button>
      </div>
    </div>
    <p class="page-hint">
      流程：选一张已出货单（部分出货 / 已完成）→ 填本次退货数量与批次 → 保存草稿 → 提交 → 审核 →
      <b>退货入库</b>。入库整单一次回补库存并写 <b>RETURN_IN</b> 流水，同时累加原出货行的已退数量；
      单价、折扣、客户、回补仓库一律从原出货单派生，退货侧不能改。累计退货超出已出货会被后端拒绝。
    </p>

    <el-alert
      v-if="dictTruncated"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="部分客户 / SKU 未能显示名称"
      description="主数据查询接口每页最多 200 条，且后端没有按 ID 批量取名的接口；字典外的行会显示 客户#编号、SKU#编号。"
    />

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`退货单查询失败：${listError}`"
    >
      <el-button size="small" @click="loadOrders">重试</el-button>
    </el-alert>

    <el-alert
      v-if="shippedError"
      type="warning"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`可退出货单加载失败：${shippedError}`"
      description="退货建单要先读出货单（选原单、取每行已出货与已退数量）。这一步失败时，「原出货单」下拉与筛选会是空的，不代表系统里没有可退的单。"
    >
      <el-button size="small" @click="loadShippedOrders">重试</el-button>
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
      create-text="新建第一张退货单"
      empty-text="还没有退货单"
      empty-hint="退货必须先有一张已出货的出货单：客户把货退回来，入库后可售库存才会回补。"
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
          v-model="customerFilter"
          placeholder="全部客户"
          filterable
          clearable
          class="filter-select"
          @change="searchOrders"
        >
          <el-option v-for="c in customers" :key="c.id" :label="customerText(c.id)" :value="c.id" />
        </el-select>
        <el-select
          v-model="originFilter"
          placeholder="全部原出货单"
          filterable
          clearable
          class="filter-select"
          @change="searchOrders"
        >
          <el-option
            v-for="o in shippedOrders"
            :key="o.id"
            :label="originText(o.id)"
            :value="o.id"
          />
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
      <el-table-column label="原出货单" min-width="160">
        <template #default="{ row }">{{ originText(row.originSalesOrderId) }}</template>
      </el-table-column>
      <el-table-column label="客户" min-width="140">
        <template #default="{ row }">{{ customerText(row.customerId) }}</template>
      </el-table-column>
      <el-table-column label="回补仓库" min-width="130">
        <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column prop="bizDate" label="业务日期" width="120" />
      <el-table-column prop="totalQty" label="退货数量" width="100" align="right" />
      <el-table-column label="冲销金额" width="120" align="right">
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
      title="新建退货单"
      width="1000px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="shippedError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
        :title="`可退出货单加载失败：${shippedError}`"
        description="读不到出货单就没法选原单：这一步需要 sal:read 权限（退货的数量上限、单价、折扣、客户、仓库全部来自原出货单）。"
      >
        <el-button size="small" @click="loadShippedOrders">重试</el-button>
      </el-alert>

      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="原出货单" prop="originSalesOrderId">
          <el-select
            v-model="createForm.originSalesOrderId"
            placeholder="选择已出货的单据（部分出货 / 已完成）"
            filterable
            class="full"
            @change="onOriginChange"
          >
            <el-option
              v-for="o in returnableOrders"
              :key="o.id"
              :label="originOptionLabel(o)"
              :value="o.id"
            />
          </el-select>
          <span v-if="!returnableOrders.length && !originLoading && !shippedError" class="field-hint">
            当前没有可退的出货单（近 200 条已完成 / 部分出货单据里，各行的已退数量都已达到已出货数量）
          </span>
          <span v-else-if="shippedTruncated" class="field-hint">
            可退单据按状态各取最近 200 条，可能没列全：请改用出货单页查单号后再回来选
          </span>
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker
            v-model="createForm.bizDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="默认今天"
            class="w-200"
          />
          <span class="field-hint">退货单按业务日期取号并归属账期，日清与月结以它为准</span>
        </el-form-item>

        <el-form-item v-if="originOrder" label="原单信息">
          <div class="origin-grid">
            <div><span class="dt">单号</span>{{ originOrder.orderNo }}</div>
            <div><span class="dt">状态</span>{{ statusMeta(originOrder.status).label }}</div>
            <div><span class="dt">客户</span>{{ customerText(originOrder.customerId) }}</div>
            <div>
              <span class="dt">回补仓库</span>{{ warehouseText(originOrder.warehouseId) }}
              <span class="field-hint">由原单派生，不可改</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item v-if="lines.length" label="可退明细">
          <el-table :data="lines" size="small" border class="lines-table">
            <el-table-column prop="lineNo" label="行号" width="60" />
            <el-table-column label="SKU" min-width="180">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="单据数量" width="90" align="right" />
            <el-table-column prop="shippedQty" label="已出货" width="80" align="right" />
            <el-table-column prop="returnedQty" label="已退货" width="80" align="right" />
            <el-table-column label="剩余可退" width="90" align="right">
              <template #default="{ row }">
                <span :class="{ 'text-muted': row.remainingQty === 0 }">{{ row.remainingQty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="本次退货" width="140">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.inputQty"
                  :min="1"
                  :step="1"
                  :disabled="row.remainingQty === 0"
                  controls-position="right"
                  class="full"
                />
              </template>
            </el-table-column>
            <el-table-column label="批次号" width="140">
              <template #default="{ row }">
                <el-input
                  v-model="row.batchNo"
                  maxlength="64"
                  placeholder="可选"
                  :disabled="row.remainingQty === 0"
                />
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
                  :disabled="row.remainingQty === 0"
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
                  :disabled="row.remainingQty === 0"
                />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }">
                <span class="amount text-muted">{{ fenToYuan(row.unitPriceAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="折扣" width="130" align="right">
              <template #default="{ row }">
                <span class="text-muted">{{ discountLabel(row.discountRate) }}（{{ row.discountRate }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="行冲销额" width="100" align="right">
              <template #default="{ row }">
                <span class="amount">{{ fenToYuan(lineAmountFen(row)) }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="lines-foot">
            <div class="lines-tools">
              <el-button size="small" @click="fillRemaining">按剩余可退全填</el-button>
              <el-button size="small" :disabled="!previewQty" @click="clearInput">清空</el-button>
            </div>
            <span class="lines-total">
              本次合计 <b>{{ previewQty }}</b> 件 ·
              <span class="amount">¥ {{ fenToYuan(previewTotal) }}</span>
              <span class="field-hint">预览值，实际金额以后端返回为准</span>
            </span>
          </div>
          <div v-if="overRows.length" class="over-tip">
            第 {{ overRows.map((r) => r.lineNo).join('、') }} 行填写量超过剩余可退数量 ——
            仍可保存，是否超退由后端判定（超退会整单拒绝并原样提示）。
          </div>
          <div class="field-hint">
            批次号与效期是退货回补入库的批次属性，可留空（留空即空批次）。
            单价与折扣<b>只读</b>：取自原出货行，退货侧不改价。
            同一原出货行在一笔退货里只能出现一次（重复行会被后端拒绝）。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="createForm.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="如：客户尺码不合"
          />
        </el-form-item>
      </el-form>

      <el-alert v-if="createError" type="error" show-icon :closable="false" :title="createError">
        <div class="error-location">
          建单失败时后端整事务回滚：库存、原出货行的已退数量、单据都不会留下痕迹。
        </div>
      </el-alert>

      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!lines.length || originLoading"
          @click="saveOrder"
        >
          保存草稿
        </el-button>
      </template>
    </el-dialog>

    <!-- ─────────── 详情 ─────────── -->
    <el-drawer v-model="detailDrawer" size="70%" :with-header="true" @close="closeDetail">
      <template #header>
        <div class="drawer-header">
          <div>
            <div class="drawer-title">{{ detail?.orderNo || '退货单详情' }}</div>
            <div v-if="detail" class="drawer-sub">
              <StatusTag :status="detail.status" size="small" />
              <span>原出货单 {{ originText(detail.originSalesOrderId) }}</span>
              <span>{{ customerText(detail.customerId) }}</span>
              <span>{{ warehouseText(detail.warehouseId) }}</span>
              <span>{{ detail.bizDate }}</span>
            </div>
          </div>
          <el-button v-if="detail" size="small" @click="refreshDetail(detail.id)">刷新</el-button>
        </div>
      </template>

      <div v-loading="detailLoading" class="drawer-body">
        <el-alert
          v-if="detailError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`退货单详情加载失败：${detailError}`"
        >
          <el-button v-if="detailId" size="small" @click="refreshDetail(detailId)">重试</el-button>
        </el-alert>

        <template v-if="detail">
          <div class="detail-grid">
            <div><span class="dt">退货数量</span>{{ detail.totalQty }}</div>
            <div>
              <span class="dt">冲销金额</span>
              <span class="amount">¥ {{ fenToYuan(detail.totalAmount) }}</span>
            </div>
            <div><span class="dt">期间</span>{{ detail.period || '-' }}</div>
            <div><span class="dt">业务日期</span>{{ detail.bizDate }}</div>
            <div class="span-2"><span class="dt">备注</span>{{ detail.remark || '-' }}</div>
          </div>

          <el-alert type="info" show-icon :closable="false" class="mb-16" :title="statusMeta(detail.status).description" />

          <el-table :data="detail.lines" size="small" border>
            <el-table-column prop="lineNo" label="行号" width="70" />
            <el-table-column label="SKU" min-width="180">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="退货数量" width="90" align="right" />
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }">
                <span class="amount text-muted">{{ fenToYuan(row.unitPriceAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="折扣" width="130" align="right">
              <template #default="{ row }">
                <span class="text-muted">{{ discountLabel(row.discountRate) }}（{{ row.discountRate }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="行冲销额" width="110" align="right">
              <template #default="{ row }">
                <span class="amount">{{ fenToYuan(row.amount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="批次" width="120">
              <template #default="{ row }">
                <span :class="{ 'text-muted': !row.batchNo }">{{ row.batchNo || '空批次' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="生产日期" width="110">
              <template #default="{ row }">
                <span :class="{ 'text-muted': !row.productionDate }">{{ row.productionDate || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="效期" width="110">
              <template #default="{ row }">
                <span :class="{ 'text-muted': !row.expiryDate }">{{ row.expiryDate || '-' }}</span>
              </template>
            </el-table-column>
          </el-table>

          <p class="page-hint mt-8">
            「单价 / 折扣」由后端从原出货行派生后回显，退货单本身不保存这些字段。
            状态为已完成时，原出货行的已退数量已累加，可在出货单详情里核对。
          </p>

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
        <el-empty v-else-if="!detailLoading && !detailError" description="没有取到单据内容" />
      </div>
    </el-drawer>
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

.origin-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 24px;
  font-size: 13px;
}

.dt {
  display: inline-block;
  min-width: 76px;
  color: #909399;
}

.lines-table {
  width: 100%;
}

.lines-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}

.lines-tools {
  display: flex;
  gap: 8px;
}

.lines-total {
  font-size: 13px;
}

.over-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #e6a23c;
}

.error-location {
  margin-top: 6px;
  font-size: 12px;
}

.mt-8 {
  margin-top: 8px;
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
  flex-wrap: wrap;
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
  flex-wrap: wrap;
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

@media (max-width: 768px) {
  .filter-select,
  .filter-select-sm,
  .w-200 {
    width: 100%;
  }

  .origin-grid,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .detail-grid .span-2 {
    grid-column: span 1;
  }
}
</style>
