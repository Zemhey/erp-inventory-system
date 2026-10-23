<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  mdmApi,
  fenToYuan,
  yuanToFen,
  type Customer,
} from '@/api/client'
import {
  arApi,
  type CustomerCredit,
  type CustomerReceipt,
  type CustomerReceivable,
  type ReceivableSummary,
} from '@/api/salesV23'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 应收 / 收款 / 信用（FR-SAL-07/08/09）。
 *
 * ★ 三个页签对应三件不同的事：
 *   1. 应收：出货 − 退货 − 回款 = 余额（后端数据库 CHECK 兜底）；
 *   2. 收款：核销明细合计必须精确等于收款金额，超额核销被拒；
 *   3. 信用：额度 0 = 不设限；敞口 = 应收余额 + 未出货订单金额。
 */

const session = useSessionStore()
const canReceipt = computed(() => session.hasPermission('sal:receipt'))
const canEditCredit = computed(() => session.hasPermission('mdm:write'))

const activeTab = ref('receivables')
const customers = ref<Customer[]>([])
const customerNames = computed(() => {
  const map = new Map<number, string>()
  for (const c of customers.value) map.set(c.id, c.name)
  return map
})
function customerText(id: number): string {
  return customerNames.value.get(id) ?? `客户#${id}`
}

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

// ─────────────────────────── 应收 ───────────────────────────

const receivables = ref<CustomerReceivable[]>([])
const receivableTotal = ref(0)
const receivablePage = ref(1)
const receivableSize = ref(20)
const arCustomerFilter = ref<number | undefined>(undefined)
const arStatusFilter = ref('')
const onlyOverdue = ref(false)
const arLoading = ref(false)
const arError = ref('')
const arFiltered = computed(
  () => arCustomerFilter.value !== undefined || arStatusFilter.value !== '' || onlyOverdue.value,
)
const summary = ref<ReceivableSummary | null>(null)

const RECEIVABLE_STATUS: Record<string, { label: string; type: 'info' | 'success' | 'danger' }> = {
  OPEN: { label: '未结清', type: 'info' },
  SETTLED: { label: '已结清', type: 'success' },
  VOIDED: { label: '已作废', type: 'danger' },
}
function receivableStatus(status: string) {
  return RECEIVABLE_STATUS[status] ?? { label: status, type: 'info' as const }
}

async function loadReceivables() {
  arLoading.value = true
  arError.value = ''
  try {
    const data = await arApi.receivables({
      customerId: arCustomerFilter.value,
      status: arStatusFilter.value || undefined,
      overdue: onlyOverdue.value ? true : undefined,
      page: receivablePage.value,
      size: receivableSize.value,
    })
    receivables.value = data.items
    receivableTotal.value = data.total
  } catch (error) {
    receivables.value = []
    receivableTotal.value = 0
    arError.value = errorMessage(error)
  } finally {
    arLoading.value = false
  }
}
function searchReceivables() {
  receivablePage.value = 1
  loadReceivables()
}
function resetReceivableFilters() {
  arCustomerFilter.value = undefined
  arStatusFilter.value = ''
  onlyOverdue.value = false
  searchReceivables()
}
function onReceivablePage(nextPage: number, nextSize: number) {
  receivablePage.value = nextPage
  receivableSize.value = nextSize
  loadReceivables()
}

async function loadSummary(customerId?: number) {
  if (customerId == null) {
    summary.value = null
    return
  }
  try {
    summary.value = await arApi.summary(customerId)
  } catch {
    summary.value = null
  }
}

// ─────────────────────────── 收款 ───────────────────────────

const receipts = ref<CustomerReceipt[]>([])
const receiptTotal = ref(0)
const receiptPage = ref(1)
const receiptSize = ref(20)
const receiptCustomerFilter = ref<number | undefined>(undefined)
const receiptLoading = ref(false)
const receiptError = ref('')
const receiptFiltered = computed(() => receiptCustomerFilter.value !== undefined)

async function loadReceipts() {
  receiptLoading.value = true
  receiptError.value = ''
  try {
    const data = await arApi.receipts({
      customerId: receiptCustomerFilter.value,
      page: receiptPage.value,
      size: receiptSize.value,
    })
    receipts.value = data.items
    receiptTotal.value = data.total
  } catch (error) {
    receipts.value = []
    receiptTotal.value = 0
    receiptError.value = errorMessage(error)
  } finally {
    receiptLoading.value = false
  }
}
function searchReceipts() {
  receiptPage.value = 1
  loadReceipts()
}
function onReceiptPage(nextPage: number, nextSize: number) {
  receiptPage.value = nextPage
  receiptSize.value = nextSize
  loadReceipts()
}

