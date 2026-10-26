<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { invApi, type InventoryBalance, type LedgerEntry } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { bizTypeLabel, formatDate, formatDateTime, signedQty } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { consumeInventoryBizRefNo, consumeInventoryTab, type InventoryTab } from '@/utils/navigation'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

const PAGE_SIZE = 20

const tab = ref<InventoryTab>('balance')
const allowed = computed(() => hasPermission('inv:read'))

const { warehouses, loadDictionary, skuText, warehouseText, warning: dictWarning } = useDictionary()

// ─────────────────────────── 筛选 ───────────────────────────

const warehouseId = ref<number | undefined>(undefined)
const onlyAvailable = ref(false)
const batchNo = ref('')
const bizRefNo = ref('')

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

function onWarehouseChange(event: unknown): void {
  const value = (event as { detail?: { value?: string | number } })?.detail?.value
  const index = Number(value ?? 0)
  warehouseId.value = index === 0 ? undefined : warehouses.value[index - 1]?.id
  applyFilters()
}

function onOnlyAvailableChange(event: unknown): void {
  const value = (event as { detail?: { value?: boolean } })?.detail?.value
  onlyAvailable.value = Boolean(value)
  applyFilters()
}

// ─────────────────────────── 余额 ───────────────────────────

const balances = ref<InventoryBalance[]>([])
const balanceLoading = ref(false)
const balanceLoaded = ref(false)
const balanceError = ref('')

const balanceFiltered = computed(
  () =>
    warehouseId.value !== undefined || batchNo.value.trim() !== '' || onlyAvailable.value,
)

let balanceQueued = false

const balanceTotals = computed(() => {
  let onHand = 0
  let reserved = 0
  let available = 0
  for (const row of balances.value) {
    onHand += row.onHandQty
    reserved += row.reservedQty
    available += row.availableQty
  }
  return { onHand, reserved, available }
})

async function loadBalances(): Promise<void> {
  if (!allowed.value) return
  if (balanceLoading.value) {
    // 筛选在请求飞行中被再次修改：记住"还欠一次"，本次结束后补跑，避免界面与筛选不一致
    balanceQueued = true
    return
  }

  balanceLoading.value = true
  balanceError.value = ''

  const result = await safe(
    invApi.query({
      warehouseId: warehouseId.value,
      batchNo: batchNo.value.trim() || undefined,
      onlyAvailable: onlyAvailable.value,
    }),
  )

  if (result.ok) {
    balances.value = result.value
  } else {
    balances.value = []
    balanceError.value = errorMessage(result.error)
  }

  balanceLoaded.value = true
  balanceLoading.value = false

  if (balanceQueued) {
    balanceQueued = false
    void loadBalances()
  }
}

// ─────────────────────────── 流水（分页） ───────────────────────────

const ledgerItems = ref<LedgerEntry[]>([])
const ledgerTotal = ref(0)
const ledgerPage = ref(0)
const ledgerLoaded = ref(false)
const ledgerLoading = ref(false)
const ledgerError = ref('')

// 用页码判断而不是 items.length：即使因并发写入触发去重，也不会无限"加载更多"
const hasMore = computed(() => ledgerPage.value * PAGE_SIZE < ledgerTotal.value)

let ledgerQueuedReset = false

function mergeLedger(incoming: LedgerEntry[], reset: boolean): void {
  const merged = reset ? incoming : [...ledgerItems.value, ...incoming]
  // 分页期间若有并发写入，offset 分页可能重复返回同一行；按 id 去重，断言"无重复"才成立
  const seen = new Set<number>()
  ledgerItems.value = merged.filter((entry) => {
    if (seen.has(entry.id)) return false
    seen.add(entry.id)
    return true
  })
}

async function loadLedger(reset = false): Promise<void> {
  if (!allowed.value) return
  if (ledgerLoading.value) {
    if (reset) ledgerQueuedReset = true
    return
  }
  if (!reset && !hasMore.value) return

  ledgerLoading.value = true
  if (reset) ledgerError.value = ''

  const nextPage = reset ? 1 : ledgerPage.value + 1
  const result = await safe(
    invApi.ledger({
      warehouseId: warehouseId.value,
      bizRefNo: bizRefNo.value.trim() || undefined,
      page: nextPage,
      size: PAGE_SIZE,
    }),
  )

  if (result.ok) {
    mergeLedger(result.value.items, reset)
    ledgerTotal.value = result.value.total
    ledgerPage.value = result.value.page
  } else {
    if (reset) {
      ledgerItems.value = []
      ledgerTotal.value = 0
      ledgerPage.value = 0
    }
    ledgerError.value = errorMessage(result.error)
  }

  ledgerLoaded.value = true
  ledgerLoading.value = false

  if (ledgerQueuedReset) {
    ledgerQueuedReset = false
    void loadLedger(true)
  }
}

