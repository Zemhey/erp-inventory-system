<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  iamApi,
  type DataScopeView,
  type EmployeeView,
  type RoleView,
} from '@/api/client'
import { skeletonApi, type DeptNode, type Position } from '@/api/skeleton'
import { useSessionStore } from '@/stores/session'

/**
 * 员工与角色管理（FR-IAM-05）+ 数据权限配置（FR-IAM-04）。
 *
 * ★ 这里把"账号"升级成了"员工"：
 *   原来只有用户名 + 显示名 + 角色，列表看起来像一堆登录账号，
 *   而实际上一家公司的人有工号、部门、岗位、手机、入职日期。
 *   账号是"能不能登录"，档案是"这个人是谁"，两者分开维护但又必须在同一页，
 *   否则人事改个手机号还得找 IT。
 *
 * ★ 本页最重要的一句话写在界面顶部：**改完立即生效，不需要对方重新登录**。
 *   实现机制在后端 —— {@code JwtAuthenticationFilter} 与 {@code PermissionAspect}
 *   每次请求都回库重读权限并集。
 *
 * ★ 前端只是体验层：真正的判定在后端。本页隐藏按钮只是因为"没权限的人点了也是 403"，
 *   不是安全边界。
 */

const session = useSessionStore()

const loading = ref(false)
const employees = ref<EmployeeView[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const deptFilter = ref<number | undefined>(undefined)
const roles = ref<RoleView[]>([])

/** 部门树（下拉用）+ 扁平化后的列表（带层级缩进） */
const deptTree = ref<DeptNode[]>([])
const flatDepts = computed(() => {
  const out: { id: number; name: string; depth: number }[] = []
  const walk = (list: DeptNode[], depth: number) => {
    for (const d of list) {
      out.push({ id: d.id, name: d.name, depth })
      if (d.children?.length) walk(d.children, depth + 1)
    }
  }
  walk(deptTree.value, 0)
  return out
})
const positions = ref<Position[]>([])

const activeTab = ref('employees')

const canWriteUser = computed(() => session.hasPermission('iam:user:write'))
const canWriteRole = computed(() => session.hasPermission('iam:role:write'))
const canWriteScope = computed(() => session.hasPermission('iam:scope:write'))

// ── 员工新建 / 编辑 ──
const userDialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  username: '',
  displayName: '',
  password: '',
  employeeNo: '',
  departmentId: undefined as number | undefined,
  positionId: undefined as number | undefined,
  phone: '',
  email: '',
  hireDate: '' as string,
  remark: '',
  selectedRoles: [] as string[],
})

// ── 角色编辑 ──
const roleDialog = reactive({
  visible: false,
  id: 0,
  code: '',
  name: '',
  description: '',
  permissionsText: '',
})

// ── 数据权限编辑 ──
const scopeDialog = reactive({
  visible: false,
  userId: 0,
  username: '',
  entries: '',
  direct: [] as string[],
  inherited: [] as string[],
})

