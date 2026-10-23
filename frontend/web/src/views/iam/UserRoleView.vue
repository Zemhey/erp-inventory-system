<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  iamApi,
  type DataScopeView,
  type RoleView,
  type UserView,
} from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 用户与角色管理（FR-IAM-05）+ 数据权限配置（FR-IAM-04）。
 *
 * ★ 本页最重要的一句话写在界面顶部：**改完立即生效，不需要对方重新登录**。
 *   实现机制在后端 —— {@code JwtAuthenticationFilter} 与 {@code PermissionAspect}
 *   每次请求都回库重读权限并集。这里刻意不做"重新登录生效"的提示，
 *   因为那会让管理员以为改完还要通知用户，进而漏掉"我已经撤权了但对方还在操作"。
 *
 * ★ 前端只是体验层：真正的判定在后端。本页隐藏按钮只是因为"没权限的人点了也是 403"，
 *   不是安全边界。
 */

const session = useSessionStore()

const loading = ref(false)
const users = ref<UserView[]>([])
const roles = ref<RoleView[]>([])
const keyword = ref('')

const activeTab = ref('users')

const canWriteUser = computed(() => session.hasPermission('iam:user:write'))
const canWriteRole = computed(() => session.hasPermission('iam:role:write'))
const canWriteScope = computed(() => session.hasPermission('iam:scope:write'))

// ── 用户编辑 ──
const userDialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  username: '',
  displayName: '',
  password: '',
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
    const [userPage, roleList] = await Promise.all([
      iamApi.users(keyword.value || undefined),
      iamApi.roles(),
    ])
    users.value = userPage.items
    roles.value = roleList.items
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
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
    selectedRoles: [],
  })
}

function openEdit(row: UserView) {
  Object.assign(userDialog, {
    visible: true,
    mode: 'edit',
    id: row.id,
    username: row.username,
    displayName: row.displayName,
    password: '',
    selectedRoles: [...row.roles],
  })
}

async function saveUser() {
  try {
    if (userDialog.mode === 'create') {
      await iamApi.createUser({
        username: userDialog.username,
        password: userDialog.password,
        displayName: userDialog.displayName,
        roles: userDialog.selectedRoles,
      })
      ElMessage.success('已创建。该账号首次登录将被强制修改密码。')
    } else {
      await iamApi.updateRoles(userDialog.id, userDialog.selectedRoles)
      ElMessage.success('角色已更新，立即生效（对方无需重新登录）')
    }
    userDialog.visible = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

async function toggleEnabled(row: UserView) {
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

async function resetPassword(row: UserView) {
  let password = ''
  try {
    const input = await ElMessageBox.prompt(
      `为「${row.username}」设置新密码（至少 8 位、3 类字符）：`,
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

async function openScope(row: UserView) {
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
      <el-tab-pane label="用户" name="users">
        <el-card shadow="never">
          <template #header>
            <div class="header">
              <span>账号清单</span>
              <div class="actions">
                <el-input
                  v-model="keyword"
                  placeholder="搜索用户名/显示名"
                  clearable
                  style="width: 220px"
                  @change="load"
                />
                <el-button @click="load">刷新</el-button>
                <el-button v-if="canWriteUser" type="primary" @click="openCreate">
                  新建账号
                </el-button>
              </div>
            </div>
          </template>

          <el-table :data="users" v-loading="loading" border>
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="username" label="用户名" width="140" />
            <el-table-column prop="displayName" label="显示名" width="140" />
            <el-table-column label="角色" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="r in row.roles" :key="r" size="small" style="margin-right: 4px">
                  {{ r }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="160">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
                <el-tag v-if="row.mustChangePassword" type="warning" style="margin-left: 4px">
                  待改密
                </el-tag>
                <el-tag v-if="row.lockedUntil" type="danger" style="margin-left: 4px">
                  已锁定
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <template v-if="canWriteUser">
                  <el-button size="small" @click="openEdit(row)">改角色</el-button>
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

    <!-- 用户新建/改角色 -->
    <el-dialog v-model="userDialog.visible" :title="userDialog.mode === 'create' ? '新建账号' : '修改角色'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="用户名">
          <el-input v-model="userDialog.username" :disabled="userDialog.mode === 'edit'" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="userDialog.displayName" />
        </el-form-item>
        <el-form-item v-if="userDialog.mode === 'create'" label="初始密码">
          <el-input v-model="userDialog.password" type="password" show-password
            placeholder="至少 8 位、3 类字符" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="userDialog.selectedRoles" multiple style="width: 100%">
            <el-option v-for="r in roles" :key="r.code" :label="`${r.code} · ${r.name}`" :value="r.code" />
          </el-select>
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
</style>