interface AllocationDraft {
  receivableId: number
  balance: number
  orderId: number
  amountYuan: string
}

const receiptDialog = ref(false)
const receiptSaving = ref(false)
const receiptFormError = ref('')
const receiptForm = reactive({
  customerId: undefined as number | undefined,
  amountYuan: '',
  bizDate: '',
  remark: '',
})
const allocationDrafts = ref<AllocationDraft[]>([])
const allocationTotalFen = computed(() =>
  allocationDrafts.value.reduce((sum, a) => sum + yuanToFen(a.amountYuan), 0),
)
const receiptAmountFen = computed(() => yuanToFen(receiptForm.amountYuan))

function todayText(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

async function openReceipt() {
  receiptForm.customerId = receiptCustomerFilter.value
  receiptForm.amountYuan = ''
  receiptForm.bizDate = todayText()
  receiptForm.remark = ''
  allocationDrafts.value = []
  receiptFormError.value = ''
  receiptDialog.value = true
  if (receiptForm.customerId != null) await loadOpenReceivables(receiptForm.customerId)
}

async function loadOpenReceivables(customerId: number) {
  try {
    const data = await arApi.receivables({ customerId, status: 'OPEN', size: 100 })
    allocationDrafts.value = data.items.map((r) => ({
      receivableId: r.id,
      balance: r.balance,
      orderId: r.salesOrderId,
      amountYuan: '',
    }))
  } catch (error) {
    receiptFormError.value = errorMessage(error)
    allocationDrafts.value = []
  }
}

async function onReceiptCustomerChange() {
  if (receiptForm.customerId != null) await loadOpenReceivables(receiptForm.customerId)
}

/** 按收款金额从最早的应收开始自动分配（用户仍可改）。 */
function autoAllocate() {
  let remaining = receiptAmountFen.value
  for (const draft of allocationDrafts.value) {
    const take = Math.min(remaining, draft.balance)
    draft.amountYuan = take > 0 ? fenToYuan(take) : ''
    remaining -= take
    if (remaining <= 0) break
  }
}

async function saveReceipt() {
  receiptFormError.value = ''
  if (receiptForm.customerId == null) {
    receiptFormError.value = '请选择客户'
    return
  }
  if (receiptAmountFen.value <= 0) {
    receiptFormError.value = '收款金额必须大于 0'
    return
  }
  const allocations = allocationDrafts.value
    .map((a) => ({ receivableId: a.receivableId, amount: yuanToFen(a.amountYuan) }))
    .filter((a) => a.amount > 0)
  if (!allocations.length) {
    receiptFormError.value = '至少填写一条核销金额'
    return
  }
  if (allocationTotalFen.value !== receiptAmountFen.value) {
    receiptFormError.value = `核销合计 ¥${fenToYuan(allocationTotalFen.value)} 必须精确等于收款金额 ¥${fenToYuan(receiptAmountFen.value)}`
    return
  }
  receiptSaving.value = true
  try {
    const receipt = await arApi.createReceipt({
      customerId: receiptForm.customerId,
      amount: receiptAmountFen.value,
      bizDate: receiptForm.bizDate || undefined,
      remark: receiptForm.remark.trim() || undefined,
      allocations,
    })
    ElMessage.success(`收款单 ${receipt.receiptNo} 已核销 ¥${fenToYuan(receipt.amount)}`)
    receiptDialog.value = false
    await Promise.all([
      loadReceivables(),
      loadReceipts(),
      loadSummary(receiptForm.customerId),
      loadCredit(receiptForm.customerId),
    ])
  } catch (error) {
    receiptFormError.value = errorMessage(error)
  } finally {
    receiptSaving.value = false
  }
}

// ─────────────────────────── 信用 ───────────────────────────

const creditCustomerId = ref<number | undefined>(undefined)
const credit = ref<CustomerCredit | null>(null)
const creditError = ref('')
const creditSaving = ref(false)
const creditForm = reactive({ creditLimitYuan: '0', creditDays: 0 })

async function loadCredit(customerId?: number) {
  const id = customerId ?? creditCustomerId.value
  if (id == null) {
    credit.value = null
    return
  }
  creditCustomerId.value = id
  creditError.value = ''
  try {
    const view = await arApi.credit(id)
    credit.value = view
    creditForm.creditLimitYuan = fenToYuan(view.creditLimit)
    creditForm.creditDays = view.creditDays
  } catch (error) {
    credit.value = null
    creditError.value = errorMessage(error)
  }
}

async function saveCredit() {
  if (creditCustomerId.value == null) return
  creditSaving.value = true
  creditError.value = ''
  try {
    const view = await arApi.updateCredit(creditCustomerId.value, {
      creditLimit: yuanToFen(creditForm.creditLimitYuan),
      creditDays: creditForm.creditDays,
    })
    credit.value = view
    ElMessage.success('信用额度已更新')
  } catch (error) {
    creditError.value = errorMessage(error)
  } finally {
    creditSaving.value = false
  }
}

function customerCreditText(customer: Customer): string {
  return customer.creditLimit === 0 ? '不设限' : `¥${fenToYuan(customer.creditLimit)} / ${customer.creditDays} 天`
}

// ─────────────────────────── 初始化 ───────────────────────────

onMounted(async () => {
  try {
    const page = await mdmApi.listCustomers({ page: 1, size: 200 })
    customers.value = page.items
  } catch {
    customers.value = []
  }
  await Promise.all([loadReceivables(), loadReceipts()])
  const first = customers.value[0]
  if (first) {
    arCustomerFilter.value = first.id
    receiptCustomerFilter.value = first.id
    await Promise.all([
      loadSummary(first.id),
      loadCredit(first.id),
      loadReceivables(),
      loadReceipts(),
    ])
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">应收 / 收款 / 信用</h2>
      <div>
        <el-button @click="loadReceivables(); loadReceipts()">刷新</el-button>
        <el-button v-if="canReceipt" type="primary" @click="openReceipt">登记收款</el-button>
      </div>
    </div>
    <p class="page-hint">
      应收余额恒等于 <b>出货 − 退货 − 回款</b>。收款必须完整核销（核销明细合计 == 收款金额），
      单笔核销不得超过应收余额；信用额度为 0 表示不设限。
    </p>

    <el-tabs v-model="activeTab" class="ar-tabs">
      <!-- 应收 -->
      <el-tab-pane label="应收" name="receivables">
        <div v-if="summary" class="summary-cards">
          <div class="summary-card"><div class="sc-label">出货</div><div class="sc-value">¥{{ fenToYuan(summary.shippedAmount) }}</div></div>
          <div class="summary-card"><div class="sc-label">退货</div><div class="sc-value">¥{{ fenToYuan(summary.returnedAmount) }}</div></div>
          <div class="summary-card"><div class="sc-label">已回款</div><div class="sc-value">¥{{ fenToYuan(summary.receivedAmount) }}</div></div>
          <div class="summary-card"><div class="sc-label">应收余额</div><div class="sc-value strong">¥{{ fenToYuan(summary.balance) }}</div></div>
          <div class="summary-card"><div class="sc-label">逾期</div><div class="sc-value danger">¥{{ fenToYuan(summary.overdueAmount) }}</div></div>
        </div>

        <el-alert v-if="arError" type="error" show-icon :closable="false" class="mb-16" :title="`应收查询失败：${arError}`">
          <el-button size="small" @click="loadReceivables">重试</el-button>
        </el-alert>

        <PageTable
          :rows="receivables"
          :loading="arLoading"
          :total="receivableTotal"
          :page="receivablePage"
          :page-size="receivableSize"
          :filtered="arFiltered"
          :load-failed="!!arError"
          empty-text="还没有应收记录"
          empty-hint="出货确认后才会生成应收。"
          @update:page="receivablePage = $event"
          @update:page-size="receivableSize = $event"
          @change="onReceivablePage"
        >
          <template #filters>
            <el-select v-model="arCustomerFilter" placeholder="全部客户" filterable clearable class="filter-select" @change="loadSummary(arCustomerFilter); searchReceivables()">
              <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-select v-model="arStatusFilter" placeholder="全部状态" clearable class="filter-select-sm" @change="searchReceivables">
              <el-option label="未结清" value="OPEN" />
              <el-option label="已结清" value="SETTLED" />
              <el-option label="已作废" value="VOIDED" />
            </el-select>
            <el-checkbox v-model="onlyOverdue" @change="searchReceivables">只看逾期</el-checkbox>
            <el-button type="primary" @click="searchReceivables">查询</el-button>
            <el-button @click="resetReceivableFilters">重置</el-button>
          </template>

          <el-table-column label="客户" min-width="150">
            <template #default="{ row }">{{ customerText(row.customerId) }}</template>
          </el-table-column>
          <el-table-column label="出货单" width="100">
            <template #default="{ row }">#{{ row.salesOrderId }}</template>
          </el-table-column>
          <el-table-column prop="bizDate" label="业务日期" width="120" />
          <el-table-column label="到期日" width="120">
            <template #default="{ row }">{{ row.dueDate || '-' }}</template>
          </el-table-column>
          <el-table-column label="出货" width="110" align="right">
            <template #default="{ row }">¥{{ fenToYuan(row.shippedAmount) }}</template>
          </el-table-column>
          <el-table-column label="退货" width="110" align="right">
            <template #default="{ row }">¥{{ fenToYuan(row.returnedAmount) }}</template>
          </el-table-column>
          <el-table-column label="回款" width="110" align="right">
            <template #default="{ row }">¥{{ fenToYuan(row.receivedAmount) }}</template>
          </el-table-column>
          <el-table-column label="余额" width="110" align="right">
            <template #default="{ row }"><span class="amount">¥{{ fenToYuan(row.balance) }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="receivableStatus(row.status).type" size="small" disable-transitions>
                {{ receivableStatus(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="逾期" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.overdue" type="danger" size="small" disable-transitions>逾期</el-tag>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
        </PageTable>
      </el-tab-pane>

      <!-- 收款 -->
      <el-tab-pane label="收款" name="receipts">
        <el-alert v-if="receiptError" type="error" show-icon :closable="false" class="mb-16" :title="`收款查询失败：${receiptError}`">
          <el-button size="small" @click="loadReceipts">重试</el-button>
        </el-alert>
        <PageTable
          :rows="receipts"
          :loading="receiptLoading"
          :total="receiptTotal"
          :page="receiptPage"
          :page-size="receiptSize"
          :filtered="receiptFiltered"
          :load-failed="!!receiptError"
          empty-text="还没有收款单"
          empty-hint="点右上角「登记收款」并按应收余额核销。"
          @update:page="receiptPage = $event"
          @update:page-size="receiptSize = $event"
          @change="onReceiptPage"
        >
          <template #filters>
            <el-select v-model="receiptCustomerFilter" placeholder="全部客户" filterable clearable class="filter-select" @change="searchReceipts">
              <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-button type="primary" @click="searchReceipts">查询</el-button>
          </template>

          <el-table-column prop="receiptNo" label="收款单号" min-width="170" />
          <el-table-column label="客户" min-width="150">
            <template #default="{ row }">{{ customerText(row.customerId) }}</template>
          </el-table-column>
          <el-table-column prop="bizDate" label="收款日期" width="120" />
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }"><span class="amount">¥{{ fenToYuan(row.amount) }}</span></template>
          </el-table-column>
          <el-table-column label="核销明细" min-width="220">
            <template #default="{ row }">
              <span v-for="a in row.allocations" :key="a.id" class="alloc-chip">
                应收#{{ a.receivableId }} ¥{{ fenToYuan(a.amount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="140" />
        </PageTable>
      </el-tab-pane>

      <!-- 信用 -->
      <el-tab-pane label="信用" name="credit">
        <el-card shadow="never" class="credit-card">
          <el-form label-width="120px" class="credit-form">
            <el-form-item label="客户">
              <el-select v-model="creditCustomerId" placeholder="选择客户" filterable class="w-280" @change="loadCredit($event)">
                <el-option
                  v-for="c in customers"
                  :key="c.id"
                  :label="`${c.name}（${customerCreditText(c)}）`"
                  :value="c.id"
                />
              </el-select>
            </el-form-item>
          </el-form>

          <el-alert v-if="creditError" type="error" show-icon :closable="false" class="mb-16" :title="creditError" />

          <template v-if="credit">
            <div class="summary-cards">
              <div class="summary-card"><div class="sc-label">信用额度</div><div class="sc-value">{{ credit.creditLimit === 0 ? '不设限' : `¥${fenToYuan(credit.creditLimit)}` }}</div></div>
              <div class="summary-card"><div class="sc-label">当前敞口</div><div class="sc-value">¥{{ fenToYuan(credit.exposureAmount) }}</div></div>
              <div class="summary-card"><div class="sc-label">可用额度</div><div class="sc-value strong">{{ credit.availableCredit < 0 ? '不设限' : `¥${fenToYuan(credit.availableCredit)}` }}</div></div>
              <div class="summary-card"><div class="sc-label">逾期金额</div><div class="sc-value danger">¥{{ fenToYuan(credit.overdueAmount) }}</div></div>
            </div>

            <el-form label-width="120px" class="credit-form">
              <el-form-item label="信用额度（元）">
                <el-input v-model="creditForm.creditLimitYuan" class="w-200">
                  <template #append>元</template>
                </el-input>
                <span class="field-hint">0 = 不设限；大于 0 时建单前校验敞口</span>
              </el-form-item>
              <el-form-item label="账期（天）">
                <el-input-number v-model="creditForm.creditDays" :min="0" controls-position="right" />
                <span class="field-hint">应收到期日 = 出货时今天 + 账期</span>
              </el-form-item>
              <el-form-item v-if="canEditCredit">
                <el-button type="primary" :loading="creditSaving" @click="saveCredit">保存信用设置</el-button>
              </el-form-item>
            </el-form>
          </template>
          <el-empty v-else description="选择客户查看信用" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 登记收款 -->
    <el-dialog v-model="receiptDialog" title="登记收款并核销" width="780px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        class="mb-16"
        title="核销必须守恒"
        description="核销明细合计必须精确等于收款金额；单笔不得超过应收余额。少收是部分收款，多核会被拒绝。"
      />
      <el-form label-width="100px">
        <el-form-item label="客户">
          <el-select v-model="receiptForm.customerId" placeholder="选择客户" filterable class="full" @change="onReceiptCustomerChange">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="收款金额">
          <el-input v-model="receiptForm.amountYuan" class="w-200" placeholder="如 3000.00">
            <template #append>元</template>
          </el-input>
          <el-button class="ml-8" size="small" @click="autoAllocate">按金额自动分配</el-button>
        </el-form-item>
        <el-form-item label="收款日期">
          <el-date-picker v-model="receiptForm.bizDate" type="date" value-format="YYYY-MM-DD" class="w-200" />
        </el-form-item>
        <el-form-item label="核销明细">
          <el-table :data="allocationDrafts" size="small" border class="full">
            <el-table-column label="应收" min-width="130">
              <template #default="{ row }">出货单#{{ row.orderId }}</template>
            </el-table-column>
            <el-table-column label="应收余额" width="130" align="right">
              <template #default="{ row }">¥{{ fenToYuan(row.balance) }}</template>
            </el-table-column>
            <el-table-column label="本次核销（元）" width="180">
              <template #default="{ row }">
                <el-input v-model="row.amountYuan" placeholder="0.00" />
              </template>
            </el-table-column>
          </el-table>
          <div class="alloc-total">
            核销合计 <span class="amount">¥{{ fenToYuan(allocationTotalFen) }}</span>
            · 收款金额 <span class="amount">¥{{ fenToYuan(receiptAmountFen) }}</span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="receiptForm.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert v-if="receiptFormError" type="error" show-icon :closable="false" :title="receiptFormError" />
      <template #footer>
        <el-button @click="receiptDialog = false">取消</el-button>
        <el-button type="primary" :loading="receiptSaving" @click="saveReceipt">确认收款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ar-tabs {
  margin-top: 4px;
}
.filter-select {
  width: 220px;
}
.filter-select-sm {
  width: 140px;
}
.full {
  width: 100%;
}
.w-200 {
  width: 200px;
}
.w-280 {
  width: 280px;
}
.ml-8 {
  margin-left: 8px;
}
.mb-16 {
  margin-bottom: 16px;
}
.field-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}
.summary-cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.summary-card {
  padding: 12px 14px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
}
.sc-label {
  font-size: 12px;
  color: #909399;
}
.sc-value {
  margin-top: 6px;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}
.sc-value.strong {
  color: #409eff;
}
.sc-value.danger {
  color: #f56c6c;
}
.credit-card {
  border-radius: 4px;
}
.credit-form {
  max-width: 640px;
}
.alloc-chip {
  display: inline-block;
  margin: 2px 8px 2px 0;
  padding: 2px 6px;
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 3px;
}
.alloc-total {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}
@media (max-width: 768px) {
  .filter-select,
  .filter-select-sm,
  .w-200,
  .w-280 {
    width: 100%;
  }
  .summary-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
