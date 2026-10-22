<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  type Supplier,
  type SupplierCreate,
  type SupplierUpdate,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 供应商档案（FR-MDM-02）。
 *
 * 列表 + 新建/编辑 + 停用/启用。编码创建后不可改；
 * 停用不删除，历史进货单仍可追溯，但新单据不能再选择。
 *
 * ★ 前端只做展示与触发：编码唯一、能否停用等规则全部以后端为准。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('mdm:write'))

// ─────────────────────────── 列表 ───────────────────────────

const suppliers = ref<Supplier[]>([])
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

async function loadSuppliers() {
  loading.value = true
  listError.value = ''
  try {
    const data = await mdmApi.listSuppliers({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: pageSize.value,
    })
    suppliers.value = data.items
    total.value = data.total
  } catch (error) {
    suppliers.value = []
    total.value = 0
    listError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function onPageChange(nextPage: number, nextPageSize: number) {
  page.value = nextPage
  pageSize.value = nextPageSize
  loadSuppliers()
}

function searchSuppliers() {
  page.value = 1
  loadSuppliers()
}

function resetFilters() {
  keyword.value = ''
  page.value = 1
  loadSuppliers()
}

onMounted(loadSuppliers)

// ─────────────────────────── 新建 / 编辑 ───────────────────────────

const dialog = ref(false)
const editing = ref<Supplier | null>(null)
const formRef = ref<FormInstance>()
const saving = ref(false)
const formError = ref('')
const form = reactive({
  code: '',
  name: '',
  contactName: '',
  contactPhone: '',
  address: '',
  enabled: true,
})

const rules: FormRules = {
  code: [
    { required: true, message: '请输入供应商编码', trigger: 'blur' },
    { min: 2, max: 32, message: '编码长度 2~32 个字符', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入供应商名称', trigger: 'blur' },
    { max: 128, message: '名称最多 128 个字符', trigger: 'blur' },
  ],
  contactName: [{ max: 64, message: '联系人最多 64 个字符', trigger: 'blur' }],
  contactPhone: [{ max: 32, message: '联系电话最多 32 个字符', trigger: 'blur' }],
  address: [{ max: 200, message: '地址最多 200 个字符', trigger: 'blur' }],
}

function clearValidate() {
  formRef.value?.clearValidate()
}

function openCreate() {
  editing.value = null
  formError.value = ''
  Object.assign(form, {
    code: '',
    name: '',
    contactName: '',
    contactPhone: '',
    address: '',
    enabled: true,
  })
  dialog.value = true
}

function openEdit(row: Supplier) {
  editing.value = row
  formError.value = ''
  Object.assign(form, {
    code: row.code,
    name: row.name,
    contactName: row.contactName ?? '',
    contactPhone: row.contactPhone ?? '',
    address: row.address ?? '',
    enabled: row.enabled,
  })
  dialog.value = true
}

async function saveSupplier() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  formError.value = ''
  try {
    const common: SupplierUpdate = {
      name: form.name.trim(),
      contactName: form.contactName.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      address: form.address.trim() || undefined,
      enabled: form.enabled,
    }
    if (editing.value) {
      await mdmApi.updateSupplier(editing.value.id, common)
      ElMessage.success('供应商已更新')
    } else {
      const createBody: SupplierCreate = { ...common, code: form.code.trim() }
      await mdmApi.createSupplier(createBody)
      ElMessage.success('供应商已创建')
    }
    dialog.value = false
    await loadSuppliers()
  } catch (error) {
    // 后端消息含定位信息（如编码重复），原样留在弹窗里，用户不用去追 toast
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function disableSupplier(row: Supplier) {
  try {
    await ElMessageBox.confirm(
      `确定停用供应商「${row.name}」吗？停用后新进货单不能再选择它，历史单据不受影响。`,
      '停用供应商',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await mdmApi.disableSupplier(row.id)
    ElMessage.success('供应商已停用')
    await loadSuppliers()
  } catch {
    // 拦截器已提示；catch 只为避免未处理的 Promise 拒绝
  }
}

async function enableSupplier(row: Supplier) {
  try {
    await mdmApi.updateSupplier(row.id, {
      name: row.name,
      contactName: row.contactName ?? undefined,
      contactPhone: row.contactPhone ?? undefined,
      address: row.address ?? undefined,
      enabled: true,
    })
    ElMessage.success('供应商已启用')
    await loadSuppliers()
  } catch {
    // 同上
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">供应商</h2>
      <el-button v-if="canWrite" type="primary" @click="openCreate">新建供应商</el-button>
    </div>
    <p class="page-hint">
      维护进货来源；停用不删除，历史进货单仍可追溯，但新单据不能再选择。
    </p>

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`供应商查询失败：${listError}`"
    >
      <el-button size="small" @click="loadSuppliers">重试</el-button>
    </el-alert>

    <PageTable
      :rows="suppliers"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!listError"
      :can-create="canWrite"
      create-text="新建第一个供应商"
      empty-text="还没有供应商"
      empty-hint="新建供应商后，进货单就可以选择它。"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
      @create="openCreate"
    >
      <template #filters>
        <el-input
          v-model="keyword"
          placeholder="按供应商编码或名称搜索"
          clearable
          class="filter-input"
          @keyup.enter="searchSuppliers"
          @clear="searchSuppliers"
        />
        <el-button type="primary" @click="searchSuppliers">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <el-table-column prop="code" label="供应商编码" width="150" />
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column label="联系人" width="120">
        <template #default="{ row }">{{ row.contactName || '-' }}</template>
      </el-table-column>
      <el-table-column label="联系电话" width="150">
        <template #default="{ row }">{{ row.contactPhone || '-' }}</template>
      </el-table-column>
      <el-table-column label="地址" min-width="200">
        <template #default="{ row }">{{ row.address || '-' }}</template>
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
          <el-button v-if="row.enabled" link type="danger" @click="disableSupplier(row)">
            停用
          </el-button>
          <el-button v-else link type="success" @click="enableSupplier(row)">启用</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog
      v-model="dialog"
      :title="editing ? '编辑供应商' : '新建供应商'"
      width="520px"
      :close-on-click-modal="false"
      @open="clearValidate"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="供应商编码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="!!editing"
            maxlength="32"
            show-word-limit
            placeholder="如 SUP-001"
          />
          <div v-if="editing" class="field-hint">编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="128" placeholder="如 杭州面料有限公司" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactName">
          <el-input v-model="form.contactName" maxlength="64" placeholder="可选" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="32" placeholder="可选" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" maxlength="200" placeholder="可选" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <el-alert v-if="formError" type="error" show-icon :closable="false" :title="formError" />
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSupplier">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-input {
  width: 280px;
}

.field-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

@media (max-width: 768px) {
  .filter-input {
    width: 100%;
  }
}
</style>