async function load() {
  loading.value = true
  try {
    const [empPage, roleList, tree] = await Promise.all([
      iamApi.employees({
        keyword: keyword.value.trim() || undefined,
        departmentId: deptFilter.value,
        page: page.value,
        size: pageSize.value,
      }),
      iamApi.roles(),
      skeletonApi.departmentTree(true),
    ])
    employees.value = empPage.items
    total.value = empPage.total
    roles.value = roleList.items
    deptTree.value = tree.items
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void load()
}

function onPageChange(p: number, size: number) {
  page.value = p
  pageSize.value = size
  void load()
}

/** 岗位随部门变化：先按部门取，再回填。 */
async function loadPositions(deptId?: number) {
  try {
    const res = await skeletonApi.positions(deptId)
    positions.value = res.items
  } catch {
    positions.value = []
  }
}

function openCreate() {
  Object.assign(userDialog, {
    visible: true,
    mode: 'create',
    id: 0,
    username: '',
    displayName: '',
    password: '',
    employeeNo: '',
    departmentId: deptFilter.value,
    positionId: undefined,
    phone: '',
    email: '',
    hireDate: '',
    remark: '',
    selectedRoles: [],
  })
  void loadPositions(deptFilter.value)
}

function openEdit(row: EmployeeView) {
  Object.assign(userDialog, {
    visible: true,
    mode: 'edit',
    id: row.id,
    username: row.username,
    displayName: row.displayName,
    password: '',
    employeeNo: row.employeeNo ?? '',
    departmentId: row.departmentId ?? undefined,
    positionId: row.positionId ?? undefined,
    phone: row.phone ?? '',
    email: row.email ?? '',
    hireDate: row.hireDate ?? '',
    remark: row.remark ?? '',
    selectedRoles: [...row.roles],
  })
  void loadPositions(row.departmentId ?? undefined)
}

function onDialogDeptChange(deptId?: number) {
  userDialog.positionId = undefined
  void loadPositions(deptId)
}

async function saveUser() {
  if (userDialog.mode === 'create' && !userDialog.password) {
    ElMessage.warning('请填写初始密码')
    return
  }
  const profile = {
    employeeNo: userDialog.employeeNo || undefined,
    departmentId: userDialog.departmentId ?? null,
    positionId: userDialog.positionId ?? null,
    phone: userDialog.phone || undefined,
    email: userDialog.email || undefined,
    hireDate: userDialog.hireDate || null,
    remark: userDialog.remark || undefined,
  }
  try {
    if (userDialog.mode === 'create') {
      await iamApi.createUser({
        username: userDialog.username,
        password: userDialog.password,
        displayName: userDialog.displayName,
        roles: userDialog.selectedRoles,
        ...profile,
      })
      ElMessage.success('已创建员工账号。该账号首次登录将被强制修改密码。')
    } else {
      await iamApi.updateEmployee(userDialog.id, profile)
      // 档案与角色是两块信息：这里顺带把角色也提交，避免用户改完档案发现角色没保存
      await iamApi.updateRoles(userDialog.id, userDialog.selectedRoles)
      ElMessage.success('档案已更新，角色变更立即生效')
    }
    userDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

async function toggleEnabled(row: EmployeeView) {
  try {
    await ElMessageBox.confirm(
      `确定${row.enabled ? '停用' : '启用'}账号「${row.username}」吗？`,
      '账号状态',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await iamApi.setEnabled(row.id, !row.enabled)
    ElMessage.success('已更新，立即生效')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function resetPassword(row: EmployeeView) {
  let password = ''
  try {
    const input = await ElMessageBox.prompt(
      `为「${row.displayName || row.username}」设置新密码（至少 8 位、3 类字符）：`,
      '重置密码',
      { confirmButtonText: '重置', cancelButtonText: '取消', inputPlaceholder: '新密码' },
    )
    password = input.value ?? ''
  } catch {
    return
  }
  try {
    await iamApi.resetPassword(row.id, password)
    ElMessage.success('已重置。用户下次登录必须先改密（系统不会回显明文）。')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重置失败')
  }
}

// ── 角色 ──

function openRole(row?: RoleView) {
  Object.assign(roleDialog, {
    visible: true,
    id: row?.id ?? 0,
    code: row?.code ?? '',
    name: row?.name ?? '',
    description: row?.description ?? '',
    permissionsText: '',
  })
  if (row) {
    iamApi
      .rolePermissions(row.id)
      .then((r) => {
        roleDialog.permissionsText = r.permissions.join('\n')
      })
      .catch(() => {
        /* 权限清单加载失败不影响改名 */
      })
  }
}

async function saveRole() {
  try {
    let roleId = roleDialog.id
    if (roleId === 0) {
      const created = await iamApi.createRole({
        code: roleDialog.code,
        name: roleDialog.name,
        description: roleDialog.description,
      })
      roleId = created.id
    }
    if (roleDialog.permissionsText.trim()) {
      const permissions = roleDialog.permissionsText
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean)
      await iamApi.replacePermissions(roleId, permissions)
    }
    ElMessage.success('已保存。权限变更立即生效（该角色下所有用户的下一次请求即按新权限判定）')
    roleDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

// ── 数据权限 ──

async function openScope(row: EmployeeView) {
  try {
    const data: DataScopeView = await iamApi.userScopes(row.id)
    Object.assign(scopeDialog, {
      visible: true,
      userId: row.id,
      username: row.username,
      direct: data.direct,
      inherited: data.inherited,
      entries: data.direct.join('\n'),
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载数据权限失败')
  }
}

async function saveScope() {
  try {
    const entries = scopeDialog.entries
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
    await iamApi.replaceUserScopes(scopeDialog.userId, entries)
    ElMessage.success('数据权限已更新，立即生效')
    scopeDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="iam-view">
    <el-alert
      type="success"
      :closable="false"
      show-icon
      title="授权即时生效"
      description="改角色 / 改权限 / 改数据范围后，对方的下一个请求就按新配置判定，不需要重新登录，
也不需要吊销 token —— 后端每个请求都回库重读权限。"
      style="margin-bottom: 16px"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="员工" name="employees">
        <el-card shadow="never">
          <template #header>
            <div class="header">
              <span>员工档案 · 共 {{ total }} 人</span>
              <div class="actions">
                <el-input
                  v-model="keyword"
                  placeholder="搜索姓名/用户名/工号/手机"
                  clearable
                  style="width: 230px"
                  @change="search"
                  @keyup.enter="search"
                />
                <el-select
                  v-model="deptFilter"
                  placeholder="全部部门"
                  clearable
                  style="width: 170px"
                  @change="search"
                >
                  <el-option
                    v-for="d in flatDepts"
                    :key="d.id"
                    :label="`${'　'.repeat(d.depth)}${d.name}`"
                    :value="d.id"
                  />
                </el-select>
                <el-button @click="search">刷新</el-button>
                <el-button v-if="canWriteUser" type="primary" @click="openCreate">
                  新建员工
                </el-button>
              </div>
            </div>
          </template>

          <el-table :data="employees" v-loading="loading" border>
            <el-table-column prop="employeeNo" label="工号" width="92">
              <template #default="{ row }">{{ row.employeeNo || '-' }}</template>
            </el-table-column>
            <el-table-column label="姓名" width="182">
              <template #default="{ row }">
                <div class="person" :title="row.email || ''">
                  <span class="person-name">{{ row.displayName || row.username }}</span>
                  <span class="person-account ellipsis">
                    {{ row.phone || row.username }}
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="部门" width="116">
              <template #default="{ row }">{{ row.departmentName || '未分配' }}</template>
            </el-table-column>
            <el-table-column label="岗位" width="104">
              <template #default="{ row }">{{ row.positionName || '-' }}</template>
            </el-table-column>
            <el-table-column label="角色" width="132">
              <template #default="{ row }">
                <el-tag
                  v-for="r in row.roles"
                  :key="r"
                  size="small"
                  type="primary"
                  effect="plain"
                  class="mr-4"
                >
                  {{ r }}
                </el-tag>
                <span v-if="row.roles.length === 0" class="hint">未分配</span>
              </template>
            </el-table-column>
            <el-table-column label="入职" width="104">
              <template #default="{ row }">{{ row.hireDate || '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="132">
              <template #default="{ row }">
                <div class="status-cell">
                  <el-tag :type="row.enabled ? 'success' : 'info'">
                    {{ row.enabled ? '在职' : '停用' }}
                  </el-tag>
                  <el-tag v-if="row.mustChangePassword" type="warning">待改密</el-tag>
                  <el-tag v-if="row.lockedUntil" type="danger">已锁定</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="252" fixed="right">
              <template #default="{ row }">
                <template v-if="canWriteUser">
                  <el-button size="small" @click="openEdit(row)">编辑</el-button>
                  <el-button size="small" @click="resetPassword(row)">重置密码</el-button>
                  <el-button size="small" @click="toggleEnabled(row)">
                    {{ row.enabled ? '停用' : '启用' }}
                  </el-button>
                </template>
                <el-button
                  v-if="canWriteScope"
                  size="small"
                  type="primary"
                  link
                  @click="openScope(row)"
                >
                  数据权限
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              :current-page="page"
              :page-size="pageSize"
              :total="total"
              :page-sizes="[20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              @update:current-page="(p: number) => onPageChange(p, pageSize)"
              @update:page-size="(s: number) => onPageChange(1, s)"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="角色与权限" name="roles">
        <el-card shadow="never">
          <template #header>
            <div class="header">
              <span>角色</span>
              <el-button v-if="canWriteRole" type="primary" @click="openRole()">新建角色</el-button>
            </div>
          </template>
          <el-table :data="roles" border>
            <el-table-column prop="code" label="编码" width="140" />
            <el-table-column prop="name" label="名称" width="160" />
            <el-table-column prop="description" label="说明" min-width="220" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button size="small" @click="openRole(row)">
                  {{ canWriteRole ? '编辑' : '查看' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 员工新建 / 编辑 -->
    <el-dialog
      v-model="userDialog.visible"
      :title="userDialog.mode === 'create' ? '新建员工' : '编辑员工档案'"
      width="620px"
    >
      <el-form label-width="90px">
        <el-row :gutter="8">
          <el-col :span="12">
            <el-form-item label="用户名">
              <el-input v-model="userDialog.username" :disabled="userDialog.mode === 'edit'" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="初始密码">
              <el-input
                v-if="userDialog.mode === 'create'"
                v-model="userDialog.password"
                type="password"
                show-password
                placeholder="至少 8 位、3 类字符"
              />
              <span v-else class="hint">用「重置密码」单独修改</span>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="8">
          <el-col :span="12">
            <el-form-item label="姓名">
              <el-input v-model="userDialog.displayName" placeholder="真实姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="工号">
              <el-input v-model="userDialog.employeeNo" placeholder="如 EMP-0012" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="8">
          <el-col :span="12">
            <el-form-item label="部门">
              <el-select
                v-model="userDialog.departmentId"
                clearable
                placeholder="选择部门"
                style="width: 100%"
                @change="onDialogDeptChange"
              >
                <el-option
                  v-for="d in flatDepts"
                  :key="d.id"
                  :label="`${'　'.repeat(d.depth)}${d.name}`"
                  :value="d.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="岗位">
              <el-select
                v-model="userDialog.positionId"
                clearable
                placeholder="选择岗位"
                style="width: 100%"
              >
                <el-option
                  v-for="p in positions"
                  :key="p.id"
                  :label="p.name"
                  :value="p.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="8">
          <el-col :span="12">
            <el-form-item label="手机">
              <el-input v-model="userDialog.phone" placeholder="11 位手机号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱">
              <el-input v-model="userDialog.email" placeholder="name@example.com" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="8">
          <el-col :span="12">
            <el-form-item label="入职日期">
              <el-date-picker
                v-model="userDialog.hireDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择日期"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="备注">
              <el-input v-model="userDialog.remark" placeholder="兼任岗位等" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="角色">
          <el-select v-model="userDialog.selectedRoles" multiple style="width: 100%">
            <el-option v-for="r in roles" :key="r.code" :label="`${r.code} · ${r.name}`" :value="r.code" />
          </el-select>
          <div class="hint">角色决定能看到哪些菜单、能做哪些操作；档案只影响列表展示。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色编辑 -->
    <el-dialog v-model="roleDialog.visible" title="角色" width="520px">
      <el-form label-width="90px">
        <el-form-item label="编码">
          <el-input v-model="roleDialog.code" :disabled="roleDialog.id !== 0" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="roleDialog.name" :disabled="!canWriteRole" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="roleDialog.description" :disabled="!canWriteRole" />
        </el-form-item>
        <el-form-item label="权限码">
          <el-input
            v-model="roleDialog.permissionsText"
            type="textarea"
            :rows="8"
            :disabled="!canWriteRole"
            placeholder="每行一个权限码，如 mdm:read / pur:approve / aps:read"
          />
          <div class="hint">覆盖式保存：删掉行即取消该权限（不是增量追加）</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialog.visible = false">取消</el-button>
        <el-button v-if="canWriteRole" type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据权限 -->
    <el-dialog v-model="scopeDialog.visible" :title="`数据权限 · ${scopeDialog.username}`" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="留空 = 全量可见"
        description="未配置任何范围时不做收窄。配置 WAREHOUSE 后只能看到这些仓；SELF 只能看到本人创建的单据。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="直接配置">
          <el-input
            v-model="scopeDialog.entries"
            type="textarea"
            :rows="4"
            :disabled="!canWriteScope"
            placeholder="每行一项：ALL / SELF / WAREHOUSE:12"
          />
          <div class="hint">每行一项：<code>ALL</code> / <code>SELF</code> / <code>WAREHOUSE:仓库ID</code></div>
        </el-form-item>
        <el-form-item label="继承自角色">
          <div v-if="scopeDialog.inherited.length === 0" class="hint">（无）</div>
          <div v-for="s in scopeDialog.inherited" :key="s" class="hint">{{ s }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scopeDialog.visible = false">取消</el-button>
        <el-button v-if="canWriteScope" type="primary" @click="saveScope">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.actions {
  display: flex;
  gap: 8px;
}
.hint {
  color: #909399;
  font-size: 12px;
}
.person {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.person-name {
  font-weight: 500;
}
.person-account {
  font-size: 12px;
  color: #909399;
}
/* 邮箱普遍比手机号长很多：不截断会把整行撑高，一屏少看五六个人 */
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 140px;
}
.mr-4 {
  margin-right: 4px;
}
/*
 * 全局主题把 .el-tag 的 border-color 设成 transparent（"小圆点+文字"风格），
 * 结果浅色底的标签在表格里几乎看不见 —— 角色列看着像"空的"。
 * 本页是信息密集的花名册，标签必须一眼可辨，所以把边框取当前字色补回来。
 */
:deep(.el-tag) {
  border-color: currentColor;
}
.ml-4 {
  margin-left: 4px;
}
/* 状态可能同时有"在职 + 待改密"：横排会挤成省略号，改成两行 */
.status-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
