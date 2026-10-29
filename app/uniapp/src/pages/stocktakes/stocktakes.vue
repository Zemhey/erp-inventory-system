<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { stocktakeApi, type Stocktake } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import { BRAND_CONFIRM } from '@/utils/dialog'
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
 *
 * 分页：一页 20 张、翻页看下一页（盘点卡片带明细行，一屏也就一两张，
 * 无限滚动滑起来最费劲）。
 */

const PAGE_SIZE = 20

const { themeClass } = useTheme()

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
const page = ref(1)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')
const postingId = ref(0)
const actionError = ref('')

const filtered = computed(() => statusFilter.value !== '' || warehouseId.value !== undefined)

let queued = false
/** 上一次真正加载成功的页码：请求失败时把翻页条退回到这里，不停在没数据的页上。 */
let goodPage = 1

function lineCount(item: Stocktake): number {
  return item.lines?.length ?? 0
}

function diffText(value: number): string {
  return value === 0 ? '0' : signedQty(value)
}

async function loadStocktakes(): Promise<void> {
  if (!allowed.value) return
  if (loading.value) {
    // 筛选在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑，避免界面与筛选不一致
    queued = true
    return
  }

  loading.value = true
  error.value = ''

  const result = await safe(
    stocktakeApi.list({
      status: statusFilter.value || undefined,
      warehouseId: warehouseId.value,
      page: page.value,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    items.value = result.value.items
    total.value = result.value.total
    // 以后端回的值为准：筛选条件变了以后，请求的那一页可能已经不存在
    page.value = result.value.page
    goodPage = result.value.page
  } else {
    items.value = []
    page.value = goodPage
    error.value = errorMessage(result.error)
  }

  loaded.value = true
  loading.value = false

  if (queued) {
    queued = false
    void loadStocktakes()
  }
}

function goStocktakesPage(next: number): void {
  page.value = next
  void loadStocktakes()
}

/** 换筛选条件一律回到第 1 页：停在第 7 页换仓库，多半只会看到空列表。 */
function reloadStocktakes(): void {
  page.value = 1
  void loadStocktakes()
}

function selectStatus(value: string): void {
  if (statusFilter.value === value) return
  statusFilter.value = value
  reloadStocktakes()
}

function onWarehouseChange(event: unknown): void {
  const index = Number((event as { detail?: { value?: string | number } })?.detail?.value ?? 0)
  warehouseId.value = index === 0 ? undefined : warehouses.value[index - 1]?.id
  reloadStocktakes()
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
    confirmColor: BRAND_CONFIRM,
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
  // 停在原来那一页刷新：从扫码页退回来不该被弹回第 1 页
  void loadStocktakes()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    // 下拉是"刷新我正在看的这一页"，翻页请走下面的翻页条
    await loadStocktakes()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page stocktakes" :class="themeClass">
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
        <text class="summary-text">本页 {{ items.length }} 张</text>
      </view>

      <view v-if="loading && !loaded" class="card loading-block">正在读取盘点单…</view>

      <view v-else-if="error && !items.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="loadStocktakes">重试</button>
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

      <view v-if="loaded && total" class="pager-wrap">
        <list-pager
          :page="page"
          :size="PAGE_SIZE"
          :total="total"
          :loading="loading"
          unit="张"
          @change="goStocktakesPage"
        />
        <view v-if="error && items.length" class="inline-error">
          <text class="block-text">{{ error }}</text>
          <text class="inline-retry" @click="loadStocktakes">重试</text>
        </view>
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
  gap: 12rpx;
  margin-bottom: 16rpx;
}

.chip {
  padding: 10rpx 26rpx;
  border-radius: 999rpx;
  background: var(--c-fill-strong);
  font-size: 24rpx;
  color: var(--c-text-2);
}

.chip.active {
  background: var(--c-brand);
  color: var(--c-on-brand);
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
  color: var(--c-text-2);
}

.picker-flex {
  flex: 1;
}

.picker-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  font-size: 27rpx;
  color: var(--c-text);
}

.picker-arrow {
  margin-left: 10rpx;
  color: var(--c-text-4);
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
  color: var(--c-text-3);
}

.summary {
  margin: 24rpx 4rpx 14rpx;
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
  color: var(--c-text);
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
  background: var(--c-warn-wash);
  color: var(--c-warn);
}

.tone-done {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.tone-quiet {
  background: var(--c-fill-strong);
  color: var(--c-text-3);
}

.meta-row {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-2);
}

.diff-value {
  font-size: 24rpx;
  font-weight: 700;
  color: var(--c-success);
}

.diff-active {
  color: var(--c-warn);
}

.line-table {
  margin-top: 16rpx;
  padding: 14rpx 18rpx;
  background: var(--c-fill);
  border-radius: 14rpx;
}

.line-row {
  display: flex;
  align-items: center;
  padding: 6rpx 0;
  font-size: 21rpx;
  color: var(--c-text-2);
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
  color: var(--c-text-3);
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
  color: var(--c-success);
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
  color: var(--c-text-4);
}

.inline-error {
  margin-top: 18rpx;
  padding: 18rpx 22rpx;
  background: var(--c-danger-wash);
  border-radius: 14rpx;
  font-size: 23rpx;
  color: var(--c-danger);
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

.pager-wrap {
  margin-top: 20rpx;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
