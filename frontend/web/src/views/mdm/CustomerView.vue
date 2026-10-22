<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  discountLabel,
  mdmApi,
  type Customer,
  type CustomerCreate,
  type CustomerUpdate,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 客户 / 会员（FR-MDM-03）。
 *
 * ★ 折扣率是**整数万分比**：10000 = 不打折，9000 = 9 折。
 *   前端只做两件事：把输入原样转成整数提交、用 discountLabel 显示。
 *   **合法区间 (0, 10000] 由后端判定**（0 = 白送，必须拒绝）——
 *   前端若再实现一遍区间规则，迟早与后端不一致（计划书 §0）。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('mdm:write'))

// ─────────────────────────── 列表 ───────────────────────────

const customers = ref<Customer[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)
const listError = ref('')
const filtered = computed(() => keyword.value.trim() !== '')

/** 拦截器已经弹过统一 toast；这里把消息留在页面上，便于刷新后回看与重试。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadCustomers() {
  loading.value = true
  listError.value = ''
  try {
    const data = await mdmApi.listCustomers({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: pageSize.value,
    })
    customers.value = data.items
    total.value = data.total
  } catch (error) {
    customers.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function onPageChange(nextPage: number, nextPageSize: number) {
  page.value = nextPage
  pageSize.value = nextPageSize
  loadCustomers()
}

function searchCustomers() {
  page.value = 1
  loadCustomers()
}

function resetFilters() {
  keyword.value = ''
  page.value = 1
  loadCustomers()
}

onMounted(loadCustomers)

// ─────────────────────────── 新建 / 编辑 ───────────────────────────

const dialog = ref(false)
const editing = ref<Customer | null>(null)
const formRef = ref<FormInstance>()
const saving = ref(false)
const formError = ref('')
const form = reactive({
  code: '',
  name: '',
  phone: '',
  discountRate: '10000',
  level: 'NORMAL',
  enabled: true,
})

/** 仅校验"整数"这一输入格式；区间由后端判断。 */
function validateIntegerRate(
  _rule: unknown,
  value: unknown,
  callback: (error?: string | Error) => void,
) {
  const raw = String(value ?? '').trim()
  if (raw === '') {
    callback()
    return
  }
  const n = Number(raw)
  if (!Number.isInteger(n)) {
    callback(new Error('折扣率必须是整数万分比（如 9000 = 9 折）'))
    return
  }
  callback()
}

