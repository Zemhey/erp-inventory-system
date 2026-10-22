<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  salApi,
  discountLabel,
  fenToYuan,
  yuanToFen,
  type Customer,
  type Product,
  type SalesOrder,
  type SalesOrderCreate,
  type Sku,
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
 * 出货单（FR-SAL-01 建单即预占 + FR-SAL-02 出货确认）。
 *
 * ★ 本页与进货单（S5）结构相同，但多了一条**不可逆的副作用**：
 *   保存的瞬间库存就被预占了。因此：
 *
 *   1. **失败必须可读**。后端在一个事务里完成"建单 + 全部行预占"，
 *      任意一行不足即整单回滚。前端不预判库存够不够，只把后端的
 *      `INV_INSUFFICIENT_STOCK` 原样展示，并补一条"辅助定位"——
 *      后端消息只有 skuId / 仓库 id，界面把它翻成行号与 SKU 名称，
 *      用户才知道回弹窗里改哪一行。
 *
 *   2. **金额只做预览**。折扣是整数万分比（10000 = 不打折），
 *      行金额 = round(qty × 单价分 × 折扣率 / 10000)（§3.4）。
 *      保存后一律显示后端返回的 `amount` / `totalAmount`（ADR-0005）。
 *
 *   3. **按钮显隐只问 `nextActions`**，模板里没有状态字面量。
 *      REJECTED 仅「作废」（G2：后端无 /reopen）；PARTIAL 仅「继续出货」
 *      （G4：已出货的作废会被拒绝，需红冲属 M2）。
 *      作废确认框写明"会释放预占"，这条后果用户看不见但影响库存。
 */

const session = useSessionStore()
const canCreate = computed(() => session.hasPermission('sal:create'))

// ─────────────────────────── 列表 ───────────────────────────

