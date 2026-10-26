<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { stocktakeApi, type Stocktake } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { formatDate, signedQty } from '@/utils/format'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 盘点单列表（FR-INV-08）。
 *
 * 后端没有「盘点单详情」端点，列表本身返回 lines —— 这里直接展示差异明细，
 * 不再为了一个详情页去新增接口。草稿单可在本页过账；过账后替换本行，
 * 不整页刷新（保留筛选与滚动位置）。
 */

const PAGE_SIZE = 20

const allowed = computed(() => hasPermission('inv:read'))
const canWrite = computed(() => hasPermission('inv:write'))
const { loadDictionary, warehouses, warehouseText, skuText, warning: dictWarning } =
  useDictionary()

const STATUS_META: Record<string, { label: string; tone: string }> = {
  DRAFT: { label: '草稿', tone: 'wait' },
  POSTED: { label: '已过账', tone: 'done' },
}

function statusMeta(status: string): { label: string; tone: string } {
  return STATUS_META[status] ?? { label: status, tone: 'quiet' }
}

const statusFilter = ref('')
const warehouseId = ref<number | undefined>(undefined)

const warehouseRange = computed(() => [
  '全部仓库',
  ...warehouses.value.map((warehouse) =>
    warehouse.enabled ? warehouse.name : `${warehouse.name}（已停用）`,
  ),
])

const warehouseIndex = computed(() => {
  if (warehouseId.value === undefined) return 0
  const index = warehouses.value.findIndex((warehouse) => warehouse.id === warehouseId.value)
  return index >= 0 ? index + 1 : 0
})

const items = ref<Stocktake[]>([])
const total = ref(0)
const page = ref(0)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')
const postingId = ref(0)
const actionError = ref('')

const hasMore = computed(() => page.value * PAGE_SIZE < total.value)
const filtered = computed(() => statusFilter.value !== '' || warehouseId.value !== undefined)

let queuedReset = false

function lineCount(item: Stocktake): number {
  return item.lines?.length ?? 0
}

