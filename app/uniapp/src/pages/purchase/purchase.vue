<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { purApi, type PurchaseOrder } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import { DOCUMENT_STATUSES, statusMeta } from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { hideNativeTabBar } from '@/utils/navigation'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 进货单列表（移动端只读入口）。
 *
 * 建单/编辑/审核/作废留在 Web；这里的列表只做状态筛选与查看，
 * 单动作「入库」在详情页完成。
 *
 * 分页：一页 20 张、翻页看下一页，不再滚到底自动续。
 */

const PAGE_SIZE = 20

const { themeClass } = useTheme()

const allowed = computed(() => hasPermission('pur:read'))
const { loadDictionary, supplierText, warehouseText, warning: dictWarning } = useDictionary()

const statusFilter = ref('')
const statusFilters = [
  { value: '', label: '全部' },
  ...DOCUMENT_STATUSES.map((value) => ({ value, label: statusMeta(value).label })),
]

const orders = ref<PurchaseOrder[]>([])
const total = ref(0)
const page = ref(1)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

let queued = false
/** 上一次真正加载成功的页码：请求失败时把翻页条退回到这里，不停在没数据的页上。 */
let goodPage = 1

async function loadOrders(): Promise<void> {
  if (!allowed.value) return
  if (loading.value) {
    // 筛选在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑，避免界面与筛选不一致
    queued = true
    return
  }

  loading.value = true
  error.value = ''

  const result = await safe(
    purApi.list({
      status: statusFilter.value || undefined,
      page: page.value,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    orders.value = result.value.items
    total.value = result.value.total
    // 以后端回的值为准：筛选条件变了以后，请求的那一页可能已经不存在
    page.value = result.value.page
    goodPage = result.value.page
  } else {
    orders.value = []
    page.value = goodPage
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false

  if (queued) {
    queued = false
    void loadOrders()
  }
}

function goOrdersPage(next: number): void {
  page.value = next
  void loadOrders()
}

/** 换筛选条件一律回到第 1 页：停在第 7 页换状态，多半只会看到空列表。 */
function reloadOrders(): void {
  page.value = 1
  void loadOrders()
}

function selectStatus(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  reloadOrders()
}

function openDetail(order: PurchaseOrder): void {
  uni.navigateTo({ url: `/pages/purchase/detail?id=${order.id}` })
}

function receivedQty(order: PurchaseOrder): number {
  return (order.lines ?? []).reduce((sum, line) => sum + (line.receivedQty ?? 0), 0)
}

onShow(() => {
  if (!ensureLoggedIn()) return
  // 底栏自己画（原生那条最多 5 项、高度也改不了），每次显示都要重新藏一次
  hideNativeTabBar()

  if (!allowed.value) return
  loadDictionary()
  // 停在原来那一页刷新：从详情页退回来不该被弹回第 1 页
  void loadOrders()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    // 下拉是"刷新我正在看的这一页"，翻页请走下面的翻页条
    await loadOrders()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page purchase page--tabbar" :class="themeClass">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有进货单读权限</text>
      <text class="notice-text">
        本页依赖 pur:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
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
        <text class="summary-text">本页 {{ orders.length }} 张</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取进货单…</view>

      <view v-else-if="error && !orders.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="loadOrders">重试</button>
      </view>

      <view v-else-if="!orders.length" class="card empty-block">
        {{
          statusFilter
            ? `没有${statusMeta(statusFilter).label}的进货单。可以切换状态筛选再试。`
            : '还没有进货单。建单与审核请使用 Web 端，完成后这里会显示单据。'
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
            <text class="meta-text">{{ supplierText(order.supplierId) }}</text>
            <text class="meta-text">{{ warehouseText(order.warehouseId) }}</text>
          </view>

          <view class="row-between order-meta">
            <text class="meta-text">{{ formatDate(order.bizDate) }}</text>
            <text class="meta-text">{{ order.totalQty }} 件 · {{ formatFen(order.totalAmount) }}</text>
          </view>

          <view class="row-between order-foot">
            <text class="progress">已入 {{ receivedQty(order) }}/{{ order.totalQty }}</text>
            <text class="arrow">查看详情 ›</text>
          </view>
        </view>
      </view>

      <view v-if="loaded && total" class="pager-wrap">
        <list-pager
          :page="page"
          :size="PAGE_SIZE"
          :total="total"
          :loading="loading"
          unit="张"
          @change="goOrdersPage"
        />
        <view v-if="error && orders.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="loadOrders">重试</text>
        </view>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>

    <app-tab-bar active="purchase" />
  </view>
</template>

<style scoped>
.purchase {
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
  color: var(--c-on-brand);
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

.pager-wrap {
  margin-top: 20rpx;
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
