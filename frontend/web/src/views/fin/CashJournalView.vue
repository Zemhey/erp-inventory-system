<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { finApi, money, type BankAccountView, type CashJournalRow } from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 现金日记账独立页（FR-V3-09 / 简报"8 个页面"）。
 * 余额口径：当日余额 = 上日余额 + 借 − 贷（资产账户借增贷减）。
 * 表格按业务日期倒序展示，但余额链是后端按账户逐笔连续计算的。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('fin:bank:write'))

const accounts = ref<BankAccountView[]>([])
const rows = ref<CashJournalRow[]>([])
const loading = ref(false)
const filters = reactive<{ accountId: number | null; range: [string, string] | null }>({
  accountId: null,
  range: null,
})

const dialog = ref(false)
const entry = reactive({
  accountId: null as number | null,
  bizDate: '',
  direction: 'DEBIT' as 'DEBIT' | 'CREDIT',
  amount: null as number | null,
  summary: '',
})

async function load() {
  loading.value = true
  try {
    const data = await finApi.cashJournal({
      accountId: filters.accountId ?? undefined,
      from: filters.range?.[0],
      to: filters.range?.[1],
    })
    rows.value = data.items
  } finally {
    loading.value = false
  }
}

async function save() {
  if (entry.accountId == null) {
    ElMessage.warning('请选择银行账户')
    return
  }
  if (!entry.amount || entry.amount <= 0) {
    ElMessage.warning('金额必须为正')
    return
  }
  try {
    await finApi.addCashJournal({
      accountId: entry.accountId,
      bizDate: entry.bizDate || undefined,
      direction: entry.direction,
      amount: entry.amount,
      summary: entry.summary || undefined,
    })
    ElMessage.success('已记账，余额链已延续')
    dialog.value = false
    entry.amount = null
    entry.summary = ''
    await load()
  } catch { /* 拦截器已提示 */ }
}

onMounted(async () => {
  const data = await finApi.bankAccounts()
  accounts.value = data.items
  if (data.items.length > 0) entry.accountId = data.items[0].id
  await load()
})
</script>

<template>
  <PageTable
    :rows="rows"
    :loading="loading"
    :paginated="false"
    :filtered="filters.accountId != null || filters.range != null"
    row-key="id"
    empty-text="暂无日记账流水"
  >
    <template #header>
      <div class="header-left">
        <span class="title">现金日记账</span>
        <span class="subtitle">当日余额 = 上日余额 + 借 − 贷；收付款核销/报销付款会自动落账</span>
      </div>
      <el-button
        v-if="canWrite"
        type="primary"
        @click="dialog = true"
      >
        手工记一笔
      </el-button>
    </template>

    <template #filters>
      <el-select v-model="filters.accountId" placeholder="全部账户" clearable class="w-200">
        <el-option v-for="a in accounts" :key="a.id" :label="`${a.name}（${money(a.balance)} 元）`" :value="a.id" />
      </el-select>
      <el-date-picker
        v-model="filters.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="w-260"
      />
      <el-button type="primary" @click="load">查询</el-button>
    </template>

    <el-table-column prop="bizDate" label="日期" width="110" />
    <el-table-column label="账户" width="140">
      <template #default="{ row }">
        {{ accounts.find((a) => a.id === row.accountId)?.name ?? `账户#${row.accountId}` }}
      </template>
    </el-table-column>
    <el-table-column prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
    <el-table-column label="来源" width="110">
      <template #default="{ row }">
        <el-tag size="small" type="info">{{ row.sourceType }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="方向" width="80">
      <template #default="{ row }">
        <el-tag :type="row.direction === 'DEBIT' ? 'success' : 'danger'" size="small">
          {{ row.directionLabel }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="金额(元)" width="120" align="right">
      <template #default="{ row }">{{ money(row.amount) }}</template>
    </el-table-column>
    <el-table-column label="余额(元)" width="120" align="right">
      <template #default="{ row }">{{ money(row.balance) }}</template>
    </el-table-column>

    <template #empty>
      <div class="empty-title">{{ accounts.length === 0 ? '还没有银行账户' : '暂无流水' }}</div>
      <div class="empty-subtitle">
        {{ accounts.length === 0
          ? '先到"银行账户"建户；之后收付款核销会自动落账。'
          : '收付款核销与报销付款会自动写入日记账，也可"手工记一笔"补录利息等。' }}
      </div>
    </template>

    <el-dialog v-model="dialog" title="手工记一笔" width="420px">
      <el-form label-width="90px">
        <el-form-item label="账户">
          <el-select v-model="entry.accountId" class="w-full">
            <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="entry.bizDate" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="方向">
          <el-radio-group v-model="entry.direction">
            <el-radio value="DEBIT">收（借）</el-radio>
            <el-radio value="CREDIT">支（贷）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="金额(元)">
          <el-input-number v-model="entry.amount" :min="0.01" :precision="2" :controls="false" class="w-full" />
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="entry.summary" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">记账</el-button>
      </template>
    </el-dialog>
  </PageTable>
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

.w-200 { width: 200px; }
.w-260 { width: 260px; }
.w-full { width: 100%; }

.empty-title {
  font-size: 15px;
  color: #606266;
}

.empty-subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
</style>