// ─────────────────────────── 交互 ───────────────────────────

function selectTab(next: InventoryTab): void {
  if (tab.value === next) return
  tab.value = next
  if (next === 'ledger') {
    loadLedger(true)
  } else {
    loadBalances()
  }
}

function applyFilters(): void {
  if (tab.value === 'balance') {
    loadBalances()
  } else {
    loadLedger(true)
  }
}

function resetFilters(): void {
  warehouseId.value = undefined
  onlyAvailable.value = false
  batchNo.value = ''
  bizRefNo.value = ''
  applyFilters()
}

onShow(() => {
  if (!ensureLoggedIn()) return

  const pending = consumeInventoryTab()
  if (pending) tab.value = pending

  // 从盘点/收货页跳过来时带着单号：直接落到"该单据的流水"，而不是让人再输一遍
  const pendingBizRef = consumeInventoryBizRefNo()
  if (pendingBizRef) bizRefNo.value = pendingBizRef

  if (!allowed.value) return

  loadDictionary()

  if (tab.value === 'ledger') {
    loadLedger(true)
  } else {
    loadBalances()
  }
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    if (allowed.value) {
      if (tab.value === 'ledger') {
        await loadLedger(true)
      } else {
        await loadBalances()
      }
    }
    uni.stopPullDownRefresh()
  })()
})

onReachBottom(() => {
  if (tab.value === 'ledger' && !ledgerLoading.value && hasMore.value) {
    loadLedger(false)
  }
})
</script>

