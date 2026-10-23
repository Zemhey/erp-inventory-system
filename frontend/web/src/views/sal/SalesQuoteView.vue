<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  discountLabel,
  fenToYuan,
  yuanToFen,
  type Customer,
  type Product,
  type Sku,
  type Warehouse,
} from '@/api/client'
import { quoteApi, type SalesQuote, type SalesQuoteCreate } from '@/api/salesV23'
import PageTable from '@/components/PageTable.vue'
import StatusTag from '@/components/StatusTag.vue'
import { DOCUMENT_STATUSES, statusMeta } from '@/utils/documentStatus'
import { useSessionStore } from '@/stores/session'

/**
 * 报价单（FR-SAL-05）。
 *
 * ★ 报价不等于出货：保存报价不占库存、不进应收；
 *   只有「转化」才走建单预占与信用校验。转化失败（库存不足/信用超额）时
 *   报价单保持已审核，用户改完条件可再次转化。
 */

const session = useSessionStore()
const canQuote = computed(() => session.hasPermission('sal:quote'))

const quotes = ref<SalesQuote[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const customerFilter = ref<number | undefined>(undefined)
const loading = ref(false)
const listError = ref('')

const statusOptions = DOCUMENT_STATUSES.map((value) => ({
  value,
  label: statusMeta(value).label,
}))
const filtered = computed(() => statusFilter.value !== '' || customerFilter.value !== undefined)

const customers = ref<Customer[]>([])
const warehouses = ref<Warehouse[]>([])
const skus = ref<Sku[]>([])
const products = ref<Product[]>([])
const dictTruncated = ref(false)

const customerOptions = computed(() => customers.value.filter((c) => c.enabled))
const skuOptions = computed(() => skus.value.filter((s) => s.enabled))

const customerNames = computed(() => {
  const map = new Map<number, string>()
  for (const c of customers.value) map.set(c.id, c.enabled ? c.name : `${c.name}（已停用）`)
  return map
})
const skuNames = computed(() => {
  const productNames = new Map(products.value.map((p) => [p.id, p.name]))
  const map = new Map<number, string>()
  for (const s of skus.value) {
    map.set(s.id, `${productNames.get(s.productId) ?? `商品#${s.productId}`} · ${s.color}-${s.size}`)
  }
  return map
})

function customerText(id: number): string {
  return customerNames.value.get(id) ?? `客户#${id}`
}
function skuText(id: number): string {
  return skuNames.value.get(id) ?? `SKU#${id}`
}

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

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
    (sku.status === 'fulfilled' && sku.value.total > sku.value.items.length)
}

async function loadQuotes() {
  loading.value = true
  listError.value = ''
  try {
    const data = await quoteApi.list({
      status: statusFilter.value || undefined,
      customerId: customerFilter.value,
      page: page.value,
      size: pageSize.value,
    })
    quotes.value = data.items
    total.value = data.total
  } catch (error) {
    quotes.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  loadQuotes()
}
function resetFilters() {
  statusFilter.value = ''
  customerFilter.value = undefined
  search()
}
function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  loadQuotes()
}

// ─────────────────────────── 建报价 ───────────────────────────

interface LineDraft {
  skuId: number | undefined
  qty: number | undefined
  priceYuan: string
  discountRate: number | undefined
}

const createDialog = ref(false)
const createFormRef = ref<FormInstance>()
const saving = ref(false)
const createError = ref('')
const createForm = reactive({
  customerId: undefined as number | undefined,
  bizDate: '',
  validUntil: '',
  remark: '',
})
const lines = ref<LineDraft[]>([emptyLine()])

const createRules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
}

