<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { invApi, mdmApi, purApi, salApi } from '@/api/client'
import { useSessionStore } from '@/stores/session'

/**
 * 工作台。
 *
 * 这里刻意做成"真实反映后端状态"而非静态欢迎页：
 * 打开系统第一眼就知道 商品/库存/进货单/出货单 各有多少、库存是否一致。
 * 一个连自己数据都读不出来的工作台，等于没有。
 */

const session = useSessionStore()

const loading = ref(false)
const loadError = ref('')
const consistencyError = ref(false)
const productTotal = ref<number | null>(null)
const inventoryRows = ref<number | null>(null)
const purchaseTotal = ref<number | null>(null)
const salesTotal = ref<number | null>(null)
const consistent = ref<boolean | null>(null)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const displayName = computed(() => session.displayName || session.username || '用户')

async function load() {
  loading.value = true
  loadError.value = ''
  consistencyError.value = false
  // 用 allSettled：任一接口失败不应让整个工作台空白
  const results = await Promise.allSettled([
    mdmApi.listProducts({ page: 1, size: 1 }),
    invApi.query({}),
    purApi.list({ page: 1, size: 1 }),
    salApi.list({ page: 1, size: 1 }),
    invApi.consistencyCheck(),
  ])

  if (results[0].status === 'fulfilled') productTotal.value = results[0].value.total
  if (results[1].status === 'fulfilled') inventoryRows.value = results[1].value.length
  if (results[2].status === 'fulfilled') purchaseTotal.value = results[2].value.total
  if (results[3].status === 'fulfilled') salesTotal.value = results[3].value.total
  if (results[4].status === 'fulfilled') {
    consistent.value = results[4].value.consistent
  } else {
    // 读不到 ≠ 漂移。保留 null 并单独提示，不能把接口失败说成"库存有问题"
    consistent.value = null
    consistencyError.value = true
  }

  if (results.some((r) => r.status === 'rejected')) {
    loadError.value = '部分统计加载失败，显示为 - 的数字不是真实值。请重试。'
  }
  loading.value = false
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-header">
      <h2 class="page-title">{{ greeting }}，{{ displayName }}</h2>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="loadError"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <!-- 数据概览 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat-label">商品</div>
          <div class="stat-value">{{ productTotal ?? '-' }}</div>
          <div class="stat-hint">个款式（不含 SKU 明细）</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat-label">库存行</div>
          <div class="stat-value">{{ inventoryRows ?? '-' }}</div>
          <div class="stat-hint">条 (SKU × 仓库 × 批次)</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat-label">进货单</div>
          <div class="stat-value">{{ purchaseTotal ?? '-' }}</div>
          <div class="stat-hint">张（全状态）</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card shadow="hover">
          <div class="stat-label">出货单</div>
          <div class="stat-value">{{ salesTotal ?? '-' }}</div>
          <div class="stat-hint">张（全状态）</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 库存一致性 -->
    <el-card shadow="never" class="mb-16">
      <template #header>
        <span>库存一致性（不变量 INV-2：实存 == 流水净和）</span>
      </template>
      <el-alert
        v-if="consistencyError"
        type="warning"
        show-icon
        :closable="false"
        title="库存一致性检查失败"
        description="无法读取一致性结果，不代表存在库存漂移。请稍后重试。"
      >
        <el-button size="small" @click="load">重试</el-button>
      </el-alert>
      <el-result
        v-else-if="consistent !== null"
        :icon="consistent ? 'success' : 'error'"
        :title="consistent ? '库存与流水一致' : '检测到库存漂移'"
        :sub-title="
          consistent
            ? '所有 (SKU, 仓库, 批次) 的实存量均等于流水净和'
            : '存在实存与流水不一致的记录，请立即排查是否有代码绕过流水修改库存'
        "
      />
      <div v-else class="empty-block">加载中…</div>
    </el-card>

    <!-- 本次会话权限 -->
    <el-card shadow="never">
      <template #header>
        <span>当前账号权限</span>
      </template>
      <div v-if="session.roles.length" class="mb-16">
        <span class="text-muted mr-8">角色：</span>
        <el-tag v-for="r in session.roles" :key="r" class="mr-8" type="primary">{{ r }}</el-tag>
      </div>
      <div v-if="session.permissions.length">
        <span class="text-muted mr-8">权限码：</span>
        <el-tag
          v-for="p in session.permissions"
          :key="p"
          class="mr-8 mb-16"
          type="info"
          size="small"
        >
          {{ p }}
        </el-tag>
      </div>
      <div v-else class="empty-block">无可用权限，请联系管理员分配角色。</div>
    </el-card>
  </div>
</template>

<style scoped>
.stat-label {
  font-size: 13px;
  color: #909399;
}

.stat-value {
  margin: 8px 0 4px;
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #303133;
}

.stat-hint {
  font-size: 12px;
  color: #c0c4cc;
}
</style>
