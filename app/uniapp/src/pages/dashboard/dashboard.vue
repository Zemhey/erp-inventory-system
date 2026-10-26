<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { invApi, type InventoryBalance, type LedgerEntry } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission, logout, session } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { bizTypeLabel, formatDateTime, signedQty } from '@/utils/format'
import { openInventory } from '@/utils/navigation'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

const loading = ref(false)
const loadedOnce = ref(false)
const loadError = ref('')

const balances = ref<InventoryBalance[]>([])
const recentLedger = ref<LedgerEntry[]>([])
const consistent = ref<boolean | null>(null)
const consistencyError = ref(false)

const { loadDictionary, skuText, warehouseText, warning: dictWarning } = useDictionary()

const allowed = computed(() => hasPermission('inv:read'))
const displayName = computed(() => session.displayName || session.username || '用户')
const roleText = computed(() => session.roles.join(' / ') || '未分配角色')

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const todayText = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}年${now.getMonth() + 1}月${now.getDate()}日 星期${
    WEEKDAYS[now.getDay()]
  }`
})

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '凌晨好'
  if (hour < 12) return '上午好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const rowCount = computed(() => balances.value.length)
const skuCount = computed(() => new Set(balances.value.map((row) => row.skuId)).size)
const totalOnHand = computed(() => balances.value.reduce((sum, row) => sum + row.onHandQty, 0))
const totalReserved = computed(() => balances.value.reduce((sum, row) => sum + row.reservedQty, 0))
const totalAvailable = computed(() => balances.value.reduce((sum, row) => sum + row.availableQty, 0))

async function load(fromPull = false): Promise<void> {
  if (loading.value) {
    if (fromPull) uni.stopPullDownRefresh()
    return
  }

  loading.value = true
  loadError.value = ''
  consistencyError.value = false

  if (!allowed.value) {
    loadedOnce.value = true
    loading.value = false
    if (fromPull) uni.stopPullDownRefresh()
    return
  }

  const [balanceResult, ledgerResult, consistencyResult] = await Promise.all([
    safe(invApi.query({})),
    safe(invApi.ledger({ page: 1, size: 5 })),
    safe(invApi.consistencyCheck()),
    safe(loadDictionary()),
  ])

  const failed: string[] = []
  let firstError: unknown = null

  if (balanceResult.ok) {
    balances.value = balanceResult.value
  } else {
    balances.value = []
    failed.push('库存概览')
    firstError = firstError ?? balanceResult.error
  }

  if (ledgerResult.ok) {
    recentLedger.value = ledgerResult.value.items
  } else {
    recentLedger.value = []
    failed.push('最近流水')
    firstError = firstError ?? ledgerResult.error
  }

  if (consistencyResult.ok) {
    consistent.value = consistencyResult.value.consistent
  } else {
    // 读不到 ≠ 漂移：显示为"未知"，不制造恐慌（与 Web 工作台同一条纪律）
    consistent.value = null
    consistencyError.value = true
  }

  if (failed.length) {
    loadError.value = `${failed.join('、')}加载失败：${errorMessage(firstError)}`
  }

  loadedOnce.value = true
  loading.value = false
  if (fromPull) uni.stopPullDownRefresh()
}

onShow(() => {
  if (!ensureLoggedIn()) return
  load()
})

onPullDownRefresh(() => {
  load(true)
})

function confirmLogout(): void {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账号？',
    success: (result) => {
      if (result.confirm) logout()
    },
  })
}

function openTab(path: string): void {
  uni.switchTab({ url: path })
}

/** 非 tab 页用 navigateTo：switchTab 打非 tabBar 页面会静默失败。 */
function openPage(path: string): void {
  uni.navigateTo({ url: path })
}
</script>

<template>
  <view class="page dashboard">
    <view class="hero">
      <view class="row-between hero-top">
        <text class="hero-kicker">云裳 · 移动工作台</text>
        <view class="hero-actions">
          <text class="hero-action" @click="load()">刷新</text>
          <text class="hero-action" @click="confirmLogout">退出</text>
        </view>
      </view>

      <text class="hero-greeting">{{ greeting }}，{{ displayName }}</text>
      <text class="hero-date">{{ todayText }} · {{ roleText }}</text>

      <view class="hero-metric">
        <text class="hero-metric-label">可用库存合计</text>
        <view class="hero-metric-line">
          <text class="hero-metric-value">{{ allowed && loadedOnce ? totalAvailable : '—' }}</text>
          <text class="hero-metric-unit">件</text>
        </view>
        <text class="hero-metric-hint">可用 = 实存 − 预占（由后端计算，前端只展示）</text>
      </view>
    </view>

    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有库存读权限</text>
      <text class="notice-text">
        工作台概览依赖 inv:read。请联系管理员分配权限；账号信息仍可在本页查看。
      </text>
    </view>

    <template v-else>
      <view v-if="loadError" class="error-strip">
        <text class="error-strip-text">{{ loadError }}</text>
        <text class="error-strip-retry" @click="load()">重试</text>
      </view>

      <view class="grid">
        <view class="card stat">
          <text class="stat-label">库存行</text>
          <text class="stat-value">{{ loadedOnce ? rowCount : '-' }}</text>
          <text class="stat-hint">SKU × 仓库 × 批次</text>
        </view>
        <view class="card stat">
          <text class="stat-label">有库存 SKU</text>
          <text class="stat-value">{{ loadedOnce ? skuCount : '-' }}</text>
          <text class="stat-hint">出现在余额表中的 SKU</text>
        </view>
      </view>

      <view class="card triple">
        <view class="triple-cell">
          <text class="triple-value">{{ loadedOnce ? totalOnHand : '-' }}</text>
          <text class="triple-label">实存</text>
        </view>
        <view class="triple-divider" />
        <view class="triple-cell">
          <text class="triple-value reserved">{{ loadedOnce ? totalReserved : '-' }}</text>
          <text class="triple-label">预占</text>
        </view>
        <view class="triple-divider" />
        <view class="triple-cell">
          <text class="triple-value available">{{ loadedOnce ? totalAvailable : '-' }}</text>
          <text class="triple-label">可用</text>
        </view>
      </view>

      <view
        class="card consistency"
        :class="{
          'consistency-ok': consistent === true,
          'consistency-bad': consistent === false,
          'consistency-unknown': consistent === null,
        }"
      >
        <view class="row-between">
          <text class="section-title">账实一致性</text>
          <text class="tag">
            {{ consistent === true ? '一致' : consistent === false ? '漂移' : '未知' }}
          </text>
        </view>
        <text class="consistency-text">
          {{
            consistent === true
              ? '所有 (SKU, 仓库, 批次) 的实存都等于流水净和（不变量 INV-2）'
              : consistent === false
                ? '检测到实存与流水不一致，请立即排查'
                : consistencyError
                  ? '一致性检查暂不可用 —— 读不到结果不代表库存有问题'
                  : '正在检查…'
          }}
        </text>
      </view>

      <view class="section-head row-between">
        <text class="section-title">最近流水</text>
        <text class="link" @click="openInventory('ledger')">查看全部</text>
      </view>

      <view v-if="recentLedger.length" class="ledger-list">
        <view
          v-for="entry in recentLedger"
          :key="entry.id"
          class="card ledger-item"
          @click="openInventory('ledger')"
        >
          <view class="ledger-badge" :class="entry.changeQty > 0 ? 'badge-in' : 'badge-out'">
            {{ entry.changeQty > 0 ? '入' : '出' }}
          </view>
          <view class="ledger-main">
            <text class="ledger-title">{{ skuText(entry.skuId) }}</text>
            <text class="ledger-sub">
              {{ bizTypeLabel(entry.bizType) }} · {{ warehouseText(entry.warehouseId) }}
            </text>
            <text class="ledger-time">{{ formatDateTime(entry.occurredAt) }} · {{ entry.bizRefNo }}</text>
          </view>
          <text class="ledger-qty" :class="entry.changeQty > 0 ? 'qty-in' : 'qty-out'">
            {{ signedQty(entry.changeQty) }}
          </text>
        </view>
      </view>
      <view v-else-if="loadedOnce" class="card empty-block">
        还没有库存流水。进货入库或出货后，这里会显示最近 5 条。
      </view>
      <view v-else class="card loading-block">正在读取库存数据…</view>

      <view class="quick-actions">
        <view class="card action" @click="openInventory('balance')">
          <text class="action-title">库存余额</text>
          <text class="action-sub">按仓库、批次查实存 / 预占 / 可用</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" @click="openInventory('ledger')">
          <text class="action-title">库存流水</text>
          <text class="action-sub">按单据号追溯每一笔出入库</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" @click="openTab('/pages/sales/sales')">
          <text class="action-title">出货单</text>
          <text class="action-sub">查看单据并在现场确认出货</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" @click="openTab('/pages/purchase/purchase')">
          <text class="action-title">进货单</text>
          <text class="action-sub">查看单据并在现场确认入库</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" @click="openTab('/pages/products/products')">
          <text class="action-title">商品与 SKU</text>
          <text class="action-sub">查款号、条码与零售价</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" data-entry="quotes" @click="openPage('/pages/quotes/quotes')">
          <text class="action-title">报价单</text>
          <text class="action-sub">现场提交、审核与驳回，通过后可直接转出货单</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" data-entry="shipments" @click="openPage('/pages/shipments/shipments')">
          <text class="action-title">发货扫码作业</text>
          <text class="action-sub">按发货单逐行扫码确认数量，扫满后整单出库</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" data-entry="scan-receive" @click="openPage('/pages/purchase/scan-receive')">
          <text class="action-title">扫码收货</text>
          <text class="action-sub">选待收货进货单，按 SKU 条码逐条累计入库</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" data-entry="stocktakes" @click="openPage('/pages/stocktakes/stocktakes')">
          <text class="action-title">库存盘点</text>
          <text class="action-sub">扫码盘点、查看差异并过账生成调整流水</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" data-entry="receivables" @click="openPage('/pages/receivables/receivables')">
          <text class="action-title">客户应收与收款</text>
          <text class="action-sub">看余额构成，现场登记收款并核销</text>
          <text class="action-arrow">›</text>
        </view>
        <view class="card action" data-entry="approvals" @click="openPage('/pages/approvals/approvals')">
          <text class="action-title">待我审批</text>
          <text class="action-sub">按节点权限筛出的我的待办，可同意、驳回、撤回、转办</text>
          <text class="action-arrow">›</text>
        </view>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
    </template>
  </view>
</template>

<style scoped>
.dashboard {
  padding-top: 24rpx;
}

.hero {
  background: linear-gradient(135deg, #1b2233 0%, #2c4066 100%);
  border-radius: 30rpx;
  padding: 32rpx;
  color: #ffffff;
  box-shadow: 0 18rpx 46rpx rgba(21, 33, 56, 0.28);
}

.hero-top {
  margin-bottom: 30rpx;
}

.hero-kicker {
  font-size: 22rpx;
  letter-spacing: 2rpx;
  color: #b9c4d8;
}

.hero-actions {
  display: flex;
  gap: 26rpx;
}

.hero-action {
  font-size: 24rpx;
  color: #d8b36a;
}

.hero-greeting {
  display: block;
  font-size: 42rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.hero-date {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: #9fabc2;
}

.hero-metric {
  margin-top: 40rpx;
  padding-top: 28rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.14);
}

.hero-metric-label {
  display: block;
  font-size: 23rpx;
  color: #b9c4d8;
  letter-spacing: 2rpx;
}

.hero-metric-line {
  display: flex;
  align-items: baseline;
  margin-top: 6rpx;
}

.hero-metric-value {
  font-size: 76rpx;
  font-weight: 700;
  line-height: 1.1;
  color: #ffffff;
}

.hero-metric-unit {
  margin-left: 12rpx;
  font-size: 24rpx;
  color: #b9c4d8;
}

.hero-metric-hint {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #8493ad;
}

.notice {
  margin-top: 24rpx;
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

.error-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24rpx;
  padding: 20rpx 24rpx;
  background: #fdf1ee;
  border: 1rpx solid #f3d3ca;
  border-radius: 16rpx;
}

.error-strip-text {
  flex: 1;
  font-size: 24rpx;
  color: #9c3a26;
  line-height: 1.45;
}

.error-strip-retry {
  flex-shrink: 0;
  margin-left: 20rpx;
  font-size: 24rpx;
  font-weight: 600;
  color: #9c3a26;
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20rpx;
  margin-top: 24rpx;
}

.stat {
  padding: 26rpx 24rpx;
}

.stat-label {
  display: block;
  font-size: 23rpx;
  color: #8a90a2;
}

.stat-value {
  display: block;
  margin-top: 8rpx;
  font-size: 46rpx;
  font-weight: 700;
  color: #1c2230;
}

.stat-hint {
  display: block;
  margin-top: 4rpx;
  font-size: 20rpx;
  color: #a2a8b6;
}

.triple {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
  padding: 26rpx 16rpx;
}

.triple-cell {
  flex: 1;
  text-align: center;
}

.triple-value {
  display: block;
  font-size: 40rpx;
  font-weight: 700;
  color: #1c2230;
}

.triple-value.reserved {
  color: #b4702f;
}

.triple-value.available {
  color: #2f7d5b;
}

.triple-label {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #8a90a2;
}

.triple-divider {
  width: 1rpx;
  height: 56rpx;
  background: #edf0f5;
}

.consistency {
  margin-top: 20rpx;
}

.consistency-text {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.55;
  color: #6e7585;
}

.consistency-ok {
  border-left: 8rpx solid #2f7d5b;
}

.consistency-bad {
  border-left: 8rpx solid #b4462f;
}

.consistency-unknown {
  border-left: 8rpx solid #b4702f;
}

.section-head {
  margin: 34rpx 4rpx 16rpx;
}

.link {
  font-size: 24rpx;
  color: #2c4066;
}

.ledger-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}

.ledger-item {
  display: flex;
  align-items: center;
  padding: 24rpx;
}

.ledger-badge {
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26rpx;
  font-weight: 600;
  margin-right: 20rpx;
}

.badge-in {
  background: #e9f5ef;
  color: #2f7d5b;
}

.badge-out {
  background: #fdf0ec;
  color: #b4462f;
}

.ledger-main {
  flex: 1;
  min-width: 0;
}

.ledger-title {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #1c2230;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ledger-sub {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #6e7585;
}

.ledger-time {
  display: block;
  margin-top: 2rpx;
  font-size: 20rpx;
  color: #a2a8b6;
}

.ledger-qty {
  flex-shrink: 0;
  margin-left: 16rpx;
  font-size: 34rpx;
  font-weight: 700;
}

.qty-in {
  color: #2f7d5b;
}

.qty-out {
  color: #b4462f;
}

.quick-actions {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 26rpx;
}

.action {
  position: relative;
  padding: 26rpx 70rpx 26rpx 26rpx;
}

.action-title {
  display: block;
  font-size: 29rpx;
  font-weight: 600;
  color: #1c2230;
}

.action-sub {
  display: block;
  margin-top: 6rpx;
  font-size: 23rpx;
  color: #8a90a2;
}

.action-arrow {
  position: absolute;
  right: 28rpx;
  top: 50%;
  transform: translateY(-50%);
  font-size: 44rpx;
  color: #b6bcc8;
}

.dict-warning {
  display: block;
  margin-top: 20rpx;
  text-align: center;
  font-size: 21rpx;
  color: #a2a8b6;
}
</style>