const orders = ref<SalesOrder[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const customerFilter = ref<number | undefined>(undefined)
const listLoading = ref(false)
const listError = ref('')

const filtered = computed(
  () => statusFilter.value !== '' || customerFilter.value !== undefined,
)

/** 状态筛选候选：直接取状态机镜像表的七个状态，不在页面里另列一份。 */
const statusOptions = DOCUMENT_STATUSES.map((value) => ({
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
    const data = await salApi.list({
      status: statusFilter.value || undefined,
      customerId: customerFilter.value,
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
  searchOrders()
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  loadOrders()
}

/**
 * 进度列（§7 决策 3）：已出 / 总数。
 *
 * 出货单的"进度"就是已出货数量；后端在列表接口里把明细一并带回，
 * 因此这里只汇总展示，不额外逐单查详情。
 */
function progress(row: SalesOrder): { text: string; open: number } | null {
  if (!row.lines?.length) return null
  const shipped = row.lines.reduce((sum, l) => sum + (l.shippedQty ?? 0), 0)
  const ordered = row.totalQty ?? 0
  return { text: progressText('SALES', shipped, ordered), open: ordered - shipped }
}

// ─────────────────────────── 主数据字典（客户 / 仓库 / SKU） ───────────────────────────

const customers = ref<Customer[]>([])
/** 名称字典含停用项：历史单据的客户/仓库停用后仍要显示得出名字。 */
const warehouses = ref<Warehouse[]>([])
/** 建单下拉用服务端的 onlyEnabled 过滤结果，不在前端猜。 */
const enabledWarehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
/** SKU 只显示"颜色-尺码"会同名撞车（多个商品都有白色-L），所以要带商品名。 */
const products = ref<Product[]>([])
const dictTruncated = ref(false)

async function loadDictionary() {
  const [cus, wh, whEnabled, sku, prod] = await Promise.allSettled([
    mdmApi.listCustomers({ page: 1, size: 200 }),
    mdmApi.listWarehouses(false),
    mdmApi.listWarehouses(true),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (cus.status === 'fulfilled') customers.value = cus.value.items
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  if (whEnabled.status === 'fulfilled') enabledWarehouses.value = whEnabled.value
  if (sku.status === 'fulfilled') skus.value = sku.value.items
  if (prod.status === 'fulfilled') products.value = prod.value.items
  dictTruncated.value =
    (cus.status === 'fulfilled' && cus.value.total > cus.value.items.length) ||
    (sku.status === 'fulfilled' && sku.value.total > sku.value.items.length) ||
    (prod.status === 'fulfilled' && prod.value.total > prod.value.items.length)
}

/**
 * 可下单的客户 / SKU：按接口返回的 `enabled` 字段筛掉停用项。
 *
 * `/customers`、`/skus` 没有 `onlyEnabled` 参数（仓库有，G9），
 * 所以只能在**已取回的数据**上筛。这不是业务判定：
 * 建单时后端仍会校验（`requireCustomerEnabled` / `requireSkuEnabled`），
 * 万一筛漏了，返回的错误消息会原样展示。
 */
const customerOptions = computed(() => customers.value.filter((c) => c.enabled))
const skuOptions = computed(() => skus.value.filter((s) => s.enabled))

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

function warehouseText(id: number): string {
  return warehouseNames.value.get(id) ?? `仓库#${id}`
}

function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

// ─────────────────────────── 状态机驱动的动作按钮 ───────────────────────────

/** 无状态字面量的按钮表：可见性完全由 `nextActions` 决定。 */
function actionsFor(row: SalesOrder): ActionMeta[] {
  return nextActions('SALES', row.status, undefined, (p) => session.hasPermission(p))
}

/** 五个动作都是点一下就发（出货没有参数，按各行未出量整行出）。 */
const SIMPLE_RUNNERS: Partial<Record<DocumentAction, (id: number) => Promise<SalesOrder>>> = {
  submit: (id) => salApi.submit(id),
  approve: (id) => salApi.approve(id),
  reject: (id) => salApi.reject(id),
  void: (id) => salApi.voidOrder(id),
  ship: (id) => salApi.ship(id),
}

/** 正在执行的动作（单号:动作），用于按钮 loading 并顺带防止连点。 */
const pendingKey = ref('')

function isPending(rowId: number, action: DocumentAction): boolean {
  return pendingKey.value === `${rowId}:${action}`
}

async function runAction(row: SalesOrder, meta: ActionMeta) {
  const runner = SIMPLE_RUNNERS[meta.action]
  if (!runner) return

  if (meta.needsConfirm) {
    try {
      await ElMessageBox.confirm(
        meta.confirmText ?? `确定${meta.label}单据 ${row.orderNo} 吗？`,
        `${meta.label}出货单`,
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
    // 拦截器已经弹了后端原始消息（状态非法 / 已出货不能作废等），页面不重复弹；
    // 这里只刷新列表，让界面对齐后端真相。
    await loadOrders()
  } finally {
    pendingKey.value = ''
  }
}

// ─────────────────────────── 建单（保存即预占） ───────────────────────────

interface LineDraft {
  skuId: number | undefined
  qty: number | undefined
  priceYuan: string
  /** 价格是否由选 SKU 自动带入（用户改过就置 false，换 SKU 时不再覆盖） */
  priceAuto: boolean
  /** 整数万分比；默认取客户折扣，可逐行覆盖 */
  discountRate: number | undefined
}

const createDialog = ref(false)
const createFormRef = ref<FormInstance>()
const saving = ref(false)
const createError = ref('')
const createLocation = ref('')
const createForm = reactive({
  customerId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  bizDate: '',
  remark: '',
})

const createRules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择出库仓库', trigger: 'change' }],
}

function todayText(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function emptyLine(rate = 10000): LineDraft {
  return {
    skuId: undefined,
    qty: undefined,
    priceYuan: '',
    priceAuto: false,
    discountRate: rate,
  }
}

const lines = ref<LineDraft[]>([])

/** 上一次套用到明细上的客户默认折扣：换客户时只改"没被手动覆盖"的行。 */
const lastAppliedRate = ref(10000)

const selectedCustomer = computed(() =>
  customers.value.find((c) => c.id === createForm.customerId),
)
const customerDefaultRate = computed(() => selectedCustomer.value?.discountRate ?? 10000)

function openCreate() {
  Object.assign(createForm, {
    customerId: undefined,
    warehouseId: undefined,
    bizDate: todayText(),
    remark: '',
  })
  // 起始只给一行空行：给两行的话，用户填完第一行点「添加一行」会多出两行空的
  lines.value = [emptyLine()]
  lastAppliedRate.value = 10000
  createError.value = ''
  createLocation.value = ''
  createDialog.value = true
}

/**
 * 换客户：把仍是"上一个客户默认折扣"的行改成新客户的默认折扣。
 *
 * 用户手动改过的行（值不等于旧默认）保持不动 —— 明细覆盖优先于客户默认。
 */
function onCustomerChange() {
  const next = customerDefaultRate.value
  for (const line of lines.value) {
    if (line.discountRate === lastAppliedRate.value) {
      line.discountRate = next
    }
  }
  lastAppliedRate.value = next
}

/**
 * 已选中的 SKU 从下拉里去掉（§7 决策 2）。
 *
 * 同一 SKU 拆两行会让"按行出货"的语义混乱 —— 后端不会报错，
 * 但每一行的未出量要跨两行算，用户根本分不清。
 */
function skuOptionsFor(row: LineDraft) {
  const picked = new Set(
    lines.value.filter((l) => l !== row && l.skuId != null).map((l) => l.skuId as number),
  )
  return skuOptions.value.filter((s) => !picked.has(s.id))
}

/** 选 SKU 时带出零售价（仅当价格为空或仍是上次自动带入的价格）。 */
function onSkuChange(row: LineDraft) {
  const sku = skus.value.find((s) => s.id === row.skuId)
  if (!sku) return
  if (row.priceAuto || row.priceYuan.trim() === '') {
    row.priceYuan = fenToYuan(sku.retailPriceAmount)
    row.priceAuto = true
  }
}

function addLine() {
  lines.value.push(emptyLine(customerDefaultRate.value))
}

function removeLine(index: number) {
  lines.value.splice(index, 1)
  if (!lines.value.length) lines.value.push(emptyLine(customerDefaultRate.value))
}

/** 整行没填 = 用户还没用到它；部分填写 = 要提醒补全。 */
function isBlank(row: LineDraft): boolean {
  return row.skuId == null && row.qty == null && row.priceYuan.trim() === ''
}

/**
 * 行金额预览（只用于界面，§3.4）。
 *
 * 后端用 BigDecimal HALF_UP，与这里的 Math.round 在 .5 边界一致；
 * 但落库金额以后端返回的 amount 为准（ADR-0005）。
 */
function lineAmountFen(row: LineDraft): number {
  const qty = row.qty ?? 0
  if (!row.skuId || qty <= 0) return 0
  const rate = row.discountRate ?? 10000
  return Math.round((qty * yuanToFen(row.priceYuan) * rate) / 10000)
}

const previewTotal = computed(() => lines.value.reduce((sum, l) => sum + lineAmountFen(l), 0))
const previewQty = computed(() =>
  lines.value.reduce((sum, l) => sum + (l.skuId ? (l.qty ?? 0) : 0), 0),
)

/**
 * 建单失败后的辅助定位。
 *
 * 后端的 `INV_INSUFFICIENT_STOCK` 消息带 skuId / warehouseId 与差额，**没有行号**；
 * 这里把 id 翻成本单里的行号与名称，用户才知道回弹窗改哪一行。
 * 不改变消息本身，也不预判库存。
 */
function locateCreateError(details: Record<string, unknown> | undefined): string {
  const skuId = typeof details?.skuId === 'number' ? details.skuId : undefined
  if (skuId == null) return ''
  const filled = lines.value.filter((l) => !isBlank(l))
  const index = filled.findIndex((l) => l.skuId === skuId)
  const parts: string[] = []
  if (index >= 0) parts.push(`本单第 ${index + 1} 行`)
  parts.push(skuText(skuId))
  const warehouseId = typeof details?.warehouseId === 'number' ? details.warehouseId : undefined
  if (warehouseId != null) parts.push(`仓库：${warehouseText(warehouseId)}`)
  return `辅助定位：${parts.join(' · ')}`
}

async function saveOrder() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return

  const filled = lines.value.filter((l) => !isBlank(l))
  if (!filled.length) {
    createError.value = '至少填写一行明细：SKU、数量、销售单价'
    createLocation.value = ''
    return
  }
  const badIndex = filled.findIndex(
    (l) =>
      l.skuId == null ||
      !l.qty ||
      l.qty < 1 ||
      l.priceYuan.trim() === '' ||
      l.discountRate == null ||
      l.discountRate < 1 ||
      l.discountRate > 10000,
  )
  if (badIndex >= 0) {
    createError.value = `第 ${badIndex + 1} 行明细没填完：需要 SKU、数量（正整数）、销售单价与 1~10000 的折扣万分比`
    createLocation.value = ''
    return
  }
  const pickedSkuIds = filled.map((l) => l.skuId as number)
  if (new Set(pickedSkuIds).size !== pickedSkuIds.length) {
    createError.value = '同一 SKU 不能重复成两行，请合并数量后再保存'
    createLocation.value = ''
    return
  }

  const body: SalesOrderCreate = {
    customerId: createForm.customerId!,
    warehouseId: createForm.warehouseId!,
    bizDate: createForm.bizDate || undefined,
    remark: createForm.remark.trim() || undefined,
    lines: filled.map((l) => ({
      skuId: l.skuId!,
      qty: l.qty!,
      unitPriceAmount: yuanToFen(l.priceYuan),
      discountRate: l.discountRate ?? 10000,
    })),
  }

  saving.value = true
  createError.value = ''
  createLocation.value = ''
  try {
    const created = await salApi.create(body)
    ElMessage.success(
      `出货单 ${created.orderNo} 已创建（草稿）：预占 ${created.totalQty} 件，金额 ¥${fenToYuan(created.totalAmount)}`,
    )
    createDialog.value = false
    page.value = 1
    await loadOrders()
  } catch (error) {
    // 预占失败整单回滚（后端单事务）；弹窗保留内容，用户改数量/换仓后可直接再提交。
    const info = errorInfo(error)
    createError.value = info.message
    createLocation.value = locateCreateError(info.details)
  } finally {
    saving.value = false
  }
}

// ─────────────────────────── 详情抽屉 ───────────────────────────

const detailDrawer = ref(false)
const detail = ref<SalesOrder | null>(null)
const detailId = ref<number | null>(null)
const detailLoading = ref(false)
const detailError = ref('')

async function openDetail(row: SalesOrder) {
  detailId.value = row.id
  detailDrawer.value = true
  await refreshDetail(row.id)
}

async function refreshDetail(id: number) {
  detailId.value = id
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await salApi.get(id)
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
  await Promise.all([loadDictionary(), loadOrders()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">出货单</h2>
      <div>
        <el-button @click="loadOrders">刷新</el-button>
        <el-button v-if="canCreate" type="primary" @click="openCreate">新建出货单</el-button>
      </div>
    </div>
    <p class="page-hint">
      流程：建单（<b>保存即预占库存</b>）→ 提交 → 审核 → 出货。建单时任一行库存不足会整单失败，
      库存与预占都不会变化；出货按 FEFO 扣减实存；作废未出货单据会释放预占，
      已出货的作废会被拒绝（需红冲，属 M2）。
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
      :title="`出货单查询失败：${listError}`"
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
      create-text="新建第一张出货单"
      empty-text="还没有出货单"
      empty-hint="出货单保存即预占库存：建好后走提交 → 审核 → 出货，库存才会真正扣减。"
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
      <el-table-column label="客户" min-width="150">
        <template #default="{ row }">{{ customerText(row.customerId) }}</template>
      </el-table-column>
      <el-table-column label="仓库" min-width="130">
        <template #default="{ row }">{{ warehouseText(row.warehouseId) }}</template>
      </el-table-column>
      <el-table-column prop="bizDate" label="业务日期" width="120" />
      <el-table-column prop="totalQty" label="单据数量" width="100" align="right" />
      <el-table-column label="出货进度" min-width="150">
        <template #default="{ row }">
          <span v-if="progress(row)" :class="{ 'text-muted': progress(row)!.open === 0 }">
            {{ progress(row)!.text }} · 未出 {{ progress(row)!.open }}
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
      title="新建出货单"
      width="980px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        class="mb-16"
        title="保存即预占库存"
        description="保存后系统会按 FEFO 立即锁定可用库存。任一行不足则整单失败（库存与预占都不变），弹窗保持打开，改完可直接再保存。"
      />

      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="客户" prop="customerId">
          <el-select
            v-model="createForm.customerId"
            placeholder="选择客户（只列启用中的）"
            filterable
            class="full"
            @change="onCustomerChange"
          >
            <el-option
              v-for="c in customerOptions"
              :key="c.id"
              :label="`${c.name} · ${discountLabel(c.discountRate)}`"
              :value="c.id"
            />
          </el-select>
          <span v-if="createForm.customerId" class="field-hint">
            客户默认折扣 {{ discountLabel(customerDefaultRate) }}（{{ customerDefaultRate }}），明细可覆盖
          </span>
        </el-form-item>
        <el-form-item label="出库仓库" prop="warehouseId">
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
            <el-table-column label="SKU" min-width="230">
              <template #default="{ row }">
                <el-select
                  v-model="row.skuId"
                  placeholder="选择 SKU"
                  filterable
                  class="full"
                  @change="onSkuChange(row)"
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
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="1" :step="1" controls-position="right" class="full" />
              </template>
            </el-table-column>
            <el-table-column label="销售单价" width="140">
              <template #default="{ row }">
                <el-input v-model="row.priceYuan" placeholder="如 129.00" @input="row.priceAuto = false">
                  <template #append>元</template>
                </el-input>
              </template>
            </el-table-column>
            <el-table-column label="折扣（万分比）" width="190">
              <template #default="{ row }">
                <div class="discount-cell">
                  <el-input-number
                    v-model="row.discountRate"
                    :min="1"
                    :max="10000"
                    :step="100"
                    controls-position="right"
                    class="discount-input"
                  />
                  <span class="field-hint">{{ discountLabel(row.discountRate) }}</span>
                </div>
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
            折扣为整数万分比：10000 = 不打折，9000 = 9 折；默认取客户折扣，可逐行覆盖。
            同一 SKU 只能成一行：已选过的 SKU 会从后面那行的下拉里消失。
            单价默认带出零售价，可改。
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

      <el-alert v-if="createError" type="error" show-icon :closable="false" :title="createError">
        <div v-if="createLocation" class="error-location">{{ createLocation }}</div>
        <div class="error-location">保存失败时整单回滚：库存与预占均不会变化。</div>
      </el-alert>

      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveOrder">保存并预占</el-button>
      </template>
    </el-dialog>

    <!-- ─────────── 详情 ─────────── -->
    <el-drawer v-model="detailDrawer" size="70%" :with-header="true" @close="closeDetail">
      <template #header>
        <div class="drawer-header">
          <div>
            <div class="drawer-title">{{ detail?.orderNo || '出货单详情' }}</div>
            <div v-if="detail" class="drawer-sub">
              <StatusTag :status="detail.status" size="small" />
              <span>{{ customerText(detail.customerId) }}</span>
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
        <el-alert
          v-if="detailError"
          type="error"
          show-icon
          :closable="false"
          class="mb-16"
          :title="`出货单详情加载失败：${detailError}`"
        >
          <el-button v-if="detailId" size="small" @click="refreshDetail(detailId)">重试</el-button>
        </el-alert>

        <template v-if="detail">
          <div class="detail-grid">
            <div><span class="dt">单据数量</span>{{ detail.totalQty }}</div>
            <div>
              <span class="dt">金额</span>
              <span class="amount">¥ {{ fenToYuan(detail.totalAmount) }}</span>
            </div>
            <div>
              <span class="dt">出货进度</span>
              <span v-if="progress(detail)">{{ progress(detail)!.text }} · 未出 {{ progress(detail)!.open }}</span>
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
            <el-table-column label="SKU" min-width="190">
              <template #default="{ row }">{{ skuText(row.skuId) }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="单据数量" width="90" align="right" />
            <el-table-column prop="shippedQty" label="已出货" width="90" align="right" />
            <el-table-column label="未出货" width="90" align="right">
              <template #default="{ row }">
                <span :class="{ 'text-muted': !row.openQty }">{{ row.openQty }}</span>
              </template>
            </el-table-column>
            <el-table-column label="单价" width="100" align="right">
              <template #default="{ row }">
                <span class="amount">{{ fenToYuan(row.unitPriceAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="折扣" width="110" align="right">
              <template #default="{ row }">{{ discountLabel(row.discountRate) }}</template>
            </el-table-column>
            <el-table-column label="行金额" width="110" align="right">
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

.discount-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.discount-input {
  width: 110px;
}

.discount-cell .field-hint {
  margin-left: 0;
  white-space: nowrap;
}

.error-location {
  margin-top: 6px;
  font-size: 12px;
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
