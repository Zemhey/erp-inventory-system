<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { OfficeBuilding } from '@element-plus/icons-vue'
import PageTable from '@/components/PageTable.vue'
import { tenantApi, type TenantView } from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 平台租户管理（B1 · FR-TNT-08）。
 *
 * ★ 这是**平台元数据**页面，不是"跨租户业务数据"页面：
 *   这里只能看/改 `tenants` 的启用状态；商品、单据、库存仍然只能看到
 *   当前行动租户的数据。平台管理员拿别的租户的业务 id 请求业务接口仍是 404。
 *
 * ★ 停用的即时语义：后端在每个请求上校验租户启用，所以停用后该租户的
 *   既有 token 下一个请求就 401，不需要等 token 过期。
 *   停用平台租户或当前所在租户会被后端拒绝（TENANT_STATE_ILLEGAL）；
 *   前端提前禁用按钮只是为了少一次往返，**不是**安全边界。
 */

const session = useSessionStore()

const rows = ref<TenantView[]>([])
const loading = ref(false)
const error = ref('')
const changingId = ref<number | null>(null)

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await tenantApi.list()).items
  } catch (cause) {
    rows.value = []
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

/** 平台租户与当前租户不能被停用（与 TenantAdminService 的两条保护逐条对应）。 */
function disableBlockedReason(row: TenantView): string {
  if (row.id === 1) return '平台租户不能停用'
  if (row.id === session.tenantId) return '不能停用当前所在租户'
  return ''
}

async function toggle(row: TenantView) {
  const next = !row.enabled
  if (!next) {
    const blocked = disableBlockedReason(row)
    if (blocked) {
      ElMessage.warning(blocked)
      return
    }
    try {
      await ElMessageBox.confirm(
        `停用后「${row.name}」的成员将无法登录/切换，既有会话的下一个请求会 401。业务数据不会被删除。确定停用吗？`,
        '停用租户',
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }

  changingId.value = row.id
  try {
    const updated = await tenantApi.setEnabled(row.id, next)
    const idx = rows.value.findIndex((r) => r.id === updated.id)
    if (idx >= 0) rows.value[idx] = updated
    ElMessage.success(next ? `已启用「${updated.name}」` : `已停用「${updated.name}」`)
  } catch {
    // 403 / 404 / 409 的定位消息由 axios 拦截器统一提示，这里不重复弹
  } finally {
    changingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">租户管理</h2>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <p class="page-hint">
      平台级租户元数据：这里只管理租户的启用状态，不读取任何租户的业务数据。
      只有平台租户管理员可见；普通租户管理员既看不到菜单，直接请求也会被拒绝。
    </p>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="mb-16"
      title="停用是即时生效的"
      description="停用后，该租户成员的登录与切换会被拒绝，既有 token 的下一个请求返回 401；不会删除业务数据。平台租户与当前所在租户不能停用。"
    />

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`租户列表加载失败：${error}`"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <PageTable
      :rows="rows"
      :loading="loading"
      :paginated="false"
      :load-failed="!!error"
      row-key="id"
      empty-text="没有租户"
      empty-hint="租户由迁移预置（default / demo）；当前没有可展示的租户。"
      table-size="small"
    >
      <template #header>
        <div class="table-title">
          <el-icon><OfficeBuilding /></el-icon>
          租户
          <span class="text-muted">共 {{ rows.length }} 个</span>
        </div>
      </template>

      <el-table-column label="ID" width="70" prop="id" />
      <el-table-column label="编码" width="120" prop="code" />
      <el-table-column label="名称" min-width="160" prop="name" />
      <el-table-column label="标记" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.id === 1" size="small" type="warning">平台租户</el-tag>
          <el-tag v-if="row.id === session.tenantId" size="small" type="success" class="ml-8">
            当前
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.enabled && disableBlockedReason(row)"
            :content="disableBlockedReason(row)"
            placement="top"
          >
            <span>
              <el-button link type="danger" disabled>停用</el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else
            link
            :type="row.enabled ? 'danger' : 'primary'"
            :loading="changingId === row.id"
            @click="toggle(row)"
          >
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </PageTable>
  </div>
</template>

<style scoped>
.table-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.ml-8 {
  margin-left: 8px;
}
</style>