function todayText(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
function emptyLine(): LineDraft {
  return { skuId: undefined, qty: undefined, priceYuan: '', discountRate: 10000 }
}

function openCreate() {
  Object.assign(createForm, {
    customerId: undefined,
    bizDate: todayText(),
    validUntil: todayText(30),
    remark: '',
  })
  lines.value = [emptyLine()]
  createError.value = ''
  createDialog.value = true
}
function onSkuChange(row: LineDraft) {
  const sku = skus.value.find((s) => s.id === row.skuId)
  if (sku && row.priceYuan.trim() === '') row.priceYuan = fenToYuan(sku.retailPriceAmount)
}
function addLine() {
  lines.value.push(emptyLine())
}
function removeLine(index: number) {
  lines.value.splice(index, 1)
  if (!lines.value.length) lines.value.push(emptyLine())
}
function isBlank(row: LineDraft): boolean {
  return row.skuId == null && row.qty == null && row.priceYuan.trim() === ''
}
const previewTotal = computed(() =>
  lines.value.reduce((sum, l) => {
    if (!l.skuId || !l.qty || l.qty < 1) return sum
    const rate = l.discountRate ?? 10000
    return sum + Math.round((l.qty * yuanToFen(l.priceYuan) * rate) / 10000)
  }, 0),
)

async function saveQuote() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  const filled = lines.value.filter((l) => !isBlank(l))
  if (!filled.length) {
    createError.value = '至少填写一行明细：SKU、数量、单价'
    return
  }
  const bad = filled.findIndex(
    (l) => l.skuId == null || !l.qty || l.qty < 1 || l.priceYuan.trim() === '',
  )
  if (bad >= 0) {
    createError.value = `第 ${bad + 1} 行没填完：需要 SKU、正整数数量与单价`
    return
  }
  const body: SalesQuoteCreate = {
    customerId: createForm.customerId!,
    bizDate: createForm.bizDate || undefined,
    validUntil: createForm.validUntil || undefined,
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
  try {
    const quote = await quoteApi.create(body)
    ElMessage.success(`报价单 ${quote.quoteNo} 已创建（草稿，不占库存）`)
    createDialog.value = false
    page.value = 1
    await loadQuotes()
  } catch (error) {
    createError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

// ─────────────────────────── 动作 ───────────────────────────

type QuoteActionName = 'submit' | 'approve' | 'reject' | 'void' | 'convert'
interface QuoteAction {
  action: QuoteActionName
  label: string
  type: 'primary' | 'success' | 'warning' | 'danger'
  permission: string
}

function actionsFor(row: SalesQuote): QuoteAction[] {
  if (!canQuote.value) return []
  const actions: QuoteAction[] = []
  if (row.status === 'DRAFT') {
    actions.push({ action: 'submit', label: '提交', type: 'primary', permission: 'sal:quote' })
    actions.push({ action: 'void', label: '作废', type: 'danger', permission: 'sal:quote' })
  } else if (row.status === 'SUBMITTED') {
    actions.push({ action: 'approve', label: '审核通过', type: 'success', permission: 'sal:quote' })
    actions.push({ action: 'reject', label: '驳回', type: 'warning', permission: 'sal:quote' })
    actions.push({ action: 'void', label: '作废', type: 'danger', permission: 'sal:quote' })
  } else if (row.status === 'APPROVED') {
    actions.push({ action: 'convert', label: '转化为出货单', type: 'primary', permission: 'sal:quote' })
    actions.push({ action: 'void', label: '作废', type: 'danger', permission: 'sal:quote' })
  } else if (row.status === 'REJECTED') {
    actions.push({ action: 'void', label: '作废', type: 'danger', permission: 'sal:quote' })
  }
  return actions.filter((a) => session.hasPermission(a.permission))
}

const pendingKey = ref('')
function isPending(id: number, action: QuoteActionName): boolean {
  return pendingKey.value === `${id}:${action}`
}

async function runAction(row: SalesQuote, meta: QuoteAction) {
  if (meta.action === 'convert') {
    openConvert(row)
    return
  }
  if (meta.action === 'void' || meta.action === 'reject') {
    try {
      await ElMessageBox.confirm(
        meta.action === 'void'
          ? '作废是终态。报价单作废后不能再转化，确定吗？'
          : '驳回后报价单只能作废，确定驳回吗？',
        meta.label,
        { type: 'warning', confirmButtonText: meta.label, cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  pendingKey.value = `${row.id}:${meta.action}`
  try {
    const runner =
      meta.action === 'submit' ? quoteApi.submit
        : meta.action === 'approve' ? quoteApi.approve
          : meta.action === 'reject' ? quoteApi.reject
            : quoteApi.voidOrder
    const updated = await runner(row.id)
    ElMessage.success(`${meta.label}成功：${updated.quoteNo}`)
    await loadQuotes()
  } catch {
    await loadQuotes()
  } finally {
    pendingKey.value = ''
  }
}

// ─────────────────────────── 转化 ───────────────────────────

const convertDialog = ref(false)
const convertTarget = ref<SalesQuote | null>(null)
const convertMode = ref<'AUTO' | 'WAREHOUSE'>('AUTO')
const convertWarehouse = ref<number | undefined>(undefined)
const convertError = ref('')
const converting = ref(false)

function openConvert(row: SalesQuote) {
  convertTarget.value = row
  convertMode.value = 'AUTO'
  convertWarehouse.value = undefined
  convertError.value = ''
  convertDialog.value = true
}

async function doConvert() {
  if (!convertTarget.value) return
  if (convertMode.value === 'WAREHOUSE' && convertWarehouse.value == null) {
    convertError.value = '请选择出库仓库'
    return
  }
  converting.value = true
  convertError.value = ''
  try {
    const order = await quoteApi.convert(convertTarget.value.id, {
      warehouseId: convertMode.value === 'WAREHOUSE' ? convertWarehouse.value : undefined,
    })
    ElMessage.success(
      `已转化为出货单 ${order.orderNo}（已预占库存，金额 ¥${fenToYuan(order.totalAmount)}）`,
    )
    convertDialog.value = false
    await loadQuotes()
  } catch (error) {
    convertError.value = errorMessage(error)
  } finally {
    converting.value = false
  }
}

// ─────────────────────────── 详情 ───────────────────────────

const detail = ref<SalesQuote | null>(null)
const detailDrawer = ref(false)
async function openDetail(row: SalesQuote) {
  detail.value = await quoteApi.get(row.id)
  detailDrawer.value = true
}

onMounted(async () => {
  await Promise.all([loadDictionary(), loadQuotes()])
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">报价单</h2>
      <div>
        <el-button @click="loadQuotes">刷新</el-button>
        <el-button v-if="canQuote" type="primary" @click="openCreate">新建报价单</el-button>
      </div>
    </div>
    <p class="page-hint">
      报价单只记录价格与有效期，<b>不占库存、不进应收</b>；审核通过后「转化为出货单」才会建单预占，
      并同时受信用额度与库存约束。转化失败时报价单保持已审核，可调整后再试。
    </p>

    <el-alert
      v-if="dictTruncated"
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="部分客户 / SKU 未能显示名称（字典每页最多 200 条）"
    />
    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`报价单查询失败：${listError}`"
    >
      <el-button size="small" @click="loadQuotes">重试</el-button>
    </el-alert>

    <PageTable
      :rows="quotes"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!listError"
      :can-create="canQuote"
      create-text="新建第一张报价单"
      empty-text="还没有报价单"
      empty-hint="报价单审核通过后可以一键转化为出货单。"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
      @create="openCreate"
    >
      <template #filters>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable class="filter-select-sm" @change="search">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="customerFilter" placeholder="全部客户" filterable clearable class="filter-select" @change="search">
          <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <el-table-column prop="quoteNo" label="报价单号" min-width="170" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <StatusTag :status="row.status" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="客户" min-width="150">
        <template #default="{ row }">{{ customerText(row.customerId) }}</template>
      </el-table-column>
      <el-table-column prop="bizDate" label="报价日期" width="120" />
      <el-table-column label="有效期至" width="120">
        <template #default="{ row }">{{ row.validUntil || '不限' }}</template>
      </el-table-column>
      <el-table-column prop="totalQty" label="数量" width="90" align="right" />
      <el-table-column label="金额" width="120" align="right">
        <template #default="{ row }"><span class="amount">¥ {{ fenToYuan(row.totalAmount) }}</span></template>
      </el-table-column>
      <el-table-column label="转化订单" width="110">
        <template #default="{ row }">
          <span v-if="row.convertedOrderId">订单#{{ row.convertedOrderId }}</span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button
            v-for="a in actionsFor(row)"
            :key="a.action"
            link
            :type="a.type"
            :loading="isPending(row.id, a.action)"
            :disabled="pendingKey !== '' && !isPending(row.id, a.action)"
            @click="runAction(row, a)"
          >
            {{ a.label }}
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <!-- 新建 -->
    <el-dialog v-model="createDialog" title="新建报价单" width="980px" :close-on-click-modal="false">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="mb-16"
        title="报价不占库存"
        description="本页只记录报价；只有转化为出货单时才会预占库存并校验信用额度。"
      />
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="客户" prop="customerId">
          <el-select v-model="createForm.customerId" placeholder="选择客户（只列启用中的）" filterable class="full">
            <el-option v-for="c in customerOptions" :key="c.id" :label="`${c.name} · ${discountLabel(c.discountRate)}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="报价日期">
          <el-date-picker v-model="createForm.bizDate" type="date" value-format="YYYY-MM-DD" class="w-200" />
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="createForm.validUntil" type="date" value-format="YYYY-MM-DD" class="w-200" />
          <span class="field-hint">留空表示不过期；过期后不能转化</span>
        </el-form-item>
        <el-form-item label="明细">
          <el-table :data="lines" size="small" border class="lines-table">
            <el-table-column label="SKU" min-width="230">
              <template #default="{ row }">
                <el-select v-model="row.skuId" placeholder="选择 SKU" filterable class="full" @change="onSkuChange(row)">
                  <el-option v-for="s in skuOptions" :key="s.id" :label="`${skuText(s.id)} · 零售 ¥${fenToYuan(s.retailPriceAmount)}`" :value="s.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="1" controls-position="right" class="full" />
              </template>
            </el-table-column>
            <el-table-column label="单价" width="140">
              <template #default="{ row }">
                <el-input v-model="row.priceYuan" placeholder="如 129.00"><template #append>元</template></el-input>
              </template>
            </el-table-column>
            <el-table-column label="折扣（万分比）" width="180">
              <template #default="{ row }">
                <el-input-number v-model="row.discountRate" :min="1" :max="10000" :step="100" controls-position="right" class="full" />
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
            <span class="lines-total">预览合计 <span class="amount">¥ {{ fenToYuan(previewTotal) }}</span></span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert v-if="createError" type="error" show-icon :closable="false" :title="createError" />
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveQuote">保存报价</el-button>
      </template>
    </el-dialog>

    <!-- 转化 -->
    <el-dialog v-model="convertDialog" title="转化为出货单" width="560px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        class="mb-16"
        title="转化会立即预占库存"
        description="库存不足或信用额度不足时整单回滚，报价单保持已审核且不会产生任何预占。"
      />
      <el-form label-width="110px">
        <el-form-item label="选仓方式">
          <el-radio-group v-model="convertMode">
            <el-radio value="AUTO">自动选仓（按优先级，单仓不足拆单）</el-radio>
            <el-radio value="WAREHOUSE">指定仓库</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="convertMode === 'WAREHOUSE'" label="出库仓库">
          <el-select v-model="convertWarehouse" placeholder="选择仓库" class="full">
            <el-option v-for="w in warehouses.filter((w) => w.enabled)" :key="w.id" :label="`${w.name}（优先级 ${w.priority}）`" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="convertTarget" label="报价单">
          <span>{{ convertTarget.quoteNo }} · {{ convertTarget.totalQty }} 件 · ¥{{ fenToYuan(convertTarget.totalAmount) }}</span>
        </el-form-item>
      </el-form>
      <el-alert v-if="convertError" type="error" show-icon :closable="false" :title="convertError" />
      <template #footer>
        <el-button @click="convertDialog = false">取消</el-button>
        <el-button type="primary" :loading="converting" @click="doConvert">确认转化</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-drawer v-model="detailDrawer" size="60%">
      <template #header>
        <div class="drawer-title">{{ detail?.quoteNo || '报价单详情' }}</div>
      </template>
      <template v-if="detail">
        <div class="detail-grid">
          <div><span class="dt">状态</span><StatusTag :status="detail.status" size="small" /></div>
          <div><span class="dt">客户</span>{{ customerText(detail.customerId) }}</div>
          <div><span class="dt">报价日期</span>{{ detail.bizDate }}</div>
          <div><span class="dt">有效期至</span>{{ detail.validUntil || '不限' }}</div>
          <div><span class="dt">数量</span>{{ detail.totalQty }}</div>
          <div><span class="dt">金额</span>¥ {{ fenToYuan(detail.totalAmount) }}</div>
          <div><span class="dt">转化订单</span>{{ detail.convertedOrderId ? `#${detail.convertedOrderId}` : '-' }}</div>
          <div class="span-2"><span class="dt">备注</span>{{ detail.remark || '-' }}</div>
        </div>
        <el-table :data="detail.lines" size="small" border>
          <el-table-column prop="lineNo" label="行号" width="70" />
          <el-table-column label="SKU" min-width="190">
            <template #default="{ row }">{{ skuText(row.skuId) }}</template>
          </el-table-column>
          <el-table-column prop="qty" label="数量" width="90" align="right" />
          <el-table-column label="单价" width="110" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.unitPriceAmount) }}</template>
          </el-table-column>
          <el-table-column label="折扣" width="100">
            <template #default="{ row }">{{ discountLabel(row.discountRate) }}</template>
          </el-table-column>
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">¥ {{ fenToYuan(row.amount) }}</template>
          </el-table-column>
        </el-table>
      </template>
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
