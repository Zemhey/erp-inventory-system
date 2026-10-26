<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { finApi, money, type BankAccountView } from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 银行账户（FR-V3-09）。左：账户列表；右：选中账户的日记账预览。
 * 收付款核销自动日记账落在"默认账户"（每租户至多一个）。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('fin:bank:write'))

const accounts = ref<BankAccountView[]>([])
const selected = ref<BankAccountView | null>(null)
const journal = ref<import('@/api/client').CashJournalRow[]>([])
const loading = ref(false)
const dialog = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({
  code: '',
  name: '',
  bankName: '',
  accountNo: '',
  initBalance: 0,
  isDefault: false,
  enabled: true,
  remark: '',
})

async function load() {
  loading.value = true
  try {
    const data = await finApi.bankAccounts()
    accounts.value = data.items
    if (selected.value) {
      selected.value = data.items.find((a) => a.id === selected.value!.id) ?? null
    }
  } finally {
    loading.value = false
  }
}

async function pick(a: BankAccountView) {
  selected.value = a
  const data = await finApi.cashJournal({ accountId: a.id })
  journal.value = data.items
}

function startCreate() {
  editingId.value = null
  form.code = ''
  form.name = ''
  form.bankName = ''
  form.accountNo = ''
  form.initBalance = 0
  form.isDefault = accounts.value.length === 0
  form.enabled = true
  form.remark = ''
  dialog.value = true
}

function startEdit(a: BankAccountView) {
  editingId.value = a.id
  form.code = a.code
  form.name = a.name
  form.bankName = a.bankName ?? ''
  form.accountNo = a.accountNo ?? ''
  // 编辑态回填期初（V24 起期初与现余额分开存：现余额 = 期初 + 流水，由服务端重算）
  form.initBalance = Number(a.initBalance)
  form.isDefault = a.isDefault
  form.enabled = a.enabled
  form.remark = a.remark ?? ''
  dialog.value = true
}

async function save() {
  const payload = {
    code: form.code.trim() || undefined,
    name: form.name.trim(),
    bankName: form.bankName || undefined,
    accountNo: form.accountNo || undefined,
    initBalance: Number(form.initBalance) || 0,
    isDefault: form.isDefault,
    enabled: form.enabled,
    remark: form.remark || undefined,
  }
  try {
    if (editingId.value == null) {
      await finApi.createBankAccount(payload)
      ElMessage.success('账户已创建')
    } else {
      await finApi.updateBankAccount(editingId.value, payload)
      ElMessage.success('账户已更新')
    }
    dialog.value = false
    await load()
  } catch { /* 拦截器已提示 */ }
}

async function setDefault(a: BankAccountView) {
  try {
    await finApi.setDefaultBankAccount(a.id)
    ElMessage.success(`已将「${a.name}」设为默认入账账户`)
    await load()
  } catch { /* 拦截器已提示 */ }
}

async function remove(a: BankAccountView) {
  try {
    await ElMessageBox.confirm(
      `删除账户「${a.name}」？已有日记账流水的账户会被拒绝（409），请改用停用。`,
      '删除账户',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await finApi.deleteBankAccount(a.id)
    ElMessage.success('已删除')
    if (selected.value?.id === a.id) selected.value = null
    await load()
  } catch { /* 拦截器已提示 */ }
}

onMounted(async () => {
  await load()
  if (accounts.value.length > 0) pick(accounts.value[0])
})
</script>

<template>
  <div>
    <el-row :gutter="12">
      <el-col :xs="24" :sm="12" :md="9">
        <el-card shadow="never" v-loading="loading">
          <template #header>
            <div class="card-header">
              <span>银行账户</span>
              <el-button v-if="canWrite" size="small" type="primary" plain @click="startCreate">
                新建账户
              </el-button>
            </div>
          </template>
          <el-empty v-if="accounts.length === 0" description="还没有银行账户：收付款自动日记账需要至少一个账户（第一个会自动成为默认）" />
          <div
            v-for="a in accounts"
            :key="a.id"
            class="account-item"
            :class="{ active: selected?.id === a.id }"
            @click="pick(a)"
          >
            <div class="account-main">
              <div class="account-name">
                {{ a.name }}
                <el-tag v-if="a.isDefault" size="small" type="success">默认</el-tag>
                <el-tag v-if="!a.enabled" size="small" type="danger">停用</el-tag>
              </div>
              <div class="account-sub">
                {{ a.bankName || a.code }} · 期初 {{ money(a.initBalance) }} · 余额 {{ money(a.balance) }} 元
              </div>
            </div>
            <div class="account-ops">
              <el-button v-if="canWrite" link size="small" @click.stop="startEdit(a)">编辑</el-button>
              <el-button v-if="canWrite && !a.isDefault" link size="small" @click.stop="setDefault(a)">设默认</el-button>
              <el-button v-if="canWrite" link size="small" type="danger" @click.stop="remove(a)">删除</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="12" :md="15">
        <el-card shadow="never">
          <template #header>
            <span>{{ selected ? `日记账 · ${selected.name}` : '日记账（选择左侧账户）' }}</span>
          </template>
          <el-table :data="journal" size="small" border max-height="480">
            <el-table-column prop="bizDate" label="日期" width="110" />
            <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip />
            <el-table-column label="方向" width="70">
              <template #default="{ row }">
                <el-tag :type="row.direction === 'DEBIT' ? 'success' : 'danger'" size="small">
                  {{ row.directionLabel }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="金额(元)" width="110" align="right">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="余额(元)" width="120" align="right">
              <template #default="{ row }">{{ money(row.balance) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialog" :title="editingId == null ? '新建银行账户' : '编辑银行账户'" width="460px">
      <el-form label-width="110px">
        <el-form-item label="编码">
          <el-input v-model="form.code" :disabled="editingId != null" maxlength="32" placeholder="如 BA01" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="64" placeholder="如 基本户" />
        </el-form-item>
        <el-form-item label="开户行">
          <el-input v-model="form.bankName" maxlength="64" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.accountNo" maxlength="64" />
        </el-form-item>
        <el-form-item label="期初余额(元)">
          <el-input-number v-model="form.initBalance" :min="0" :precision="2" :step="1000" />
          <div class="form-hint">改期初会连带重算现余额（现余额 = 期初 + 已入账流水），流水本身不动</div>
        </el-form-item>
        <el-form-item label="默认账户">
          <el-switch v-model="form.isDefault" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-hint {
  width: 100%;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.account-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 8px;
  border-bottom: 1px solid #ebeef5;
  cursor: pointer;
}

.account-item.active {
  background: #ecf5ff;
}

.account-name {
  font-weight: 600;
  display: flex;
  gap: 6px;
  align-items: center;
}

.account-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}
</style>