<template>
  <view class="page inventory">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有库存读权限</text>
      <text class="notice-text">
        本页依赖 inv:read。请联系管理员分配权限；前端判断只影响入口展示，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view class="tabs">
        <view
          class="tab"
          :class="{ active: tab === 'balance' }"
          @click="selectTab('balance')"
        >
          库存余额
        </view>
        <view
          class="tab"
          :class="{ active: tab === 'ledger' }"
          @click="selectTab('ledger')"
        >
          库存流水
        </view>
      </view>

      <view class="card filter-card">
        <view class="filter-row">
          <text class="filter-label">仓库</text>
          <picker
            class="picker-flex"
            :range="warehouseRange"
            :value="warehouseIndex"
            @change="onWarehouseChange"
          >
            <view class="picker-value">
              <text>{{ warehouseRange[warehouseIndex] }}</text>
              <text class="picker-arrow">⌄</text>
            </view>
          </picker>
        </view>

        <view class="filter-row">
          <text class="filter-label">{{ tab === 'balance' ? '批次' : '单据号' }}</text>
          <input
            v-if="tab === 'balance'"
            v-model="batchNo"
            class="filter-input"
            placeholder="批次号（精确匹配）"
            confirm-type="search"
            @confirm="applyFilters"
          />
          <input
            v-else
            v-model="bizRefNo"
            class="filter-input"
            placeholder="来源单据号，如 PO/SO 单号"
            confirm-type="search"
            @confirm="applyFilters"
          />
        </view>

        <view v-if="tab === 'balance'" class="filter-row switch-row">
          <text class="filter-label">仅看可用</text>
          <switch
            :checked="onlyAvailable"
            color="#1f3a63"
            style="transform: scale(0.85)"
            @change="onOnlyAvailableChange"
          />
        </view>

        <view class="filter-actions">
          <button class="btn-ghost filter-btn" hover-class="none" @click="resetFilters">重置</button>
          <button class="btn-primary filter-btn" hover-class="none" @click="applyFilters">查询</button>
        </view>
      </view>

      <!-- ───────────── 余额 ───────────── -->
      <template v-if="tab === 'balance'">
        <view v-if="balanceLoaded" class="summary row-between">
          <text class="summary-text">
            当前筛选 {{ balances.length }} 行{{ balanceFiltered ? '（已过滤）' : '' }}
          </text>
          <text class="summary-text">
            可用合计
            <text class="summary-strong">{{ balanceTotals.available }}</text>
            件
          </text>
        </view>

        <view v-if="balanceLoading && !balanceLoaded" class="card loading-block">
          正在读取库存余额…
        </view>

        <view v-else-if="balanceError" class="card error-block">
          <text class="block-text">{{ balanceError }}</text>
          <button class="btn-ghost block-btn" hover-class="none" @click="loadBalances">重试</button>
        </view>

        <view v-else-if="!balances.length" class="card empty-block">
          {{
            balanceFiltered
              ? '当前筛选没有匹配的库存行。可以放宽批次或切换仓库再试。'
              : '还没有库存。完成进货入库后，这里会出现余额。'
          }}
        </view>

        <view v-else class="list">
          <view
            v-for="row in balances"
            :key="`${row.skuId}-${row.warehouseId}-${row.batchNo}`"
            class="card balance-card"
            :data-balance-key="`${row.skuId}-${row.warehouseId}-${row.batchNo}`"
          >
            <view class="row-between card-head">
              <text class="sku-name">{{ skuText(row.skuId) }}</text>
              <text class="tag">{{ row.batchNo || '无批次' }}</text>
            </view>

            <view class="qty-row">
              <view class="qty-cell">
                <text class="qty-value">{{ row.onHandQty }}</text>
                <text class="qty-label">实存</text>
              </view>
              <view class="qty-cell">
                <text class="qty-value reserved">{{ row.reservedQty }}</text>
                <text class="qty-label">预占</text>
              </view>
              <view class="qty-cell">
                <text class="qty-value available">{{ row.availableQty }}</text>
                <text class="qty-label">可用</text>
              </view>
            </view>

            <view class="row-between card-foot">
              <text class="meta">{{ warehouseText(row.warehouseId) }}</text>
              <text class="meta">效期 {{ row.expiryDate ? formatDate(row.expiryDate) : '—' }}</text>
            </view>
          </view>
        </view>
      </template>

      <!-- ───────────── 流水 ───────────── -->
      <template v-else>
        <view v-if="ledgerLoaded" class="summary row-between">
          <text class="summary-text">共 {{ ledgerTotal }} 条流水</text>
          <text class="summary-text">已加载 {{ ledgerItems.length }} 条</text>
        </view>

        <view v-if="ledgerLoading && !ledgerLoaded" class="card loading-block">
          正在读取库存流水…
        </view>

        <view v-else-if="ledgerError && !ledgerItems.length" class="card error-block">
          <text class="block-text">{{ ledgerError }}</text>
          <button class="btn-ghost block-btn" hover-class="none" @click="loadLedger(true)">重试</button>
        </view>

        <view v-else-if="!ledgerItems.length" class="card empty-block">
          {{
            bizRefNo.trim()
              ? '这个单据号没有匹配的流水，请核对单号。'
              : '还没有库存流水。进货入库或出货后，每一笔变动都会记录在这里。'
          }}
        </view>

        <view v-else class="list">
          <view
            v-for="entry in ledgerItems"
            :key="entry.id"
            class="card ledger-card"
            :data-entry-id="entry.id"
          >
            <view class="row-between card-head">
              <view class="row">
                <text class="type-badge" :class="entry.changeQty > 0 ? 'badge-in' : 'badge-out'">
                  {{ entry.changeQty > 0 ? '入' : '出' }}
                </text>
                <text class="type-name">{{ bizTypeLabel(entry.bizType) }}</text>
              </view>
              <text class="change-qty" :class="entry.changeQty > 0 ? 'qty-in' : 'qty-out'">
                {{ signedQty(entry.changeQty) }}
              </text>
            </view>

            <text class="sku-name ledger-sku">{{ skuText(entry.skuId) }}</text>

            <view class="row-between card-foot">
              <text class="meta">{{ warehouseText(entry.warehouseId) }}</text>
              <text class="meta">{{ entry.batchNo || '无批次' }}</text>
            </view>

            <view class="row-between card-foot">
              <text class="meta">单据 {{ entry.bizRefNo }}</text>
              <text class="meta">成本 {{ formatFen(entry.unitCostAmount) }}</text>
            </view>

            <text class="time-text">{{ formatDateTime(entry.occurredAt) }}</text>
          </view>
        </view>

        <view v-if="ledgerLoaded" class="ledger-footer">
          <!--
            ★ 加载更多按钮在请求飞行中**不能被 v-if 换掉**：
              换成"加载中…"会让节点从 DOM detach，连点/自动化都会撞在重新挂载的节点上。
              这里保持同一节点，只把文案切成"加载中…"并禁用点击。
          -->
          <button
            v-if="hasMore || ledgerLoading"
            class="btn-ghost load-more"
            hover-class="none"
            :disabled="ledgerLoading"
            @click="loadLedger(false)"
          >
            {{ ledgerLoading ? '加载中…' : `加载更多（${ledgerItems.length} / ${ledgerTotal}）` }}
          </button>
          <text v-else-if="ledgerItems.length" class="footer-note">
            已全部加载，共 {{ ledgerTotal }} 条
          </text>
          <view v-if="ledgerError && ledgerItems.length" class="inline-error">
            <text class="block-text">{{ ledgerError }}</text>
            <text class="inline-retry" @click="loadLedger(false)">重试</text>
          </view>
        </view>
      </template>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.inventory {
  padding-top: 20rpx;
}

