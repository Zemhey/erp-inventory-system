<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { finApi, money, type FinInvoiceRow } from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 发票台账（FR-V3-11）。销项（OUTPUT）/进项（INPUT）两个 Tab。
 * 关联进货单/出货单时，同一单据累计开票不得超过单据金额（409，消息带差额）。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('fin:invoice:write'))

const tab = ref<'OUTPUT' | 'INPUT'>('OUTPUT')
const rows = ref<FinInvoiceRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const filters = reactive({ keyword: '', range: null as [string, string] | null })

const dialog = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  invoiceNo: '',
  partnerName: '',
  partnerId: null as number | null,
  amount: null as number | null,
  taxAmount: 0,
  invoiceDate: '',
  sourceType: '' as '' | 'PURCHASE_ORDER' | 'SALES_ORDER',
  sourceId: null as number | null,
  remark: '',
})

async function load() {
  loading.value = true
  try {
    const data = await finApi.invoices({
      type: tab.value,
      keyword: filters.keyword || undefined,
      from: filters.range?.[0],
      to: filters.range?.[1],
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
  form.invoiceNo = ''
  form.partnerName = ''
  form.partnerId = null
  form.amount = null
  form.taxAmount = 0
  form.invoiceDate = ''
  form.sourceType = ''
  form.sourceId = null
  form.remark = ''
  dialog.value = true
}

function startEdit(row: FinInvoiceRow) {
  editingId.value = row.id
  form.invoiceNo = row.invoiceNo
  form.partnerName = row.partnerName
  form.partnerId = row.partnerId
  form.amount = Number(row.amount)
  form.taxAmount = Number(row.taxAmount)
  form.invoiceDate = row.invoiceDate
  form.sourceType = row.sourceType ?? ''
  form.sourceId = row.sourceId ?? null
  form.remark = row.remark ?? ''
  dialog.value = true
}

const totalPreview = computed(
  () => (Number(form.amount) || 0) + (Number(form.taxAmount) || 0),
)

async function save() {
  const payload = {
    invoiceNo: form.invoiceNo.trim(),
    type: tab.value,
    partnerName: form.partnerName.trim(),
    partnerId: form.partnerId ?? undefined,
    amount: Number(form.amount) || 0,
    taxAmount: Number(form.taxAmount) || 0,
    invoiceDate: form.invoiceDate || undefined,
    sourceType: form.sourceType || undefined,
    sourceId: form.sourceId ?? undefined,
    remark: form.remark || undefined,
  }
  try {
    if (editingId.value == null) {
      await finApi.createInvoice(payload)
      ElMessage.success('发票已登记')
    } else {
      await finApi.updateInvoice(editingId.value, payload)
      ElMessage.success('发票已更新')
    }
    dialog.value = false
    await load()
  } catch { /* 409 超额（带差额）等消息由拦截器透出 */ }
}

async function voidInvoice(row: FinInvoiceRow) {
  try {
    await ElMessageBox.confirm(
      `作废发票 ${row.invoiceNo}？作废后释放该单据的已开额度。`,
      '作废发票',
      { type: 'warning', confirmButtonText: '作废', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await finApi.voidInvoice(row.id)
    ElMessage.success('已作废')
    await load()
  } catch { /* 拦截器已提示 */ }
}

onMounted(load)
</script>

<template>
  <el-tabs v-model="tab" class="invoice-tabs" @tab-change="search">
    <el-tab-pane label="销项发票（开给客户）" name="OUTPUT" />
    <el-tab-pane label="进项发票（供应商开给我）" name="INPUT" />
  </el-tabs>

  <PageTable
    v-model:page="page"
    v-model:page-size="pageSize"
    :rows="rows"
    :total="total"
    :loading="loading"
    row-key="id"
    empty-text="暂无发票"
    @change="load"
  >
    <template #header>
      <div class="header-left">
        <span class="title">{{ tab === 'OUTPUT' ? '销项发票台账' : '进项发票台账' }}</span>
        <span class="subtitle">价税合计 = 不含税额 + 税额；关联单据的累计开票不得超过单据金额</span>
      </div>
      <el-button v-if="canWrite" type="primary" @click="startCreate">登记发票</el-button>
    </template>

    <template #filters>
      <el-input v-model="filters.keyword" placeholder="发票号 / 购销方" class="w-220" clearable />
      <el-date-picker
        v-model="filters.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开票起"
        end-placeholder="开票止"
        class="w-260"
      />
      <el-button type="primary" @click="search">查询</el-button>
    </template>

    <el-table-column prop="invoiceNo" label="发票号" width="150" />
    <el-table-column prop="partnerName" label="对方" min-width="140" />
    <el-table-column label="不含税(元)" width="120" align="right">
      <template #default="{ row }">{{ money(row.amount) }}</template>
    </el-table-column>
    <el-table-column label="税额(元)" width="110" align="right">
      <template #default="{ row }">{{ money(row.taxAmount) }}</template>
    </el-table-column>
    <el-table-column label="价税合计(元)" width="130" align="right">
      <template #default="{ row }">{{ money(row.totalAmount) }}</template>
    </el-table-column>
    <el-table-column prop="invoiceDate" label="开票日期" width="110" />
    <el-table-column label="关联单据" width="180">
      <template #default="{ row }">
        <span v-if="row.sourceType">{{ row.sourceType }} #{{ row.sourceId }}</span>
        <span v-else class="muted">未关联</span>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <el-tag :type="row.status === 'NORMAL' ? 'success' : 'danger'" size="small">
          {{ row.statusLabel }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="130" fixed="right">
      <template #default="{ row }">
        <template v-if="canWrite && row.status === 'NORMAL'">
          <el-button link type="primary" @click="startEdit(row)">修改</el-button>
          <el-button link type="danger" @click="voidInvoice(row)">作废</el-button>
        </template>
        <span v-else class="muted">—</span>
      </template>
    </el-table-column>
  </PageTable>

  <el-dialog v-model="dialog" :title="editingId == null ? '登记发票' : '修改发票'" width="520px">
    <el-form label-width="120px">
      <el-form-item label="发票号">
        <el-input v-model="form.invoiceNo" maxlength="64" :disabled="editingId != null" />
      </el-form-item>
      <el-form-item label="对方名称">
        <el-input v-model="form.partnerName" maxlength="64" />
      </el-form-item>
      <el-form-item label="对方ID">
        <el-input-number v-model="form.partnerId" :min="1" :controls="false" placeholder="客户/供应商 id，选填" />
      </el-form-item>
      <el-form-item label="不含税金额(元)">
        <el-input-number v-model="form.amount" :min="0" :precision="2" :controls="false" class="w-180" />
      </el-form-item>
      <el-form-item label="税额(元)">
        <el-input-number v-model="form.taxAmount" :min="0" :precision="2" :controls="false" class="w-180" />
      </el-form-item>
      <el-form-item label="价税合计(元)">
        <span class="total-preview">{{ money(totalPreview) }}</span>
      </el-form-item>
      <el-form-item label="开票日期">
        <el-date-picker v-model="form.invoiceDate" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
      </el-form-item>
      <el-form-item label="关联单据">
        <el-select v-model="form.sourceType" clearable placeholder="不关联" class="w-180">
          <el-option label="进货单" value="PURCHASE_ORDER" />
          <el-option label="出货单" value="SALES_ORDER" />
        </el-select>
        <el-input-number
          v-if="form.sourceType"
          v-model="form.sourceId"
          :min="1"
          :controls="false"
          placeholder="单据 id"
          class="w-140 ml-8"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.invoice-tabs {
  margin-bottom: 4px;
}

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

.muted {
  color: #909399;
  font-size: 12px;
}

.total-preview {
  font-weight: 600;
}

.w-220 { width: 220px; }
.w-260 { width: 260px; }
.w-180 { width: 180px; }
.w-140 { width: 140px; }
.ml-8 { margin-left: 8px; }
</style>
