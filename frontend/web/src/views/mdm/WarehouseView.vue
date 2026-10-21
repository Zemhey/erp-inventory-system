<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  type Warehouse,
  type WarehouseCreate,
  type WarehouseUpdate,
} from '@/api/client'
import PageTable from '@/components/PageTable.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 仓库管理（FR-MDM-04）。
 *
 * ★ 与商品/供应商不同，仓库列表**不分页**：后端返回数组。
 *   仓库数量是"家店有几个仓"，通常是几条到几十条，分页只会增加一次点击。
 *   PageTable 用 paginated=false 关闭分页条（计划书 §3.1）。
 *
 * ★ onlyEnabled 是服务端过滤参数，不是前端 filter()：
 *   前端过滤只能过滤当前页，而"停用的仓"可能根本不在这一页里。
 */

const session = useSessionStore()
const canWrite = computed(() => session.hasPermission('mdm:write'))

// ─────────────────────────── 列表 ───────────────────────────

const warehouses = ref<Warehouse[]>([])
const onlyEnabled = ref(false)
const loading = ref(false)
const filtered = computed(() => onlyEnabled.value)

async function loadWarehouses() {
  loading.value = true
  try {
    warehouses.value = await mdmApi.listWarehouses(onlyEnabled.value)
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  onlyEnabled.value = false
  loadWarehouses()
}

onMounted(loadWarehouses)

// ─────────────────────────── 新建 / 编辑 ───────────────────────────

const dialog = ref(false)
const editing = ref<Warehouse | null>(null)
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({
  code: '',
  name: '',
  address: '',
  enabled: true,
})

const rules: FormRules = {
  code: [
    { required: true, message: '请输入仓库编码', trigger: 'blur' },
    { min: 2, max: 32, message: '编码长度 2~32 个字符', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入仓库名称', trigger: 'blur' },
    { max: 64, message: '名称最多 64 个字符', trigger: 'blur' },
  ],
  address: [{ max: 200, message: '地址最多 200 个字符', trigger: 'blur' }],
}

function clearValidate() {
  formRef.value?.clearValidate()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', address: '', enabled: true })
  dialog.value = true
}

function openEdit(row: Warehouse) {
  editing.value = row
  Object.assign(form, {
    code: row.code,
    name: row.name,
    address: row.address ?? '',
    enabled: row.enabled,
  })
  dialog.value = true
}

async function saveWarehouse() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const common: WarehouseUpdate = {
      name: form.name.trim(),
      address: form.address.trim() || undefined,
      enabled: form.enabled,
    }
    if (editing.value) {
      await mdmApi.updateWarehouse(editing.value.id, common)
      ElMessage.success('仓库已更新')
    } else {
      const createBody: WarehouseCreate = { ...common, code: form.code.trim() }
      await mdmApi.createWarehouse(createBody)
      ElMessage.success('仓库已创建')
    }
    dialog.value = false
    await loadWarehouses()
  } finally {
    saving.value = false
  }
}

async function disableWarehouse(row: Warehouse) {
  try {
    await ElMessageBox.confirm(
      `确定停用仓库「${row.name}」吗？停用后新单据不能再选择它，历史单据与库存不受影响。`,
      '停用仓库',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await mdmApi.disableWarehouse(row.id)
  ElMessage.success('仓库已停用')
  await loadWarehouses()
}

async function enableWarehouse(row: Warehouse) {
  await mdmApi.updateWarehouse(row.id, {
    name: row.name,
    address: row.address ?? undefined,
    enabled: true,
  })
  ElMessage.success('仓库已启用')
  await loadWarehouses()
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">仓库</h2>
      <el-button v-if="canWrite" type="primary" @click="openCreate">新建仓库</el-button>
    </div>
    <p class="page-hint">
      库存余额的维度是（SKU × 仓库 × 批次）；停用不删除，历史单据与库存仍可追溯。
    </p>

    <PageTable
      :rows="warehouses"
      :loading="loading"
      :paginated="false"
      :filtered="filtered"
      :can-create="canWrite"
      create-text="新建第一个仓库"
      empty-text="还没有仓库"
      empty-hint="新建仓库后，进货、出货与库存查询才有存放维度。"
      @create="openCreate"
    >
      <template #filters>
        <el-checkbox v-model="onlyEnabled" @change="loadWarehouses">
          仅看启用仓库
        </el-checkbox>
        <el-button @click="resetFilters">重置</el-button>
        <span class="filter-summary">共 {{ warehouses.length }} 个</span>
      </template>

      <el-table-column prop="code" label="仓库编码" width="150" />
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column label="地址" min-width="240">
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
          <el-button v-if="row.enabled" link type="danger" @click="disableWarehouse(row)">
            停用
          </el-button>
          <el-button v-else link type="success" @click="enableWarehouse(row)">启用</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog
      v-model="dialog"
      :title="editing ? '编辑仓库' : '新建仓库'"
      width="520px"
      :close-on-click-modal="false"
      @open="clearValidate"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="仓库编码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="!!editing"
            maxlength="32"
            show-word-limit
            placeholder="如 WH-SH01"
          />
          <div v-if="editing" class="field-hint">编码创建后不可修改</div>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" placeholder="如 上海仓" />
        </el-form-item>
        <el-form-item label="地址" prop="address">
          <el-input v-model="form.address" maxlength="200" placeholder="可选" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWarehouse">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.field-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.filter-summary {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

@media (max-width: 768px) {
  .filter-summary {
    margin-left: 0;
  }
}
</style>
