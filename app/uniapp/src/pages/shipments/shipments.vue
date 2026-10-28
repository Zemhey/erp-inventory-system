<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { shipmentApi, type Shipment } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { SHIPMENT_STATUSES, shipmentStatusMeta } from '@/utils/shipmentStatus'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 发货单列表（FR-SAL-06）。
 *
 * 发货单是「拣货 + 出库」的作业单，状态是 ShipmentStatus 五态，
 * 与出货单的单据七态是两套（见 utils/shipmentStatus.ts 的对照表）。
 * 计划由出货单详情页生成，一张计划可能按仓拆成多张发货单 —— 这里按状态筛正在做的活。
 */

const PAGE_SIZE = 20

const allowed = computed(() => hasPermission('sal:read'))
const { loadDictionary, warehouseText, warning: dictWarning } = useDictionary()

const statusFilter = ref('PICKING')
const statusFilters = [
  { value: '', label: '全部' },
  ...SHIPMENT_STATUSES.map((value) => ({ value, label: shipmentStatusMeta(value).label })),
]

const shipments = ref<Shipment[]>([])
const total = ref(0)
const page = ref(0)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const hasMore = computed(() => page.value * PAGE_SIZE < total.value)

let queuedReset = false

function pickedQty(shipment: Shipment): number {
  return (shipment.lines ?? []).reduce((sum, line) => sum + line.pickedQty, 0)
}

function plannedQty(shipment: Shipment): number {
  return (shipment.lines ?? []).reduce((sum, line) => sum + line.plannedQty, 0)
}

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
    shipmentApi.list({
      status: statusFilter.value || undefined,
      page: nextPage,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    shipments.value = reset
      ? result.value.items
      : [
          ...shipments.value,
          ...result.value.items.filter(
            (incoming) => !shipments.value.some((existing) => existing.id === incoming.id),
          ),
        ]
    total.value = result.value.total
    page.value = result.value.page
  } else {
    if (reset) {
      shipments.value = []
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

function openDetail(shipment: Shipment): void {
  uni.navigateTo({ url: `/pages/shipments/detail?id=${shipment.id}` })
}

function openSalesOrder(shipment: Shipment): void {
  uni.navigateTo({ url: `/pages/sales/detail?id=${shipment.salesOrderId}` })
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
  if (!loading.value && hasMore.value) load(false)
})
</script>

<template>
  <view class="page shipments">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有发货单读权限</text>
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
          {{ statusFilter ? shipmentStatusMeta(statusFilter).label : '全部' }}发货单 {{ total }} 张
        </text>
        <text class="summary-text">已加载 {{ shipments.length }} 张</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取发货单…</view>

      <view v-else-if="error && !shipments.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load(true)">重试</button>
      </view>

      <view v-else-if="!shipments.length" class="card empty-block">
        {{
          statusFilter
            ? `没有${shipmentStatusMeta(statusFilter).label}的发货单。可以切换状态筛选再试。`
            : '还没有发货单。到「出货单」详情里点「生成发货计划」即可开始拣货作业。'
        }}
      </view>

      <view v-else class="list">
        <view
          v-for="shipment in shipments"
          :key="shipment.id"
          class="card shipment-card"
          :data-shipment-no="shipment.shipmentNo"
          @click="openDetail(shipment)"
        >
          <view class="row-between shipment-head">
            <text class="shipment-no">{{ shipment.shipmentNo }}</text>
            <text class="status-tag" :class="`tone-${shipmentStatusMeta(shipment.status).tone}`">
              {{ shipmentStatusMeta(shipment.status).label }}
            </text>
          </view>

          <view class="row-between shipment-meta">
            <text class="meta-text">{{ warehouseText(shipment.warehouseId) }}</text>
            <text class="meta-text">{{ (shipment.lines ?? []).length }} 行拣货</text>
          </view>

          <view class="pick-bar">
            <view class="pick-track">
              <view
                class="pick-fill"
                :class="{ 'pick-error': pickedQty(shipment) > plannedQty(shipment) }"
                :style="{
                  width: `${plannedQty(shipment) ? Math.min(100, (pickedQty(shipment) / plannedQty(shipment)) * 100) : 0}%`,
                }"
              />
            </view>
            <text class="pick-text">已扫 {{ pickedQty(shipment) }}/{{ plannedQty(shipment) }}</text>
          </view>

          <view class="row-between shipment-foot">
            <text class="link" @click.stop="openSalesOrder(shipment)">
              出货单 #{{ shipment.salesOrderId }} ›
            </text>
            <text class="arrow">开始扫码 ›</text>
          </view>
        </view>
      </view>

      <view v-if="loaded" class="list-footer">
        <button
          v-if="hasMore || loading"
          class="btn-ghost load-more"
          hover-class="none"
          :disabled="loading"
          @click="load(false)"
        >
          {{ loading ? '加载中…' : `加载更多（${shipments.length} / ${total}）` }}
        </button>
        <text v-else-if="shipments.length" class="footer-note">已全部加载，共 {{ total }} 张</text>

        <view v-if="error && shipments.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="load(false)">重试</text>
        </view>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.shipments {
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

.shipment-card {
  padding: 26rpx;
}

.shipment-head {
  margin-bottom: 14rpx;
}

.shipment-no {
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

.tone-wait {
  background: var(--c-fill-strong);
  color: var(--c-text-2);
}

.tone-working {
  background: var(--c-warn-wash);
  color: var(--c-warn);
}

.tone-ready {
  background: var(--c-info-wash);
  color: var(--c-info-text);
}

.tone-done {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.tone-quiet {
  background: var(--c-fill-strong);
  color: var(--c-text-3);
}

.shipment-meta {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 23rpx;
  color: var(--c-text-2);
}

.pick-bar {
  display: flex;
  align-items: center;
  margin-top: 16rpx;
}

.pick-track {
  flex: 1;
  height: 10rpx;
  border-radius: 999rpx;
  background: #eaedf3;
  overflow: hidden;
}

.pick-fill {
  height: 100%;
  border-radius: 999rpx;
  background: var(--c-brand);
}

.pick-error {
  background: var(--c-danger);
}

.pick-text {
  margin-left: 16rpx;
  font-size: 23rpx;
  font-weight: 600;
  color: var(--c-warn);
}

.shipment-foot {
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--c-line);
}

.link {
  font-size: 23rpx;
  color: var(--c-brand-text);
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
