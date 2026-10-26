<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  finApi,
  money,
  type FinAccountView,
  type FinVoucherRow,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 凭证管理（FR-V3-07/08）。
 *
 * ★ 借贷平衡是保存的入场券：录入抽屉实时显示 借合计/贷合计/差额，
 *   后端 400 FIN_VOUCHER_UNBALANCED 的 details 也带差额 —— 两处口径同一公式。
 * ★ 自动生成凭证（sourceType ≠ MANUAL）直接是 POSTED，只读；手工凭证走
 *   DRAFT → AUDITED → POSTED。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('fin:voucher:write'))

const rows = ref<FinVoucherRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const accounts = ref<FinAccountView[]>([])

const filters = reactive({ status: '', sourceType: '', period: '', keyword: '' })

const drawer = ref(false)
const editingId = ref<number | null>(null)
const head = reactive({ voucherDate: '', remark: '' })
interface LineRow {
  accountId: number | null
  summary: string
  debit: number | null
  credit: number | null
}
const lines = ref<LineRow[]>([])

const sumDebit = computed(() =>
  lines.value.reduce((s, l) => s + (Number(l.debit) || 0), 0),
)
const sumCredit = computed(() =>
  lines.value.reduce((s, l) => s + (Number(l.credit) || 0), 0),
)
const diff = computed(() => sumDebit.value - sumCredit.value)
const balanced = computed(() => Math.abs(diff.value) < 0.005)

