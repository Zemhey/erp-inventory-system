<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { skeletonApi, type DeptNode, type Position } from '@/api/skeleton'

/**
 * 组织架构（FR-V3-23）：部门树 + 岗位。
 *
 * ★ 部门树后端直接返回嵌套 children，前端用 el-table 的树形行渲染，不需要递归组件。
 * ★ 写操作（建/改/删）由拦截器自动带 X-Request-Id；后端有"不能挂自己下 / 有子部门或员工不可删"
 *   等业务校验，错误信息原样透出。
 */

const departments = ref<DeptNode[]>([])
const positions = ref<Position[]>([])
const deptFilter = ref<number | null>(null)

const loading = ref(false)
const error = ref('')

const includeDisabled = ref(false)

// ─────────────────────────── 加载 ───────────────────────────

async function loadDepartments() {
  loading.value = true
  error.value = ''
  try {
    const res = await skeletonApi.departmentTree(includeDisabled.value)
    departments.value = res.items
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    error.value = e?.response?.data?.message || e?.message || '部门加载失败'
  } finally {
    loading.value = false
  }
}

async function loadPositions() {
  const res = await skeletonApi.positions(deptFilter.value ?? undefined)
  positions.value = res.items
}

function onIncludeDisabledChange() {
  void loadDepartments()
}

function onDeptFilterChange() {
  void loadPositions()
}

/** 扁平化部门树，给"上级部门"下拉用。 */
const flatDepartments = computed(() => {
  const out: DeptNode[] = []
  const walk = (list: DeptNode[]) => {
    for (const d of list) {
      out.push(d)
      if (d.children?.length) walk(d.children)
    }
  }
  walk(departments.value)
  return out
})

// ─────────────────────────── 部门对话框 ───────────────────────────

const deptDialog = reactive({
  visible: false,
  editing: false,
  id: 0,
  code: '',
  name: '',
  parentId: null as number | null,
  sortNo: 0,
})

function openCreateDept() {
  deptDialog.editing = false
  deptDialog.id = 0
  deptDialog.code = ''
  deptDialog.name = ''
  deptDialog.parentId = null
  deptDialog.sortNo = 0
  deptDialog.visible = true
}

function openEditDept(row: DeptNode) {
  deptDialog.editing = true
  deptDialog.id = row.id
  deptDialog.code = ''
  deptDialog.name = row.name
  deptDialog.parentId = row.parentId
  deptDialog.sortNo = row.sortNo
  deptDialog.visible = true
}

