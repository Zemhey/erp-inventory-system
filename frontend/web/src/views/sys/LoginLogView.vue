<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { skeletonApi, type LoginLog } from '@/api/skeleton'

/**
 * 登录日志（FR-V3-29）。
 *
 * ★ 只读：登录日志由 iam 登录流程写入 sys 模块（LoginLogRecorder），
 *   本页只查最近日志与"当前在线用户（最近 N 分钟有过成功登录）"，不做任何写操作。
 */

const logs = ref<LoginLog[]>([])
const loading = ref(false)
const error = ref('')

const onlyOnline = ref(false)
const onlineMinutes = ref(30)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = onlyOnline.value
      ? await skeletonApi.onlineUsers(onlineMinutes.value)
      : await skeletonApi.loginLogs(100)
    logs.value = res.items
  } catch (cause: unknown) {
    const e = cause as { response?: { data?: { message?: string } }; message?: string }
    error.value = e?.response?.data?.message || e?.message || '登录日志加载失败'
  } finally {
    loading.value = false
  }
}

function onModeChange() {
  void load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">登录日志</h2>
      <div>
        <el-switch
          v-model="onlyOnline"
          active-text="仅看在线"
          inactive-text="全部日志"
          class="mr-8"
          @change="onModeChange"
        />
        <el-button @click="load">刷新</el-button>
      </div>
    </div>
    <p class="page-hint">
      记录登录账号、IP、User-Agent、时间与成败；"仅看在线"按最近 {{ onlineMinutes }} 分钟成功登录筛选。
    </p>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="`加载失败：${error}`"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <el-table v-loading="loading" :data="logs" border size="small">
      <el-table-column label="账号" prop="username" min-width="120" />
      <el-table-column label="IP" prop="ip" min-width="130">
        <template #default="{ row }">{{ row.ip || '—' }}</template>
      </el-table-column>
      <el-table-column label="User-Agent" prop="userAgent" min-width="220">
        <template #default="{ row }">
          <span class="ua">{{ row.userAgent || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="登录时间" prop="loginAt" min-width="190" />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'" size="small">
            {{ row.success ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="失败原因" prop="failReason" min-width="160">
        <template #default="{ row }">{{ row.failReason || '—' }}</template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !error && !logs.length" :description="onlyOnline ? '当前没有在线用户' : '没有登录日志'" />
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

.page-hint {
  margin: 6px 0 12px;
}

.mr-8 {
  margin-right: 8px;
}

.ua {
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}
</style>