async function load() {
  loading.value = true
  try {
    const data = await finApi.vouchers({
      status: filters.status || undefined,
      sourceType: filters.sourceType || undefined,
      period: filters.period || undefined,
      keyword: filters.keyword || undefined,
      page: page.value,
      size: pageSize.value,
    })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function statusTag(s: string): 'info' | 'warning' | 'success' {
  return s === 'POSTED' ? 'success' : s === 'AUDITED' ? 'warning' : 'info'
}

async function openCreate() {
  editingId.value = null
  head.voucherDate = ''
  head.remark = ''
  lines.value = [
    { accountId: null, summary: '', debit: null, credit: null },
    { accountId: null, summary: '', debit: null, credit: null },
  ]
  drawer.value = true
}

async function openEdit(row: FinVoucherRow) {
  const detail = await finApi.voucher(row.id)
  editingId.value = detail.id
  head.voucherDate = detail.voucherDate
  head.remark = detail.remark ?? ''
  lines.value = detail.lines.map((l) => ({
    accountId: l.accountId,
    summary: l.summary,
    debit: Number(l.debit) || null,
    credit: Number(l.credit) || null,
  }))
  drawer.value = true
}

function addLine() {
  lines.value.push({ accountId: null, summary: '', debit: null, credit: null })
}

function removeLine(i: number) {
  lines.value.splice(i, 1)
}

function leafAccounts() {
  return accounts.value.filter((a) => a.leaf && a.enabled)
}

async function save() {
  if (!balanced.value) {
    ElMessage.warning(`借贷不平，差额 ${diff.value.toFixed(2)} 元`)
    return
  }
  const payload = {
    voucherDate: head.voucherDate || undefined,
    remark: head.remark || undefined,
    lines: lines.value
      .filter((l) => l.accountId != null && (l.debit || l.credit))
      .map((l) => ({
        accountId: l.accountId as number,
        summary: l.summary || undefined,
        debit: Number(l.debit) || undefined,
        credit: Number(l.credit) || undefined,
      })),
  }
  try {
    if (editingId.value == null) {
      await finApi.createVoucher(payload)
      ElMessage.success('凭证已保存（草稿）')
    } else {
      await finApi.updateVoucher(editingId.value, payload)
      ElMessage.success('凭证已更新')
    }
    drawer.value = false
    await load()
  } catch {
    /* 400 差额 / 科目非法等消息由拦截器透出 */
  }
}

async function audit(row: FinVoucherRow) {
  try {
    await finApi.auditVoucher(row.id)
    ElMessage.success('已审核')
    await load()
  } catch { /* 拦截器已提示 */ }
}

async function post(row: FinVoucherRow) {
  try {
    await finApi.postVoucher(row.id)
    ElMessage.success('已过账（报表开始统计这张凭证）')
    await load()
  } catch { /* 拦截器已提示 */ }
}

onMounted(async () => {
  const data = await finApi.accounts()
  accounts.value = data.items
  await load()
})
</script>

<template>
  <div>
    <PageTable
      v-model:page="page"
      v-model:page-size="pageSize"
      :rows="rows"
      :total="total"
      :loading="loading"
      row-key="id"
      empty-text="暂无凭证"
      @change="load"
    >
      <template #header>
        <div class="header-left">
          <span class="title">凭证管理</span>
          <span class="subtitle">业务单据确认后自动生成（已过账）；手工凭证走 草稿 → 审核 → 过账</span>
        </div>
        <el-button v-if="canWrite" type="primary" @click="openCreate">录入凭证</el-button>
      </template>

      <template #filters>
        <el-input v-model="filters.period" placeholder="账期 yyyy-MM" class="w-140" clearable />
        <el-select v-model="filters.status" placeholder="状态" class="w-120" clearable>
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已审核" value="AUDITED" />
          <el-option label="已过账" value="POSTED" />
        </el-select>
        <el-select v-model="filters.sourceType" placeholder="来源" class="w-140" clearable>
          <el-option label="手工" value="MANUAL" />
          <el-option label="采购入库" value="PUR_IN" />
          <el-option label="销售出库（发货单）" value="SAL_OUT" />
          <el-option label="销售出货（直发）" value="SAL_ORDER_OUT" />
          <el-option label="付款核销" value="PUR_PAY" />
          <el-option label="收款核销" value="SAL_RCV" />
          <el-option label="费用报销" value="EXPENSE" />
          <el-option label="发票" value="INVOICE" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="凭证号 / 来源单号 / 摘要" class="w-220" clearable />
        <el-button type="primary" @click="search">查询</el-button>
      </template>

      <el-table-column prop="voucherNo" label="凭证号" width="150" />
      <el-table-column prop="voucherDate" label="日期" width="110" />
      <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip />
      <el-table-column label="来源" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="row.sourceType === 'MANUAL' ? '' : 'info'">
            {{ row.sourceTypeLabel }}
          </el-tag>
          <span v-if="row.sourceNo" class="src-no">{{ row.sourceNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="借方合计(元)" width="120" align="right">
        <template #default="{ row }">{{ money(row.totalDebit) }}</template>
      </el-table-column>
      <el-table-column label="贷方合计(元)" width="120" align="right">
        <template #default="{ row }">{{ money(row.totalCredit) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="canWrite">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="openEdit(row)">修改</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="warning" @click="audit(row)">审核</el-button>
            <el-button v-if="row.status === 'AUDITED'" link type="success" @click="post(row)">过账</el-button>
            <el-button v-if="row.status === 'POSTED'" link @click="openEdit(row)">查看</el-button>
          </template>
          <span v-else class="muted">只读</span>
        </template>
      </el-table-column>

      <template #empty>
        <div class="empty-title">暂无凭证</div>
        <div class="empty-subtitle">
          采购入库 / 销售出库 / 收付款核销确认后会自动生成已过账凭证；也可以点右上"录入凭证"手工录入。
        </div>
      </template>
    </PageTable>

    <el-drawer v-model="drawer" :title="editingId == null ? '录入凭证' : '凭证详情/编辑'" size="720px">
      <el-form inline label-width="80px">
        <el-form-item label="凭证日期">
          <el-date-picker v-model="head.voucherDate" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="head.remark" maxlength="255" />
        </el-form-item>
      </el-form>

      <el-table :data="lines" size="small" border>
        <el-table-column label="摘要" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.summary" :disabled="!canWrite" maxlength="128" placeholder="摘要" />
          </template>
        </el-table-column>
        <el-table-column label="科目" width="210">
          <template #default="{ row }">
            <el-select v-model="row.accountId" filterable placeholder="编码/名称" class="w-full">
              <el-option
                v-for="a in leafAccounts()"
                :key="a.id"
                :label="`${a.code} ${a.name}`"
                :value="a.id"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="借方(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.debit" :min="0" :precision="2" :controls="false" class="w-full" />
          </template>
        </el-table-column>
        <el-table-column label="贷方(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.credit" :min="0" :precision="2" :controls="false" class="w-full" />
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite && editingId == null" label="" width="50">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeLine($index)">删</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="totals">
        <span>借合计：<b>{{ money(sumDebit) }}</b></span>
        <span>贷合计：<b>{{ money(sumCredit) }}</b></span>
        <span>差额：<b :class="balanced ? 'ok' : 'bad'">{{ diff.toFixed(2) }}</b></span>
        <el-tag v-if="!balanced" type="danger" size="small">借贷不平，保存会被拒绝</el-tag>
      </div>

      <div class="drawer-actions">
        <el-button v-if="editingId == null && canWrite" @click="addLine">加一行</el-button>
        <el-button v-if="canWrite" type="primary" :disabled="!balanced" @click="save">
          {{ editingId == null ? '保存草稿' : '保存修改' }}
        </el-button>
        <el-button @click="drawer = false">关闭</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.header-left {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.title {
  font-size: 16px;
  font-weight: 600;
}

.subtitle {
  font-size: 12px;
  color: #909399;
}

.src-no {
  margin-left: 6px;
  font-size: 12px;
  color: #909399;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.w-140 { width: 140px; }
.w-120 { width: 120px; }
.w-220 { width: 220px; }
.w-full { width: 100%; }

.totals {
  display: flex;
  gap: 24px;
  align-items: center;
  margin-top: 12px;
  font-size: 14px;
}

.ok { color: #3a8f5a; }
.bad { color: #c45656; }

.drawer-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.empty-title {
  font-size: 15px;
  color: #606266;
}

.empty-subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
