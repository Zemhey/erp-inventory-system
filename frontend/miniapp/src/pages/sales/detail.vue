<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { salApi, shipmentApi, type SalesOrder } from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import {
  ACTION_META,
  ALLOWED_ACTIONS,
  canExecuteAction,
  isDocumentStatus,
  statusMeta,
} from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 出货单详情 + 单动作 ship。
 *
 * 按钮显隐只由「后端状态镜像 + sal:ship 权限」决定，不看数量、余额或日期。
 * 真正能否出货由 POST /sales-orders/{id}/ship 校验，失败消息原样展示。
 */

const allowed = computed(() => hasPermission('sal:read'))
const { loadDictionary, customerText, warehouseText, skuText, warning: dictWarning } =
  useDictionary()

const orderId = ref(0)
const order = ref<SalesOrder | null>(null)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const actionLoading = ref(false)
const actionError = ref('')

const statusAllowsShip = computed(
  () =>
    !!order.value &&
    isDocumentStatus(order.value.status) &&
    ALLOWED_ACTIONS.SALES[order.value.status].includes('ship'),
)

const canShip = computed(
  () =>
    !!order.value &&
    canExecuteAction('SALES', order.value.status, 'ship', hasPermission),
)

const actionHint = computed(() => {
  if (!order.value || canShip.value) return ''
  if (statusAllowsShip.value) {
    return '当前账号没有出货权限（sal:ship），请联系管理员。'
  }
  return `当前状态「${statusMeta(order.value.status).label}」不可出货；审核与作废请使用 Web 端。`
})

const shippedQty = computed(() =>
  (order.value?.lines ?? []).reduce((sum, line) => sum + (line.shippedQty ?? 0), 0),
)

async function load(): Promise<void> {
  if (!orderId.value) {
    error.value = '缺少单据 ID，无法打开详情。'
    loaded.value = true
    return
  }
  if (!allowed.value) return
  if (loading.value) return

  loading.value = true
  error.value = ''
  const result = await safe(salApi.get(orderId.value))
  if (result.ok) {
    order.value = result.value
  } else {
    order.value = null
    error.value = errorMessage(result.error)
  }
  loaded.value = true
  loading.value = false
}

function confirmShip(): void {
  if (!order.value || actionLoading.value) return
  uni.showModal({
    title: '确认出货',
    content: ACTION_META['SALES:ship'].confirmText,
    success: (result) => {
      if (result.confirm) void doShip()
    },
  })
}

