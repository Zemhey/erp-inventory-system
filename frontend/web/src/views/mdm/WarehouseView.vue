<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  mdmApi,
  type Warehouse,
  type WarehouseCreate,
  type WarehouseLocation,
  type WarehouseLocationCreate,
  type WarehouseLocationReconcile,
  type WarehouseLocationUpdate,
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
const listError = ref('')
const filtered = computed(() => onlyEnabled.value)

/** 拦截器已经弹过统一 toast；这里把消息留在页面上，便于刷新后回看与重试。 */
function errorMessage(error: unknown): string {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function loadWarehouses() {
  loading.value = true
  listError.value = ''
  try {
    warehouses.value = await mdmApi.listWarehouses(onlyEnabled.value)
  } catch (error) {
    warehouses.value = []
    listError.value = errorMessage(error)
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
const formError = ref('')
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
  formError.value = ''
  Object.assign(form, { code: '', name: '', address: '', enabled: true })
  dialog.value = true
}

function openEdit(row: Warehouse) {
  editing.value = row
  formError.value = ''
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
  formError.value = ''
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
  } catch (error) {
    formError.value = errorMessage(error)
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
  try {
    await mdmApi.disableWarehouse(row.id)
    ElMessage.success('仓库已停用')
    await loadWarehouses()
  } catch {
    // 拦截器已提示；catch 只为避免未处理的 Promise 拒绝
  }
}

async function enableWarehouse(row: Warehouse) {
  try {
    await mdmApi.updateWarehouse(row.id, {
      name: row.name,
      address: row.address ?? undefined,
      enabled: true,
    })
    ElMessage.success('仓库已启用')
    await loadWarehouses()
  } catch {
    // 同上
  }
}

// ─────────────────────────── 库位与容量（V18 · B2） ───────────────────────────

const locationDrawer = ref(false)
const locationWarehouse = ref<Warehouse | null>(null)
const locationList = ref<WarehouseLocation[]>([])
const locationLoading = ref(false)
const locationError = ref('')
const locationOnlyEnabled = ref(false)
const reconcile = ref<WarehouseLocationReconcile | null>(null)

const locationDialog = ref(false)
const editingLocation = ref<WarehouseLocation | null>(null)
const locationFormRef = ref<FormInstance>()
const locationSaving = ref(false)
const locationFormError = ref('')
const locationForm = reactive({
  locationCode: '',
  name: '',
  capacityQty: 1,
})

const locationRules: FormRules = {
  locationCode: [
    { required: true, message: '请输入库位编码', trigger: 'blur' },
    { max: 32, message: '库位编码最多 32 个字符', trigger: 'blur' },
  ],
  name: [{ max: 64, message: '名称最多 64 个字符', trigger: 'blur' }],
  capacityQty: [{ required: true, message: '请输入库位容量', trigger: 'blur' }],
}

const capacityDialog = ref(false)
const capacitySaving = ref(false)
const capacityError = ref('')
const capacityForm = reactive({ capacityQty: 0 })

const reconcileTitle = computed(() => {
  if (!reconcile.value) return '容量核对'
  return reconcile.value.consistent ? '容量核对：一致' : '容量核对：不一致'
})

const reconcileDetail = computed(() => {
  const r = reconcile.value
  if (!r) return ''
  const capacity = r.warehouseCapacityQty === 0 ? '未设定' : String(r.warehouseCapacityQty)
  return (
    `仓库容量 ${capacity} / 启用库位合计 ${r.locationCapacityQty} / 差额 ${r.difference}` +
    `（启用 ${r.enabledLocationCount} 个，共 ${r.totalLocationCount} 个）`
  )
})

function openLocations(row: Warehouse) {
  locationWarehouse.value = row
  locationOnlyEnabled.value = false
  locationDrawer.value = true
  loadLocations()
}

async function loadLocations() {
  if (!locationWarehouse.value) return
  locationLoading.value = true
  locationError.value = ''
  try {
    const warehouseId = locationWarehouse.value.id
    const [items, check] = await Promise.all([
      mdmApi.listWarehouseLocations(warehouseId, locationOnlyEnabled.value),
      mdmApi.reconcileWarehouseLocations(warehouseId),
    ])
    locationList.value = items
    reconcile.value = check
    // 容量可能刚被其他页面改过：同步列表里的仓库行，避免抽屉头部显示旧值
    const row = warehouses.value.find((w) => w.id === warehouseId)
    if (row) row.capacityQty = check.warehouseCapacityQty
    locationWarehouse.value = row ?? locationWarehouse.value
  } catch (error) {
    locationList.value = []
    locationError.value = errorMessage(error)
  } finally {
    locationLoading.value = false
  }
}

function clearLocationValidate() {
  locationFormRef.value?.clearValidate()
}

function openCreateLocation() {
  editingLocation.value = null
  locationFormError.value = ''
  Object.assign(locationForm, { locationCode: '', name: '', capacityQty: 1 })
  locationDialog.value = true
}

function openEditLocation(location: WarehouseLocation) {
  editingLocation.value = location
  locationFormError.value = ''
  Object.assign(locationForm, {
    locationCode: location.locationCode,
    name: location.name ?? '',
    capacityQty: location.capacityQty,
  })
  locationDialog.value = true
}

async function saveLocation() {
  if (!locationWarehouse.value || !locationFormRef.value) return
  const valid = await locationFormRef.value.validate().catch(() => false)
  if (!valid) return

  locationSaving.value = true
  locationFormError.value = ''
  try {
    const warehouseId = locationWarehouse.value.id
    if (editingLocation.value) {
      const body: WarehouseLocationUpdate = {
        name: locationForm.name.trim() || undefined,
        capacityQty: Number(locationForm.capacityQty),
      }
      await mdmApi.updateWarehouseLocation(warehouseId, editingLocation.value.id, body)
      ElMessage.success('库位已更新')
    } else {
      const body: WarehouseLocationCreate = {
        locationCode: locationForm.locationCode.trim(),
        name: locationForm.name.trim() || undefined,
        capacityQty: Number(locationForm.capacityQty),
        enabled: true,
      }
      await mdmApi.createWarehouseLocation(warehouseId, body)
      ElMessage.success('库位已创建')
    }
    locationDialog.value = false
    await loadLocations()
  } catch (error) {
    locationFormError.value = errorMessage(error)
  } finally {
    locationSaving.value = false
  }
}

async function disableLocation(location: WarehouseLocation) {
  if (!locationWarehouse.value) return
  try {
    await ElMessageBox.confirm(
      `确定停用库位「${location.locationCode}」吗？停用不删除，历史库存与流水不受影响；` +
        '该库位容量将从仓库已分配容量中扣除。',
      '停用库位',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await mdmApi.disableWarehouseLocation(locationWarehouse.value.id, location.id)
    ElMessage.success('库位已停用')
    await loadLocations()
  } catch {
    // 拦截器已提示
  }
}

async function enableLocation(location: WarehouseLocation) {
  if (!locationWarehouse.value) return
  try {
    await mdmApi.enableWarehouseLocation(locationWarehouse.value.id, location.id)
    ElMessage.success('库位已启用')
    await loadLocations()
  } catch {
    // 同上
  }
}

function openCapacityDialog() {
  capacityError.value = ''
  capacityForm.capacityQty = locationWarehouse.value?.capacityQty ?? 0
  capacityDialog.value = true
}

async function saveCapacity() {
  if (!locationWarehouse.value) return
  capacitySaving.value = true
  capacityError.value = ''
  try {
    const updated = await mdmApi.setWarehouseCapacity(
      locationWarehouse.value.id,
      Number(capacityForm.capacityQty),
    )
    const row = warehouses.value.find((w) => w.id === updated.id)
    if (row) row.capacityQty = updated.capacityQty
    locationWarehouse.value = row ?? locationWarehouse.value
    capacityDialog.value = false
    ElMessage.success('仓库容量已更新')
    await loadLocations()
  } catch (error) {
    capacityError.value = errorMessage(error)
  } finally {
    capacitySaving.value = false
  }
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

    <el-alert
      v-if="listError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`仓库查询失败：${listError}`"
    >
      <el-button size="small" @click="loadWarehouses">重试</el-button>
    </el-alert>

    <PageTable
      :rows="warehouses"
      :loading="loading"
      :paginated="false"
      :filtered="filtered"
      :load-failed="!!listError"
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
      <el-table-column label="容量" width="120">
        <template #default="{ row }">
          {{ row.capacityQty > 0 ? `${row.capacityQty} 件` : '未设定' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openLocations(row)">库位</el-button>
          <template v-if="canWrite">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.enabled" link type="danger" @click="disableWarehouse(row)">
              停用
            </el-button>
            <el-button v-else link type="success" @click="enableWarehouse(row)">启用</el-button>
          </template>
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
      <el-alert v-if="formError" type="error" show-icon :closable="false" :title="formError" />
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveWarehouse">保存</el-button>
      </template>
    </el-dialog>

    <!-- 仓库库位与容量守恒核对 -->
    <el-drawer v-model="locationDrawer" size="75%" :with-header="true">
      <template #header>
        <div class="drawer-header">
          <div>
            <div class="drawer-title">
              {{ locationWarehouse?.code }} · 库位
            </div>
            <div class="text-muted">共 {{ locationList.length }} 个库位</div>
          </div>
          <el-button v-if="canWrite" type="primary" @click="openCreateLocation">
            新建库位
          </el-button>
        </div>
      </template>

      <div class="drawer-toolbar">
        <span class="capacity-label">
          仓库容量：{{ (locationWarehouse?.capacityQty ?? 0) > 0
            ? `${locationWarehouse?.capacityQty} 件`
            : '未设定' }}
        </span>
        <el-button v-if="canWrite" @click="openCapacityDialog">设置容量</el-button>
        <el-checkbox v-model="locationOnlyEnabled" @change="loadLocations">
          仅看启用库位
        </el-checkbox>
        <el-button @click="loadLocations">刷新</el-button>
      </div>

      <el-alert
        v-if="reconcile"
        :type="reconcile.consistent ? 'success' : 'warning'"
        :title="reconcileTitle"
        :closable="false"
        show-icon
        class="mb-16"
      >
        <div>{{ reconcileDetail }}</div>
      </el-alert>

      <el-alert
        v-if="locationError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
        :title="`库位查询失败：${locationError}`"
      >
        <el-button size="small" @click="loadLocations">重试</el-button>
      </el-alert>

      <el-empty
        v-else-if="!locationLoading && locationList.length === 0"
        description="该仓库还没有库位"
      >
        <el-button v-if="canWrite" type="primary" @click="openCreateLocation">
          新建第一个库位
        </el-button>
      </el-empty>

      <el-table v-else v-loading="locationLoading" :data="locationList" border>
        <el-table-column prop="locationCode" label="库位编码" width="130" />
        <el-table-column prop="name" label="名称" min-width="160">
          <template #default="{ row }">{{ row.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="容量" width="120" align="right">
          <template #default="{ row }">{{ row.capacityQty }} 件</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditLocation(row)">编辑</el-button>
            <el-button v-if="row.enabled" link type="danger" @click="disableLocation(row)">
              停用
            </el-button>
            <el-button v-else link type="success" @click="enableLocation(row)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog
        v-model="locationDialog"
        :title="editingLocation ? '编辑库位' : '新建库位'"
        width="520px"
        :close-on-click-modal="false"
        append-to-body
        @open="clearLocationValidate"
      >
        <el-form
          ref="locationFormRef"
          :model="locationForm"
          :rules="locationRules"
          label-width="100px"
        >
          <el-form-item label="库位编码" prop="locationCode">
            <el-input
              v-model="locationForm.locationCode"
              :disabled="!!editingLocation"
              maxlength="32"
              placeholder="如 A-01"
            />
            <div v-if="editingLocation" class="field-hint">库位编码创建后不可修改</div>
          </el-form-item>
          <el-form-item label="名称" prop="name">
            <el-input v-model="locationForm.name" maxlength="64" placeholder="如 A 区 01" />
          </el-form-item>
          <el-form-item label="容量" prop="capacityQty">
            <el-input-number v-model="locationForm.capacityQty" :min="1" :step="1" />
            <span class="field-hint inline">件；启用库位合计不得超过仓库容量</span>
          </el-form-item>
        </el-form>
        <el-alert
          v-if="locationFormError"
          type="error"
          show-icon
          :closable="false"
          :title="locationFormError"
        />
        <template #footer>
          <el-button @click="locationDialog = false">取消</el-button>
          <el-button type="primary" :loading="locationSaving" @click="saveLocation">
            保存
          </el-button>
        </template>
      </el-dialog>

      <el-dialog
        v-model="capacityDialog"
        title="设置仓库容量"
        width="460px"
        :close-on-click-modal="false"
        append-to-body
      >
        <el-form label-width="100px">
          <el-form-item label="仓库容量">
            <el-input-number v-model="capacityForm.capacityQty" :min="0" :step="1" />
            <span class="field-hint inline">件；0 = 未设定（不限制库位容量）</span>
          </el-form-item>
        </el-form>
        <el-alert
          v-if="capacityError"
          type="error"
          show-icon
          :closable="false"
          :title="capacityError"
        />
        <template #footer>
          <el-button @click="capacityDialog = false">取消</el-button>
          <el-button type="primary" :loading="capacitySaving" @click="saveCapacity">
            保存
          </el-button>
        </template>
      </el-dialog>
    </el-drawer>
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

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
}

.drawer-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.capacity-label {
  font-weight: 600;
}

.field-hint.inline {
  display: inline-block;
  margin-left: 8px;
  margin-top: 0;
}

@media (max-width: 768px) {
  .filter-summary {
    margin-left: 0;
  }
}
</style>