function diffText(value: number): string {
  return value === 0 ? '0' : signedQty(value)
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

  const result = await safe(
    stocktakeApi.list({
      status: statusFilter.value || undefined,
      warehouseId: warehouseId.value,
      page: reset ? 1 : page.value + 1,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    items.value = reset
      ? result.value.items
      : [
          ...items.value,
          ...result.value.items.filter(
            (incoming) => !items.value.some((existing) => existing.id === incoming.id),
          ),
        ]
    total.value = result.value.total
    page.value = result.value.page
  } else {
    if (reset) {
      items.value = []
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

function onWarehouseChange(event: unknown): void {
  const index = Number((event as { detail?: { value?: string | number } })?.detail?.value ?? 0)
  warehouseId.value = index === 0 ? undefined : warehouses.value[index - 1]?.id
  load(true)
}

function createStocktake(): void {
  const query = warehouseId.value ? `?warehouseId=${warehouseId.value}` : ''
  uni.navigateTo({ url: `/pages/stocktakes/scan${query}` })
}

function confirmPost(item: Stocktake): void {
  if (!canWrite.value || postingId.value) return
  uni.showModal({
    title: '盘点过账',
    content:
      `将按差异生成调整流水（盘盈 ADJUST_IN / 盘亏 ADJUST_OUT），本次差异合计 ` +
      `${diffText(item.totalDiffQty)} 件。过账后不可撤销，确定过账吗？`,
    confirmColor: '#1f3a63',
    success: (modal) => {
      if (modal.confirm) void doPost(item)
    },
  })
}

async function doPost(item: Stocktake): Promise<void> {
  postingId.value = item.id
  actionError.value = ''

  const result = await safe(stocktakeApi.post(item.id))
  if (result.ok) {
    items.value = items.value.map((existing) =>
      existing.id === result.value.id ? result.value : existing,
    )
    uni.showToast({ title: '盘点已过账', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  postingId.value = 0
}

onLoad((options: Record<string, string> | undefined) => {
  const fromQuery = Number(options?.warehouseId ?? 0)
  if (fromQuery > 0) warehouseId.value = fromQuery
})

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) {
    loaded.value = true
    return
  }
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
  <view class="page stocktakes">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有库存读权限</text>
      <text class="notice-text">本页依赖 inv:read；盘点建单与过账还需要 inv:write。</text>
    </view>

    <template v-else>
      <view class="chips">
        <view class="chip" :class="{ active: statusFilter === '' }" @click="selectStatus('')">全部</view>
        <view
          v-for="(meta, key) in STATUS_META"
          :key="key"
          class="chip"
          :class="{ active: statusFilter === key }"
          @click="selectStatus(key)"
        >
          {{ meta.label }}
        </view>
      </view>

      <view class="card filter-card">
        <view class="filter-row">
          <text class="filter-label">仓库</text>
          <picker class="picker-flex" :range="warehouseRange" :value="warehouseIndex" @change="onWarehouseChange">
            <view class="picker-value">
              <text>{{ warehouseRange[warehouseIndex] }}</text>
              <text class="picker-arrow">⌄</text>
            </view>
          </picker>
        </view>

        <button
          v-if="canWrite"
          class="btn-primary create-btn"
          hover-class="none"
          data-entry="stocktake-create"
          @click="createStocktake"
        >
          新建扫码盘点
        </button>
        <text v-else class="create-note">当前账号没有 inv:write，只能查看盘点单。</text>
      </view>

      <view v-if="loaded" class="summary row-between">
        <text class="summary-text">
          {{ statusFilter ? statusMeta(statusFilter).label : '全部' }}盘点单 {{ total }} 张
        </text>
        <text class="summary-text">已加载 {{ items.length }} 张</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取盘点单…</view>

      <view v-else-if="error && !items.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load(true)">重试</button>
      </view>

      <view v-else-if="!items.length" class="card empty-block">
        {{
          filtered
            ? '当前筛选没有盘点单。可以切换状态或仓库再试。'
            : '还没有盘点单。点「新建扫码盘点」，扫码后即可建单并过账差异。'
        }}
      </view>

      <view v-else class="list">
        <view
          v-for="item in items"
          :key="item.id"
          class="card stocktake-card"
          :data-stocktake-no="item.stocktakeNo"
          :data-stocktake-id="item.id"
        >
          <view class="row-between card-head">
            <text class="stocktake-no">{{ item.stocktakeNo }}</text>
            <text class="status-tag" :class="`tone-${statusMeta(item.status).tone}`">
              {{ statusMeta(item.status).label }}
            </text>
          </view>

          <view class="row-between meta-row">
            <text class="meta-text">{{ warehouseText(item.warehouseId) }}</text>
            <text class="meta-text">{{ formatDate(item.bizDate) }}</text>
          </view>
          <view class="row-between meta-row">
            <text class="meta-text">明细 {{ lineCount(item) }} 行</text>
            <text class="diff-value" :class="item.totalDiffQty === 0 ? '' : 'diff-active'">
              差异 {{ diffText(item.totalDiffQty) }}
            </text>
          </view>

          <view class="line-table">
            <view v-for="line in item.lines" :key="`${line.skuId}-${line.batchNo}`" class="line-row">
              <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
              <text class="line-batch">{{ line.batchNo || '无批次' }}</text>
              <text class="line-qty">账 {{ line.bookQty }} / 实 {{ line.countedQty }}</text>
              <text class="line-diff" :class="line.diffQty === 0 ? '' : 'diff-active'">
                {{ diffText(line.diffQty) }}
              </text>
            </view>
          </view>

          <view v-if="item.status === 'DRAFT' && canWrite" class="card-actions">
            <button
              class="btn-primary post-btn"
              hover-class="none"
              :disabled="postingId === item.id"
              @click="confirmPost(item)"
            >
              {{ postingId === item.id ? '过账中…' : '过账' }}
            </button>
          </view>
          <text v-else-if="item.status === 'POSTED'" class="posted-note">
            {{ item.postedAt ? `已过账 · ${formatDate(item.postedAt)}` : '已过账' }}
          </text>
        </view>
      </view>

      <view v-if="actionError" class="inline-error" data-testid="stocktake-action-error">
        <text class="block-text">{{ actionError }}</text>
        <text class="inline-retry" @click="actionError = ''">知道了</text>
      </view>

      <view v-if="loaded && items.length" class="footer">
        <button
          v-if="hasMore || loading"
          class="btn-ghost load-more"
          hover-class="none"
          :disabled="loading"
          @click="load(false)"
        >
          {{ loading ? '加载中…' : `加载更多（${items.length} / ${total}）` }}
        </button>
        <text v-else class="footer-note">已全部加载，共 {{ total }} 张</text>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.stocktakes {
  padding-top: 20rpx;
}

.notice-title {
  display: block;
  font-size: 29rpx;
  font-weight: 600;
  color: #9c3a26;
}

.notice-text {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: #6e7585;
  line-height: 1.55;
}

.chips {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}

.chip {
  padding: 10rpx 26rpx;
  border-radius: 999rpx;
  background: #e9edf3;
  font-size: 24rpx;
  color: #6e7585;
}

.chip.active {
  background: #1f3a63;
  color: #ffffff;
  font-weight: 600;
}

.filter-card {
  padding: 4rpx 26rpx 22rpx;
}

.filter-row {
  display: flex;
  align-items: center;
  min-height: 82rpx;
}

.filter-label {
  width: 120rpx;
  flex-shrink: 0;
  font-size: 25rpx;
  color: #6e7585;
}

.picker-flex {
  flex: 1;
}

.picker-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  font-size: 27rpx;
  color: #1c2230;
}

.picker-arrow {
  margin-left: 10rpx;
  color: #a2a8b6;
}

.create-btn {
  width: 100%;
  height: 80rpx;
  margin-top: 12rpx;
  font-size: 27rpx;
}

.create-note {
  display: block;
  margin-top: 12rpx;
  font-size: 23rpx;
  color: #8a90a2;
}

.summary {
  margin: 24rpx 4rpx 14rpx;
}

.summary-text {
  font-size: 23rpx;
  color: #8a90a2;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.stocktake-card {
  padding: 24rpx;
}

.card-head {
  margin-bottom: 10rpx;
}

.stocktake-no {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
  font-weight: 700;
  color: #1c2230;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 5rpx 16rpx;
  border-radius: 999rpx;
  font-size: 21rpx;
  font-weight: 600;
}

.tone-wait {
  background: #fbf1e3;
  color: #a86a1f;
}

.tone-done {
  background: #e9f5ef;
  color: #2f7d5b;
}

.tone-quiet {
  background: #f1f2f5;
  color: #8a90a2;
}

.meta-row {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 22rpx;
  color: #6e7585;
}

.diff-value {
  font-size: 24rpx;
  font-weight: 700;
  color: #2f7d5b;
}

.diff-active {
  color: #b4702f;
}

.line-table {
  margin-top: 16rpx;
  padding: 14rpx 18rpx;
  background: #f7f9fc;
  border-radius: 14rpx;
}

.line-row {
  display: flex;
  align-items: center;
  padding: 6rpx 0;
  font-size: 21rpx;
  color: #586176;
}

.line-sku {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-batch {
  width: 150rpx;
  flex-shrink: 0;
  margin-left: 10rpx;
  color: #8a90a2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-qty {
  width: 190rpx;
  flex-shrink: 0;
  text-align: right;
}

.line-diff {
  width: 70rpx;
  flex-shrink: 0;
  text-align: right;
  font-weight: 700;
  color: #2f7d5b;
}

.card-actions {
  margin-top: 18rpx;
}

.post-btn {
  width: 100%;
  height: 76rpx;
  font-size: 26rpx;
}

.posted-note {
  display: block;
  margin-top: 14rpx;
  font-size: 21rpx;
  color: #a2a8b6;
}

.inline-error {
  margin-top: 18rpx;
  padding: 18rpx 22rpx;
  background: #fdf1ee;
  border-radius: 14rpx;
  font-size: 23rpx;
  color: #9c3a26;
}

.inline-retry {
  margin-left: 12rpx;
  font-weight: 600;
  text-decoration: underline;
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.footer {
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
  color: #a2a8b6;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: #a2a8b6;
}
</style>