.notice {
  margin-top: 12rpx;
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

.tabs {
  display: flex;
  padding: 6rpx;
  margin-bottom: 20rpx;
  background: #e9edf3;
  border-radius: 18rpx;
}

.tab {
  flex: 1;
  height: 72rpx;
  line-height: 72rpx;
  text-align: center;
  border-radius: 14rpx;
  font-size: 27rpx;
  color: #6e7585;
}

.tab.active {
  background: #ffffff;
  color: #1f3a63;
  font-weight: 600;
  box-shadow: 0 4rpx 14rpx rgba(31, 58, 99, 0.12);
}

.filter-card {
  padding: 8rpx 26rpx 22rpx;
}

.filter-row {
  display: flex;
  align-items: center;
  min-height: 82rpx;
  border-bottom: 1rpx solid #f0f2f6;
}

.filter-row:last-of-type {
  border-bottom: none;
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
  font-size: 26rpx;
}

.filter-input {
  flex: 1;
  height: 72rpx;
  font-size: 27rpx;
  color: #1c2230;
  text-align: right;
}

.switch-row {
  justify-content: space-between;
}

.filter-actions {
  display: flex;
  gap: 18rpx;
  margin-top: 20rpx;
}

.filter-btn {
  flex: 1;
  height: 76rpx;
  font-size: 26rpx;
}

.summary {
  margin: 24rpx 4rpx 14rpx;
}

.summary-text {
  font-size: 23rpx;
  color: #8a90a2;
}

.summary-strong {
  color: #1f3a63;
  font-weight: 700;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.balance-card,
.ledger-card {
  padding: 26rpx;
}

.card-head {
  margin-bottom: 20rpx;
}

.sku-name {
  flex: 1;
  min-width: 0;
  font-size: 28rpx;
  font-weight: 600;
  color: #1c2230;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qty-row {
  display: flex;
  align-items: center;
  padding: 18rpx 0;
  background: #f7f9fc;
  border-radius: 16rpx;
}

.qty-cell {
  flex: 1;
  text-align: center;
}

.qty-value {
  display: block;
  font-size: 40rpx;
  font-weight: 700;
  color: #1c2230;
}

.qty-value.reserved {
  color: #b4702f;
}

.qty-value.available {
  color: #2f7d5b;
}

.qty-label {
  display: block;
  margin-top: 2rpx;
  font-size: 21rpx;
  color: #8a90a2;
}

.card-foot {
  margin-top: 18rpx;
}

.meta {
  font-size: 22rpx;
  color: #8a90a2;
}

.type-badge {
  width: 48rpx;
  height: 48rpx;
  border-radius: 14rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  font-weight: 600;
  margin-right: 12rpx;
}

.badge-in {
  background: #e9f5ef;
  color: #2f7d5b;
}

.badge-out {
  background: #fdf0ec;
  color: #b4462f;
}

.type-name {
  font-size: 27rpx;
  font-weight: 600;
  color: #1c2230;
}

.change-qty {
  font-size: 38rpx;
  font-weight: 700;
}

.qty-in {
  color: #2f7d5b;
}

.qty-out {
  color: #b4462f;
}

.ledger-sku {
  display: block;
  margin-bottom: 16rpx;
}

.time-text {
  display: block;
  margin-top: 14rpx;
  font-size: 21rpx;
  color: #a2a8b6;
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.ledger-footer {
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

.inline-error {
  margin-top: 16rpx;
  padding: 18rpx 22rpx;
  background: #fdf1ee;
  border-radius: 14rpx;
  text-align: center;
  font-size: 23rpx;
  color: #9c3a26;
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
  color: #a2a8b6;
}
</style>