async function submitDept() {
  try {
    if (deptDialog.editing) {
      await skeletonApi.updateDepartment(deptDialog.id, {
        name: deptDialog.name,
        parentId: deptDialog.parentId,
        sortNo: deptDialog.sortNo,
        enabled: true,
      })
      ElMessage.success('部门已更新')
    } else {
      await skeletonApi.createDepartment({
        code: deptDialog.code,
        name: deptDialog.name,
        parentId: deptDialog.parentId,
        sortNo: deptDialog.sortNo,
      })
      ElMessage.success('部门已创建')
    }
    deptDialog.visible = false
    await loadDepartments()
    await loadPositions()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

async function removeDept(row: DeptNode) {
  try {
    await skeletonApi.deleteDepartment(row.id)
    ElMessage.success('部门已删除')
    await loadDepartments()
    await loadPositions()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
  }
}

// ─────────────────────────── 岗位对话框 ───────────────────────────

const posDialog = reactive({
  visible: false,
  editing: false,
  id: 0,
  departmentId: null as number | null,
  code: '',
  name: '',
  level: 1,
})

function openCreatePos() {
  posDialog.editing = false
  posDialog.id = 0
  posDialog.departmentId = deptFilter.value
  posDialog.code = ''
  posDialog.name = ''
  posDialog.level = 1
  posDialog.visible = true
}

function openEditPos(row: Position) {
  posDialog.editing = true
  posDialog.id = row.id
  posDialog.departmentId = row.departmentId
  posDialog.code = ''
  posDialog.name = row.name
  posDialog.level = row.level
  posDialog.visible = true
}

async function submitPos() {
  try {
    if (posDialog.editing) {
      await skeletonApi.updatePosition(posDialog.id, {
        departmentId: posDialog.departmentId ?? undefined,
        name: posDialog.name,
        level: posDialog.level,
        enabled: true,
      })
      ElMessage.success('岗位已更新')
    } else {
      await skeletonApi.createPosition({
        departmentId: posDialog.departmentId!,
        code: posDialog.code,
        name: posDialog.name,
        level: posDialog.level,
      })
      ElMessage.success('岗位已创建')
    }
    posDialog.visible = false
    await loadPositions()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}

async function removePos(row: Position) {
  try {
    await skeletonApi.deletePosition(row.id)
    ElMessage.success('岗位已删除')
    await loadPositions()
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
  }
}

onMounted(() => {
  void loadDepartments()
  void loadPositions()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">组织架构</h2>
      <div>
        <el-button @click="loadDepartments">刷新</el-button>
        <el-button type="primary" @click="openCreateDept">新建部门</el-button>
        <el-button type="primary" @click="openCreatePos">新建岗位</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`部门加载失败：${error}`"
    >
      <el-button size="small" @click="loadDepartments">重试</el-button>
    </el-alert>

    <el-checkbox v-model="includeDisabled" class="mb-16" @change="onIncludeDisabledChange">
      包含已停用的部门
    </el-checkbox>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="13" class="mb-16">
        <el-card shadow="never">
          <template #header><span>部门树</span></template>
          <el-table
            v-loading="loading"
            :data="departments"
            row-key="id"
            :tree-props="{ children: 'children' }"
            border
            size="small"
            default-expand-all
          >
            <el-table-column label="编码" prop="code" width="110" />
            <el-table-column label="名称" prop="name" min-width="140" />
            <el-table-column label="排序" prop="sortNo" width="70" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditDept(row)">编辑</el-button>
                <el-popconfirm title="确认删除该部门？" @confirm="removeDept(row)">
                  <template #reference>
                    <el-button link type="danger">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="11" class="mb-16">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>岗位</span>
              <el-select
                v-model="deptFilter"
                placeholder="全部部门"
                clearable
                size="small"
                class="dept-select"
                @change="onDeptFilterChange"
              >
                <el-option
                  v-for="d in flatDepartments"
                  :key="d.id"
                  :label="`${d.name}（${d.code}）`"
                  :value="d.id"
                />
              </el-select>
            </div>
          </template>
          <el-table :data="positions" border size="small">
            <el-table-column label="编码" prop="code" width="110" />
            <el-table-column label="名称" prop="name" min-width="120" />
            <el-table-column label="级别" prop="level" width="70" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEditPos(row)">编辑</el-button>
                <el-popconfirm title="确认删除该岗位？" @confirm="removePos(row)">
                  <template #reference>
                    <el-button link type="danger">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 部门对话框 -->
    <el-dialog v-model="deptDialog.visible" :title="deptDialog.editing ? '编辑部门' : '新建部门'" width="420px">
      <el-form label-width="80px">
        <el-form-item v-if="!deptDialog.editing" label="编码" required>
          <el-input v-model="deptDialog.code" placeholder="部门编码，唯一" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="deptDialog.name" placeholder="部门名称" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="deptDialog.parentId" placeholder="无（顶级部门）" clearable>
            <el-option
              v-for="d in flatDepartments.filter((d) => d.id !== deptDialog.id)"
              :key="d.id"
              :label="`${d.name}（${d.code}）`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="deptDialog.sortNo" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deptDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitDept">保存</el-button>
      </template>
    </el-dialog>

    <!-- 岗位对话框 -->
    <el-dialog v-model="posDialog.visible" :title="posDialog.editing ? '编辑岗位' : '新建岗位'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="部门" required>
          <el-select v-model="posDialog.departmentId" placeholder="选择部门">
            <el-option
              v-for="d in flatDepartments"
              :key="d.id"
              :label="`${d.name}（${d.code}）`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!posDialog.editing" label="编码" required>
          <el-input v-model="posDialog.code" placeholder="岗位编码，唯一" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="posDialog.name" placeholder="岗位名称" />
        </el-form-item>
        <el-form-item label="级别">
          <el-input-number v-model="posDialog.level" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="posDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="submitPos">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dept-select {
  width: 180px;
}
</style>