async function doShip(): Promise<void> {
  if (!order.value || actionLoading.value) return
  actionLoading.value = true
  actionError.value = ''

  // X-Request-Id 由 api/client.ts 自动注入；页面不手写幂等键
  const result = await safe(salApi.ship(order.value.id))
  if (result.ok) {
    order.value = result.value
    uni.showToast({ title: '出货成功', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  actionLoading.value = false
}

function openLineSku(skuId: number): void {
  uni.navigateTo({ url: `/pages/products/sku-detail?id=${skuId}` })
}

/**
 * 「生成发货计划」入口（FR-SAL-06 的起点）。
 *
 * 可点条件与「确认出货」同一批状态：后端 createPlan 要 `order.status().isExecutable()`
 * （= APPROVED / PARTIAL），而 ALLOWED_ACTIONS.SALES 里能 ship 的正好就是这两个状态。
 * 两条路径是**并存**的能力，不是新旧替代：
 * - 确认出货：无拣货明细，按 FEFO 整单出库（老路径，仓库没有逐货位要求时最快）；
 * - 生成发货计划：按 ACTIVE 预占逐行拆出发货单，仓里扫码确认后再出库（可追责到人和批次）。
 * 重复点第二次会被后端按「预占已被其他发货计划占用」拒掉，消息原样显示。
 */
const canPlan = computed(() => statusAllowsShip.value && hasPermission('sal:pick'))

function confirmCreatePlan(): void {
  if (!order.value || actionLoading.value) return
  uni.showModal({
    title: '生成发货计划',
    content:
      '将按本单未出量与现有预占生成发货单（跨仓时按仓库优先级拆成多张），随后在手机上逐行扫码。确定生成吗？',
    success: (result) => {
      if (result.confirm) void doCreatePlan()
    },
  })
}

async function doCreatePlan(): Promise<void> {
  if (!order.value || actionLoading.value) return
  actionLoading.value = true
  actionError.value = ''

  // X-Request-Id 由 api/client.ts 注入；后端用它做计划幂等
  const result = await safe(shipmentApi.createPlan(order.value.id))
  if (result.ok) {
    const shipments = result.value.shipments ?? []
    uni.showToast({ title: `已生成 ${shipments.length} 张发货单`, icon: 'success' })
    // 单张直接进扫码页；拆仓成多张时回列表，让作业人自己挑从哪张开始
    if (shipments.length === 1) {
      setTimeout(() => {
        uni.navigateTo({ url: `/pages/shipments/detail?id=${shipments[0].id}` })
      }, 300)
    } else {
      setTimeout(() => {
        uni.navigateTo({ url: '/pages/shipments/shipments' })
      }, 300)
    }
    await load()
  } else {
    actionError.value = errorMessage(result.error)
  }

  actionLoading.value = false
}

function discountText(rate: number | undefined): string {
  if (rate === undefined || rate === null) return '折扣 -'
  if (rate === 10000) return '无折扣'
  return `${(rate / 1000).toFixed(1)} 折`
}

onLoad((options: Record<string, string> | undefined) => {
  orderId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!allowed.value) {
    loaded.value = true
    return
  }
  loadDictionary()
  load()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    await load()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page detail">
    <view v-if="!allowed" class="card notice">
      <text class="notice-title">当前账号没有出货单读权限</text>
      <text class="notice-text">本页依赖 sal:read；请联系管理员分配权限。</text>
    </view>

    <template v-else>
      <view v-if="loading && !loaded" class="card loading-block">正在读取出货单…</view>

      <view v-else-if="error && !order" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
      </view>

      <template v-else-if="order">
        <view class="card hero-card">
          <view class="row-between">
            <text class="order-no">{{ order.orderNo }}</text>
            <text class="status-tag" :class="`tone-${statusMeta(order.status).tone}`">
              {{ statusMeta(order.status).label }}
            </text>
          </view>
          <text class="status-desc">{{ statusMeta(order.status).description }}</text>

          <view class="hero-grid">
            <view class="hero-cell">
              <text class="hero-label">数量</text>
              <text class="hero-value">{{ order.totalQty }}</text>
            </view>
            <view class="hero-cell">
              <text class="hero-label">已出</text>
              <text class="hero-value">{{ shippedQty }}</text>
            </view>
            <view class="hero-cell">
              <text class="hero-label">金额</text>
              <text class="hero-value amount">{{ formatFen(order.totalAmount) }}</text>
            </view>
          </view>

          <view class="kv"><text class="kv-label">客户</text><text class="kv-value">{{ customerText(order.customerId) }}</text></view>
          <view class="kv"><text class="kv-label">出库仓库</text><text class="kv-value">{{ warehouseText(order.warehouseId) }}</text></view>
          <view class="kv"><text class="kv-label">业务日期</text><text class="kv-value">{{ formatDate(order.bizDate) }}</text></view>
          <view class="kv"><text class="kv-label">账期</text><text class="kv-value">{{ order.period || '—' }}</text></view>
          <view v-if="order.remark" class="kv"><text class="kv-label">备注</text><text class="kv-value">{{ order.remark }}</text></view>
        </view>

        <view class="section-head row-between">
          <text class="section-title">明细（{{ order.lines.length }} 行）</text>
          <text class="section-hint">点 SKU 看商品</text>
        </view>

        <view v-if="!order.lines.length" class="card empty-block">
          这张单据没有明细行。请回到 Web 端检查单据数据。
        </view>

        <view v-else class="list">
          <view v-for="line in order.lines" :key="line.id" class="card line-card" @click="openLineSku(line.skuId)">
            <view class="row-between line-head">
              <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
              <text class="line-amount">{{ formatFen(line.amount) }}</text>
            </view>
            <view class="row-between line-meta">
              <text class="meta-text">数量 {{ line.qty }} · 已出 {{ line.shippedQty ?? 0 }}</text>
              <text class="meta-text">{{ formatFen(line.unitPriceAmount) }} / 件</text>
            </view>
            <view class="row-between line-meta">
              <text class="meta-text">未出 {{ line.openQty ?? line.qty - (line.shippedQty ?? 0) }}</text>
              <text class="meta-text">{{ discountText(line.discountRate) }}</text>
            </view>
          </view>
        </view>

        <view v-if="actionError" class="inline-error">
          <text class="block-text">{{ actionError }}</text>
        </view>

        <view v-if="canShip" class="action-card card">
          <text class="action-title">现场出货</text>
          <text class="action-desc">
            出货会真实扣减库存（按 FEFO 拆批次），预占同时转为已消费，不可撤销。
          </text>
          <button
            class="btn-primary action-btn"
            hover-class="none"
            :loading="actionLoading"
            :disabled="actionLoading"
            @click="confirmShip"
          >
            {{ actionLoading ? '出货中…' : '确认出货' }}
          </button>
        </view>
        <view v-else-if="actionHint" class="card action-note">
          <text class="action-note-text">{{ actionHint }}</text>
        </view>

        <view v-if="canPlan" class="action-card card">
          <text class="action-title">发货扫码作业</text>
          <text class="action-desc">
            与上面的「确认出货」是两条并存路径：确认出货按 FEFO 整单出库、不留拣货明细；
            生成发货计划则按预占拆出发货单，仓里逐行扫码后再确认发货，可追责到人、到批次。
          </text>
          <button
            class="btn-primary action-btn plan-btn"
            hover-class="none"
            data-action="create-shipment-plan"
            :disabled="actionLoading"
            @click="confirmCreatePlan"
          >
            生成发货计划
          </button>
        </view>

        <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
      </template>
    </template>
  </view>
</template>

<style scoped>
.detail {
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

.hero-card {
  padding: 28rpx;
}

.order-no {
  flex: 1;
  min-width: 0;
  font-size: 34rpx;
  font-weight: 700;
  color: #1c2230;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 6rpx 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  font-weight: 600;
}

.tone-info {
  background: #eef1f6;
  color: #586176;
}

.tone-primary {
  background: #e8eefb;
  color: #2c4f8a;
}

.tone-success {
  background: #e9f5ef;
  color: #2f7d5b;
}

.tone-warning {
  background: #fbf1e3;
  color: #a86a1f;
}

.tone-danger {
  background: #fdf0ec;
  color: #b4462f;
}

.status-desc {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: #8a90a2;
}

.hero-grid {
  display: flex;
  margin-top: 24rpx;
  padding: 20rpx 0;
  background: #f7f9fc;
  border-radius: 16rpx;
}

.hero-cell {
  flex: 1;
  text-align: center;
}

.hero-label {
  display: block;
  font-size: 21rpx;
  color: #8a90a2;
}

.hero-value {
  display: block;
  margin-top: 4rpx;
  font-size: 36rpx;
  font-weight: 700;
  color: #1c2230;
}

.hero-value.amount {
  font-size: 30rpx;
  color: #2c4066;
}

.kv {
  display: flex;
  align-items: flex-start;
  margin-top: 14rpx;
}

.kv-label {
  width: 130rpx;
  flex-shrink: 0;
  font-size: 24rpx;
  color: #8a90a2;
}

.kv-value {
  flex: 1;
  font-size: 25rpx;
  color: #1c2230;
  line-height: 1.45;
}

.section-head {
  margin: 28rpx 4rpx 14rpx;
}

.section-hint {
  font-size: 21rpx;
  color: #a2a8b6;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.line-card {
  padding: 24rpx;
}

.line-head {
  margin-bottom: 10rpx;
}

.line-sku {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: #1c2230;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-amount {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 26rpx;
  font-weight: 700;
  color: #2c4066;
}

.line-meta {
  margin-top: 6rpx;
}

.meta-text {
  font-size: 22rpx;
  color: #8a90a2;
}

.action-card {
  margin-top: 26rpx;
  padding: 26rpx;
}

.action-title {
  display: block;
  font-size: 29rpx;
  font-weight: 700;
  color: #1c2230;
}

.action-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 23rpx;
  line-height: 1.5;
  color: #6e7585;
}

.action-btn {
  width: 100%;
  margin-top: 20rpx;
}

/* 次要动作：与「确认出货」同宽但换色，避免两个深色大按钮看起来都是主操作 */
.plan-btn {
  background: #ffffff;
  color: #2c4066;
  border: 1rpx solid #c9d5e8;
}

.action-note {
  margin-top: 26rpx;
  padding: 22rpx 24rpx;
}

.action-note-text {
  font-size: 23rpx;
  line-height: 1.5;
  color: #8a90a2;
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
  margin-top: 20rpx;
  padding: 18rpx 22rpx;
  background: #fdf1ee;
  border-radius: 14rpx;
  font-size: 23rpx;
  color: #9c3a26;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: #a2a8b6;
}
</style>
