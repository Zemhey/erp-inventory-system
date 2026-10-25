<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  finApi,
  money,
  type FinExpenseDetail,
  type FinExpenseRow,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 费用报销（FR-V3-10）。
 * 状态机 DRAFT → SUBMITTED → APPROVED → PAID；驳回回 DRAFT 并留原因。
 * 付款成功后：凭证（借 6602 管理费用 / 贷 1002 银行存款）与银行日记账自动出现。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('fin:expense:write'))
const canApprove = computed(() => session.hasPermission('fin:expense:approve'))

const rows = ref<FinExpenseRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const filters = reactive({ status: '', keyword: '' })

const drawer = ref(false)
const editingId = ref<number | null>(null)
const head = reactive({ expenseType: '', departmentName: '', applyDate: '', remark: '' })
const lines = ref<{ expenseItem: string; amount: number | null; invoiceNo: string }[]>([
  { expenseItem: '', amount: null, invoiceNo: '' },
])

const lineTotal = computed(() =>
  lines.value.reduce((s, l) => s + (Number(l.amount) || 0), 0),
)

async function load() {
  loading.value = true
  try {
    const data = await finApi.expenses({
      status: filters.status || undefined,
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

function startCreate() {
  editingId.value = null
  head.expenseType = '差旅费'
  head.departmentName = ''
  head.applyDate = ''
  head.remark = ''
  lines.value = [{ expenseItem: '', amount: null, invoiceNo: '' }]
  drawer.value = true
}

async function openDetail(row: FinExpenseRow) {
  const d: FinExpenseDetail = await finApi.expense(row.id)
  if (d.status === 'DRAFT' && canWrite.value) {
    editingId.value = d.id
    head.expenseType = d.expenseType
    head.departmentName = d.departmentName ?? ''
    head.applyDate = d.applyDate
    head.remark = d.remark ?? ''
    lines.value = d.lines.map((l) => ({
      expenseItem: l.expenseItem,
      amount: Number(l.amount),
      invoiceNo: l.invoiceNo ?? '',
    }))
    drawer.value = true
  } else {
    try {
      await ElMessageBox.alert(
        d.lines
          .map((l) => `#${l.lineNo} ${l.expenseItem}　${money(l.amount)} 元${l.invoiceNo ? `（票 ${l.invoiceNo}）` : ''}`)
          .join('\n') + `\n合计：${money(d.amount)} 元`,
        `${d.expenseNo} · ${d.statusLabel}`,
        { type: 'info' },
      )
    } catch { /* 关闭即可 */ }
  }
}

async function save() {
  try {
    const payload = {
      expenseType: head.expenseType,
      departmentName: head.departmentName || undefined,
      applyDate: head.applyDate || undefined,
      remark: head.remark || undefined,
      lines: lines.value
        .filter((l) => l.expenseItem.trim() && l.amount)
        .map((l) => ({
          expenseItem: l.expenseItem.trim(),
          amount: Number(l.amount),
          invoiceNo: l.invoiceNo || undefined,
        })),
    }
    if (editingId.value == null) {
      await finApi.createExpense(payload)
      ElMessage.success('报销单已创建（草稿）')
    } else {
      await finApi.updateExpense(editingId.value, payload)
      ElMessage.success('已保存')
    }
    drawer.value = false
    await load()
  } catch { /* 拦截器已提示 */ }
}

async function act(fn: () => Promise<unknown>, ok: string) {
  try {
    await fn()
    ElMessage.success(ok)
    await load()
  } catch { /* 状态非法等消息由拦截器透出 */ }
}

async function reject(row: FinExpenseRow) {
  try {
    const result = await ElMessageBox.prompt('填写驳回原因（必填）', `驳回 ${row.expenseNo}`, {
      inputValidator: (v: string) => (v && v.trim().length > 0 ? true : '原因不能为空'),
    })
    await finApi.rejectExpense(row.id, result.value)
    ElMessage.success('已驳回，报销单回到草稿')
    await load()
  } catch { /* 取消或后端拒绝 */ }
}

function statusTag(s: string): string {
  return s === 'PAID' ? 'success' : s === 'APPROVED' ? 'primary'
    : s === 'SUBMITTED' ? 'warning' : 'info'
}

onMounted(load)
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
      empty-text="暂无报销单"
      @change="load"
    >
      <template #header>
        <div class="header-left">
          <span class="title">费用报销</span>
          <span class="subtitle">两级审批（申请人 → 财务）；付款自动生成凭证并落银行日记账</span>
        </div>
        <el-button v-if="canWrite" type="primary" @click="startCreate">新建报销单</el-button>
      </template>

      <template #filters>
        <el-select v-model="filters.status" placeholder="状态" class="w-130" clearable>
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已提交" value="SUBMITTED" />
          <el-option label="已审批" value="APPROVED" />
          <el-option label="已付款" value="PAID" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="单号 / 申请人" class="w-200" clearable />
        <el-button type="primary" @click="search">查询</el-button>
      </template>

      <el-table-column prop="expenseNo" label="单号" width="150" />
      <el-table-column prop="applicantName" label="申请人" width="110" />
      <el-table-column prop="expenseType" label="类型" width="110" />
      <el-table-column label="金额(元)" width="120" align="right">
        <template #default="{ row }">{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="applyDate" label="申请日期" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tooltip v-if="row.rejectReason" :content="`曾被驳回：${row.rejectReason}`">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.statusLabel }}</el-tag>
          </el-tooltip>
          <el-tag v-else :type="statusTag(row.status)" size="small">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">明细</el-button>
          <el-button
            v-if="canWrite && row.status === 'DRAFT'"
            link
            type="warning"
            @click="act(() => finApi.submitExpense(row.id), '已提交，等待财务审批')"
          >
            提交
          </el-button>
          <el-button
            v-if="canApprove && row.status === 'SUBMITTED'"
            link
            type="success"
            @click="act(() => finApi.approveExpense(row.id), '已审批')"
          >
            审批
          </el-button>
          <el-button
            v-if="canApprove && row.status === 'SUBMITTED'"
            link
            type="danger"
            @click="reject(row)"
          >
            驳回
          </el-button>
          <el-button
            v-if="canApprove && row.status === 'APPROVED'"
            link
            type="primary"
            @click="act(() => finApi.payExpense(row.id), '已付款；凭证与日记账已生成')"
          >
            付款
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-drawer v-model="drawer" :title="editingId == null ? '新建报销单' : '修改报销单（草稿）'" size="620px">
      <el-form label-width="90px">
        <el-form-item label="费用类型">
          <el-select v-model="head.expenseType" class="w-200">
            <el-option v-for="t in ['差旅费', '办公费', '业务招待费', '运输费', '培训费', '其他']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="head.departmentName" maxlength="64" placeholder="选填" class="w-200" />
        </el-form-item>
        <el-form-item label="申请日期">
          <el-date-picker v-model="head.applyDate" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="head.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>

      <el-table :data="lines" size="small" border>
        <el-table-column label="费用项目" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.expenseItem" maxlength="64" placeholder="如 高铁票 上海-杭州" />
          </template>
        </el-table-column>
        <el-table-column label="金额(元)" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.amount" :min="0.01" :precision="2" :controls="false" class="w-full" />
          </template>
        </el-table-column>
        <el-table-column label="票据号" width="140">
          <template #default="{ row }">
            <el-input v-model="row.invoiceNo" maxlength="64" />
          </template>
        </el-table-column>
        <el-table-column width="50">
          <template #default="{ $index }">
            <el-button link type="danger" @click="lines.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="totals">明细合计：<b>{{ money(lineTotal) }}</b> 元（保存时主表金额自动取合计）</div>
      <div class="drawer-actions">
        <el-button @click="lines.push({ expenseItem: '', amount: null, invoiceNo: '' })">加一行</el-button>
        <el-button type="primary" @click="save">保存</el-button>
        <el-button @click="drawer = false">取消</el-button>
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

.w-130 { width: 130px; }
.w-200 { width: 200px; }
.w-full { width: 100%; }

.totals {
  margin-top: 12px;
  font-size: 14px;
}

.drawer-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}
</style>
