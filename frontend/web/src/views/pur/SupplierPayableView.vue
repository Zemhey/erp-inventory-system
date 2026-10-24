<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  mdmApi,
  payableApi,
  fenToYuan,
  yuanToFen,
  type PayableReconcile,
  type Supplier,
  type SupplierPayable,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 供应商应付与付款核销（FR-PUR-07 / FR-PUR-08）。
 *
 * 设计要点：
 * 1. 余额只展示后端 `balance`（数据库生成列），前端不自己减 ——
 *    否则页面与账本会出现两套口径。
 * 2. 付款金额与核销金额都从"元"输入转"分"提交，金额一律整数分（ADR-0005）。
 * 3. "余额核对"每次点击都重新聚合，两次结果不一致时直接显示后端错误，
 *    不把旧卡片当成本次结果。
 */

const session = useSessionStore()
const canPay = computed(() => session.hasPermission('pur:pay'))

const rows = ref<SupplierPayable[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const supplierFilter = ref<number | undefined>(undefined)
const statusFilter = ref('')
const loading = ref(false)
const loadError = ref('')
const filtered = computed(
  () => statusFilter.value !== '' || supplierFilter.value !== undefined,
)

const suppliers = ref<Supplier[]>([])
const supplierNames = computed(() => {
  const map = new Map<number, string>()
  for (const s of suppliers.value) map.set(s.id, s.enabled ? s.name : `${s.name}（已停用）`)
  return map
})

const reconcile = ref<PayableReconcile | null>(null)
const reconcileLoading = ref(false)
const reconcileError = ref('')

const STATUS_LABELS: Record<string, { label: string; type: 'info' | 'warning' | 'success' }> = {
  OPEN: { label: '未付款', type: 'info' },
  PARTIAL: { label: '部分核销', type: 'warning' },
  SETTLED: { label: '已结清', type: 'success' },
}

function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadPayables() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await payableApi.list({
      supplierId: supplierFilter.value,
      status: statusFilter.value || undefined,
      page: page.value,
      size: pageSize.value,
    })
    rows.value = data.items
    total.value = data.total
  } catch (error) {
    rows.value = []
    total.value = 0
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function loadReconcile() {
  reconcileLoading.value = true
  reconcileError.value = ''
  try {
    reconcile.value = await payableApi.reconcile(supplierFilter.value)
  } catch (error) {
    reconcile.value = null
    reconcileError.value = errorMessage(error)
  } finally {
    reconcileLoading.value = false
  }
}

async function loadSuppliers() {
  try {
    suppliers.value = (await mdmApi.listSuppliers({ page: 1, size: 200 })).items
  } catch {
    suppliers.value = []
  }
}

function search() {
  page.value = 1
  loadPayables()
  loadReconcile()
}

function resetFilters() {
  supplierFilter.value = undefined
  statusFilter.value = ''
  search()
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  loadPayables()
}

function supplierText(id: number): string {
  return supplierNames.value.get(id) ?? `供应商#${id}`
}

function statusMeta(status: string) {
  return STATUS_LABELS[status] ?? { label: status, type: 'info' as const }
}

// ─────────────────────────── 付款 ───────────────────────────

const payOpen = ref(false)
const paying = ref(false)
const payRow = ref<SupplierPayable | null>(null)
const payForm = reactive({ amountYuan: '', remark: '' })

function openPay(row: SupplierPayable) {
  payRow.value = row
  payForm.amountYuan = fenToYuan(row.balance)
  payForm.remark = ''
  payOpen.value = true
}

async function submitPay() {
  const row = payRow.value
  if (!row) return
  const amount = yuanToFen(payForm.amountYuan)
  if (amount <= 0) {
    ElMessage.warning('付款金额必须大于 0')
    return
  }
  if (amount > row.balance) {
    ElMessage.warning(`付款金额不能超过应付余额 ${fenToYuan(row.balance)} 元`)
    return
  }
  paying.value = true
  try {
    const result = await payableApi.pay({
      supplierId: row.supplierId,
      amount,
      remark: payForm.remark || undefined,
      allocations: [{ payableId: row.id, amount }],
    })
    ElMessage.success(`付款成功：${result.paymentNo}，核销 ${fenToYuan(amount)} 元`)
    payOpen.value = false
    await Promise.all([loadPayables(), loadReconcile()])
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    paying.value = false
  }
}

// ─────────────────────────── 明细 ───────────────────────────

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<SupplierPayable | null>(null)

async function openDetail(row: SupplierPayable) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await payableApi.get(row.id)
  } catch (error) {
    ElMessage.error(errorMessage(error))
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

const detailBalance = computed(() => {
  const d = detail.value
  if (!d) return 0
  return d.originalAmount - d.returnedAmount - d.paidAmount
})

onMounted(() => {
  void loadSuppliers()
  void loadPayables()
  void loadReconcile()
})
</script>

<template>
  <div class="payable-view">
    <el-card shadow="never" class="summary">
      <div class="summary-head">
        <div>
          <div class="summary-title">应付余额核对</div>
          <div class="summary-sub">
            余额 = 进货（合格到货）− 退货 − 付款；明细合计必须等于付款单金额。
          </div>
        </div>
        <el-button :loading="reconcileLoading" @click="loadReconcile">重新核对</el-button>
      </div>

      <el-alert v-if="reconcileError" type="error" :closable="false" :title="reconcileError" />
      <div v-else-if="reconcile" class="figures">
        <div class="figure">
          <span class="figure-label">进货金额</span>
          <span class="figure-value">{{ fenToYuan(reconcile.purchaseAmount) }}</span>
        </div>
        <div class="figure">
          <span class="figure-label">退货冲减</span>
          <span class="figure-value">{{ fenToYuan(reconcile.returnAmount) }}</span>
        </div>
        <div class="figure">
          <span class="figure-label">已付款</span>
          <span class="figure-value">{{ fenToYuan(reconcile.paidAmount) }}</span>
        </div>
        <div class="figure">
          <span class="figure-label">应付余额</span>
          <span class="figure-value strong">{{ fenToYuan(reconcile.balance) }}</span>
        </div>
        <div class="figure">
          <span class="figure-label">余额恒等式</span>
          <el-tag :type="reconcile.balanced ? 'success' : 'danger'" size="small">
            {{ reconcile.balanced ? '成立' : '不平' }}
          </el-tag>
        </div>
        <div class="figure">
          <span class="figure-label">核销守恒</span>
          <el-tag :type="reconcile.conserved ? 'success' : 'danger'" size="small">
            {{ reconcile.conserved ? '成立' : '不平' }}
          </el-tag>
        </div>
      </div>
    </el-card>

    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!loadError"
      row-key="id"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
    >
      <template #filters>
        <el-select
          v-model="supplierFilter"
          placeholder="全部供应商"
          clearable
          filterable
          style="width: 220px"
          @change="search"
        >
          <el-option
            v-for="s in suppliers"
            :key="s.id"
            :label="s.enabled ? s.name : `${s.name}（已停用）`"
            :value="s.id"
          />
        </el-select>
        <el-select
          v-model="statusFilter"
          placeholder="全部状态"
          clearable
          style="width: 150px"
          @change="search"
        >
          <el-option label="未付款" value="OPEN" />
          <el-option label="部分核销" value="PARTIAL" />
          <el-option label="已结清" value="SETTLED" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <template #header>
        <span>供应商应付单（{{ total }}）</span>
        <span class="text-muted">金额单位：元</span>
      </template>

      <el-table-column prop="payableNo" label="应付单号" min-width="150" />
      <el-table-column prop="orderNo" label="进货单号" min-width="150" />
      <el-table-column label="供应商" min-width="160">
        <template #default="{ row }">{{ supplierText(row.supplierId) }}</template>
      </el-table-column>
      <el-table-column prop="bizDate" label="业务日期" width="110" />
      <el-table-column label="原值" width="110" align="right">
        <template #default="{ row }">{{ fenToYuan(row.originalAmount) }}</template>
      </el-table-column>
      <el-table-column label="退货" width="100" align="right">
        <template #default="{ row }">{{ fenToYuan(row.returnedAmount) }}</template>
      </el-table-column>
      <el-table-column label="已付" width="100" align="right">
        <template #default="{ row }">{{ fenToYuan(row.paidAmount) }}</template>
      </el-table-column>
      <el-table-column label="余额" width="110" align="right">
        <template #default="{ row }">
          <strong>{{ fenToYuan(row.balance) }}</strong>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta(row.status).type" size="small">
            {{ statusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">明细</el-button>
          <el-button
            v-if="canPay && row.balance > 0"
            link
            type="success"
            @click="openPay(row)"
          >
            付款核销
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <!-- 付款弹窗 -->
    <el-dialog v-model="payOpen" title="付款核销" width="520px" destroy-on-close>
      <template v-if="payRow">
        <el-descriptions :column="1" border size="small" class="mb-12">
          <el-descriptions-item label="应付单">{{ payRow.payableNo }}</el-descriptions-item>
          <el-descriptions-item label="进货单">{{ payRow.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="当前余额">
            {{ fenToYuan(payRow.balance) }} 元
          </el-descriptions-item>
        </el-descriptions>
        <el-form label-width="90px">
          <el-form-item label="付款金额">
            <el-input v-model="payForm.amountYuan" placeholder="元，最多等于当前余额">
              <template #append>元</template>
            </el-input>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="payForm.remark" maxlength="500" show-word-limit />
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          title="本次付款会一次性核销到该应付单；核销合计必须等于付款金额，后端会再次校验。"
        />
      </template>
      <template #footer>
        <el-button @click="payOpen = false">取消</el-button>
        <el-button type="primary" :loading="paying" @click="submitPay">确认付款</el-button>
      </template>
    </el-dialog>

    <!-- 明细弹窗 -->
    <el-dialog v-model="detailOpen" title="应付明细" width="720px" destroy-on-close>
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="3" border size="small" class="mb-12">
            <el-descriptions-item label="应付单">{{ detail.payableNo }}</el-descriptions-item>
            <el-descriptions-item label="进货单">{{ detail.orderNo }}</el-descriptions-item>
            <el-descriptions-item label="余额">
              {{ fenToYuan(detail.balance) }} 元
            </el-descriptions-item>
            <el-descriptions-item label="原值">
              {{ fenToYuan(detail.originalAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="退货">
              {{ fenToYuan(detail.returnedAmount) }}
            </el-descriptions-item>
            <el-descriptions-item label="已付">
              {{ fenToYuan(detail.paidAmount) }}
            </el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-if="detailBalance !== detail.balance"
            type="error"
            :closable="false"
            class="mb-12"
            :title="`余额字段与三项相减不一致：${fenToYuan(detail.balance)} ≠ ${fenToYuan(detailBalance)}`"
          />
          <el-table :data="detail.allocations" size="small" border>
            <el-table-column prop="paymentNo" label="付款单号" min-width="150" />
            <el-table-column label="核销金额" width="120" align="right">
              <template #default="{ row }">{{ fenToYuan(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="付款单金额" width="120" align="right">
              <template #default="{ row }">{{ fenToYuan(row.paymentAmount) }}</template>
            </el-table-column>
            <el-table-column prop="bizDate" label="业务日期" width="110" />
            <el-table-column prop="remark" label="备注" min-width="140" />
            <template #empty>
              <span class="text-muted">尚未发生付款核销</span>
            </template>
          </el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.payable-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.summary-title {
  font-size: 15px;
  font-weight: 600;
}

.summary-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.figures {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-top: 16px;
}

.figure {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.figure-label {
  font-size: 12px;
  color: #909399;
}

.figure-value {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}

.figure-value.strong {
  color: #e6a23c;
  font-weight: 600;
}

.mb-12 {
  margin-bottom: 12px;
}

@media (max-width: 768px) {
  .figures {
    gap: 16px;
  }

  .figure-value {
    font-size: 16px;
  }
}
</style>