const rules: FormRules = {
  code: [
    { required: true, message: '请输入客户编码', trigger: 'blur' },
    { min: 2, max: 32, message: '编码长度 2~32 个字符', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入客户名称', trigger: 'blur' },
    { max: 128, message: '名称最多 128 个字符', trigger: 'blur' },
  ],
  phone: [{ max: 32, message: '电话最多 32 个字符', trigger: 'blur' }],
  discountRate: [
    { required: true, message: '请输入折扣率', trigger: 'blur' },
    { validator: validateIntegerRate, trigger: 'blur' },
  ],
  level: [{ max: 16, message: '等级最多 16 个字符', trigger: 'blur' }],
}

const discountPreview = computed(() => {
  const raw = form.discountRate.trim()
  if (raw === '') return '-'
  const n = Number(raw)
  // 仅用于显示：区间外不给出折扣文案，避免把 0 显示成「0 折」而误导；
  // 是否接受该值仍由后端决定。
  return Number.isInteger(n) && n >= 1 && n <= 10000 ? discountLabel(n) : '-'
})

function clearValidate() {
  formRef.value?.clearValidate()
}

function openCreate() {
  editing.value = null
  formError.value = ''
  Object.assign(form, {
    code: '',
    name: '',
    phone: '',
    discountRate: '10000',
    level: 'NORMAL',
    enabled: true,
  })
  dialog.value = true
}

function openEdit(row: Customer) {
  editing.value = row
  formError.value = ''
  Object.assign(form, {
    code: row.code,
    name: row.name,
    phone: row.phone ?? '',
    discountRate: String(row.discountRate),
    level: row.level || 'NORMAL',
    enabled: row.enabled,
  })
  dialog.value = true
}

async function saveCustomer() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  formError.value = ''
  try {
    const common: CustomerUpdate = {
      name: form.name.trim(),
      phone: form.phone.trim() || undefined,
      // 原样交给后端判定区间；这里只保证是整数
      discountRate: Number(form.discountRate.trim()),
      level: form.level.trim() || undefined,
      enabled: form.enabled,
    }
    if (editing.value) {
      await mdmApi.updateCustomer(editing.value.id, common)
      ElMessage.success('客户已更新')
    } else {
      const createBody: CustomerCreate = { ...common, code: form.code.trim() }
      await mdmApi.createCustomer(createBody)
      ElMessage.success('客户已创建')
    }
    dialog.value = false
    await loadCustomers()
  } catch (error) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function disableCustomer(row: Customer) {
  try {
    await ElMessageBox.confirm(
      `确定停用客户「${row.name}」吗？停用后新出货单不能再选择它，历史单据不受影响。`,
      '停用客户',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await mdmApi.disableCustomer(row.id)
    ElMessage.success('客户已停用')
    await loadCustomers()
  } catch {
    // 拦截器已提示；catch 只为避免未处理的 Promise 拒绝
  }
}

async function enableCustomer(row: Customer) {
  try {
    await mdmApi.updateCustomer(row.id, {
      name: row.name,
      phone: row.phone ?? undefined,
      discountRate: row.discountRate,
      level: row.level || undefined,
      enabled: true,
    })
    ElMessage.success('客户已启用')
    await loadCustomers()
  } catch {
    // 同上
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">客户 / 会员</h2>
      <el-button v-if="canWrite" type="primary" @click="openCreate">新建客户</el-button>
    </div>
    <p class="page-hint">
      折扣以整数万分比保存（9000 = 9 折，10000 = 不打折）；停用不删除，历史出货单仍可追溯。
    </p>

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`客户查询失败：${listError}`"
    >
      <el-button size="small" @click="loadCustomers">重试</el-button>
    </el-alert>

    <PageTable
      :rows="customers"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!listError"
      :can-create="canWrite"
      create-text="新建第一个客户"
      empty-text="还没有客户"
      empty-hint="新建客户后，出货单就可以选择它，并按折扣等级计价。"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
      @create="openCreate"
    >
      <template #filters>
        <el-input
          v-model="keyword"
          placeholder="按客户编码、名称或电话搜索"
          clearable
          class="filter-input"
          @keyup.enter="searchCustomers"
          @clear="searchCustomers"
        />
        <el-button type="primary" @click="searchCustomers">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <el-table-column prop="code" label="客户编码" width="150" />
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column label="电话" width="150">
        <template #default="{ row }">{{ row.phone || '-' }}</template>
      </el-table-column>
      <el-table-column label="折扣" width="150">
        <template #default="{ row }">
          <span>{{ discountLabel(row.discountRate) }}</span>
          <span class="rate-raw">（{{ row.discountRate }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="等级" width="110">
        <template #default="{ row }">{{ row.level || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="canWrite" label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.enabled" link type="danger" @click="disableCustomer(row)">
            停用
          </el-button>
          <el-button v-else link type="success" @click="enableCustomer(row)">启用</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog
      v-model="dialog"
      :title="editing ? '编辑客户' : '新建客户'"
      width="520px"
      :close-on-click-modal="false"
      @open="clearValidate"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="客户编码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="!!editing"
            maxlength="32"
            show-word-limit
            placeholder="如 CUS-001"
          />
          <div v-if="editing" class="field-hint">编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" placeholder="如 张女士" />
        </el-form-item>
        <el-form-item label="电话" prop="phone">
          <el-input v-model="form.phone" maxlength="32" placeholder="可选" />
        </el-form-item>
        <el-form-item label="折扣率" prop="discountRate">
          <el-input v-model="form.discountRate" maxlength="5" placeholder="10000">
            <template #append>万分比</template>
          </el-input>
          <div class="field-hint">
            整数万分比，有效范围 1~10000（10000 = 不打折）；当前显示：{{ discountPreview }}
          </div>
        </el-form-item>
        <el-form-item label="等级" prop="level">
          <el-select
            v-model="form.level"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="留空默认 NORMAL"
            class="level-select"
          >
            <el-option label="NORMAL（普通）" value="NORMAL" />
            <el-option label="VIP" value="VIP" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <el-alert v-if="formError" type="error" show-icon :closable="false" :title="formError" />
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCustomer">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-input {
  width: 300px;
}

.field-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.rate-raw {
  color: #909399;
  font-size: 12px;
}

.level-select {
  width: 220px;
}

@media (max-width: 768px) {
  .filter-input,
  .level-select {
    width: 100%;
  }
}
</style>
