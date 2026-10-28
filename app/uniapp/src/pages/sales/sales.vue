<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { salApi, type SalesOrder } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { DOCUMENT_STATUSES, statusMeta } from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 出货单列表（移动端只读入口）。
 *
 * 建单/编辑/审核/作废留在 Web；这里的列表只做状态筛选与查看，
 * 单动作「出货」在详情页完成。状态筛选取值来自镜像表，不自行发明状态。
 */

const PAGE_SIZE = 20

const allowed = computed(() => hasPermission('sal:read'))
const { loadDictionary, customerText, warehouseText, warning: dictWarning } = useDictionary()

const statusFilter = ref('')
const statusFilters = [
  { value: '', label: '全部' },
  ...DOCUMENT_STATUSES.map((value) => ({ value, label: statusMeta(value).label })),
]

const orders = ref<SalesOrder[]>([])
const total = ref(0)
const page = ref(0)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const hasMore = computed(() => page.value * PAGE_SIZE < total.value)

let queuedReset = false

async function load(reset = true): Promise<void> {
  if (!allowed.value) return
  if (loading.value) {
    if (reset) queuedReset = true
    return
  }
  if (!reset && !hasMore.value) return

  loading.value = true
  if (reset) {
    error.value = ''
    loaded.value = false
  }

  const nextPage = reset ? 1 : page.value + 1
  const result = await safe(
    salApi.list({
      status: statusFilter.value || undefined,
      page: nextPage,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    orders.value = reset
      ? result.value.items
      : [...orders.value, ...result.value.items.filter(
          (incoming) => !orders.value.some((existing) => existing.id === incoming.id),
        )]
    total.value = result.value.total
    page.value = result.value.page
  } else {
    if (reset) {
      orders.value = []
      total.value = 0
      page.value = 0
    }
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false

  if (queuedReset) {
    queuedReset = false
    void load(true)
  }
}

function selectStatus(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  load(true)
}

function openDetail(order: SalesOrder): void {
  uni.navigateTo({ url: `/pages/sales/detail?id=${order.id}` })
}

function shippedQty(order: SalesOrder): number {
  return (order.lines ?? []).reduce((sum, line) => sum + (line.shippedQty ?? 0), 0)
}

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) return
  loadDictionary()
  load(true)
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    await load(true)
    uni.stopPullDownRefresh()
  })()
})

onReachBottom(() => {
  if (!loading.value && hasMore.value) {
    load(false)
  }
})
</script>

<template>
  <view class="page sales">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有出货单读权限</text>
      <text class="notice-text">
        本页依赖 sal:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view class="chips">
        <view
          v-for="option in statusFilters"
          :key="option.value || 'ALL'"
          class="chip"
          :class="{ active: statusFilter === option.value }"
          @click="selectStatus(option.value)"
        >
          {{ option.label }}
        </view>
      </view>

      <view v-if="loaded" class="summary row-between">
        <text class="summary-text">
          {{ statusFilter ? statusMeta(statusFilter).label : '全部' }}单据 {{ total }} 张
        </text>
        <text class="summary-text">已加载 {{ orders.length }} 张</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取出货单…</view>

      <view v-else-if="error && !orders.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load(true)">重试</button>
      </view>

      <view v-else-if="!orders.length" class="card empty-block">
        {{
          statusFilter
            ? `没有${statusMeta(statusFilter).label}的出货单。可以切换状态筛选再试。`
            : '还没有出货单。建单与审核请使用 Web 端，完成后这里会显示单据。'
        }}
      </view>

      <view v-else class="list">
        <view
          v-for="order in orders"
          :key="order.id"
          class="card order-card"
          :data-order-no="order.orderNo"
          @click="openDetail(order)"
        >
          <view class="row-between order-head">
            <text class="order-no">{{ order.orderNo }}</text>
            <text class="status-tag" :class="`tone-${statusMeta(order.status).tone}`">
              {{ statusMeta(order.status).label }}
            </text>
          </view>

          <view class="row-between order-meta">
            <text class="meta-text">{{ customerText(order.customerId) }}</text>
            <text class="meta-text">{{ warehouseText(order.warehouseId) }}</text>
          </view>

          <view class="row-between order-meta">
            <text class="meta-text">{{ formatDate(order.bizDate) }}</text>
            <text class="meta-text">{{ order.totalQty }} 件 · {{ formatFen(order.totalAmount) }}</text>
          </view>

          <view class="row-between order-foot">
            <text class="progress">已出 {{ shippedQty(order) }}/{{ order.totalQty }}</text>
            <text class="arrow">查看详情 ›</text>
          </view>
        </view>
      </view>

      <view v-if="loaded" class="list-footer">
        <!-- 加载更多在请求飞行中保持同一节点，只改文案 + 禁用：v-if 换节点会让重复点击打空 -->
        <button
          v-if="hasMore || loading"
          class="btn-ghost load-more"
          hover-class="none"
          :disabled="loading"
          @click="load(false)"
        >
          {{ loading ? '加载中…' : `加载更多（${orders.length} / ${total}）` }}
        </button>
        <text v-else-if="orders.length" class="footer-note">已全部加载，共 {{ total }} 张</text>

        <view v-if="error && orders.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="load(false)">重试</text>
        </view>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.sales {
  padding-top: 20rpx;
}

.notice-title {
  display: block;
  font-size: 29rpx;
  font-weight: 600;
  color: var(--c-danger);
}

.notice-text {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-bottom: 20rpx;
}

.chip {
  padding: 12rpx 26rpx;
  border-radius: 999rpx;
  background: var(--c-fill-strong);
  color: var(--c-text-2);
  font-size: 24rpx;
}

.chip.active {
  background: var(--c-brand);
  color: #ffffff;
  font-weight: 600;
}

.summary {
  margin: 6rpx 4rpx 14rpx;
}

.summary-text {
  font-size: 23rpx;
  color: var(--c-text-3);
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.order-card {
  padding: 26rpx;
}

.order-head {
  margin-bottom: 16rpx;
}

.order-no {
  flex: 1;
  min-width: 0;
  font-size: 29rpx;
  font-weight: 700;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-size: 21rpx;
  font-weight: 600;
}

.tone-info {
  background: var(--c-fill-strong);
  color: var(--c-text-2);
}

.tone-primary {
  background: var(--c-info-wash);
  color: var(--c-info-text);
}

.tone-success {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.tone-warning {
  background: var(--c-warn-wash);
  color: var(--c-warn);
}

.tone-danger {
  background: var(--c-danger-wash);
  color: var(--c-danger);
}

.order-meta {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 23rpx;
  color: var(--c-text-2);
}

.order-foot {
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--c-line);
}

.progress {
  font-size: 24rpx;
  color: var(--c-warn);
  font-weight: 600;
}

.arrow {
  font-size: 23rpx;
  color: var(--c-brand-text);
}

.list-footer {
  margin-top: 20rpx;
}

.load-more {
  width: 100%;
  height: 80rpx;
  font-size: 26rpx;
}

.footer-note {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: var(--c-text-4);
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.inline-error {
  margin-top: 16rpx;
  padding: 18rpx 22rpx;
  background: var(--c-danger-wash);
  border-radius: 14rpx;
  text-align: center;
  font-size: 23rpx;
  color: var(--c-danger);
}

.inline-retry {
  margin-left: 12rpx;
  font-weight: 600;
  text-decoration: underline;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
