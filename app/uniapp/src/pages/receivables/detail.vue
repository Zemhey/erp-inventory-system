<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  receiptApi,
  receivableApi,
  type CustomerReceipt,
  type Receivable,
} from '@/api/client'
import { useDictionary } from '@/composables/useDictionary'
import { useTheme } from '@/composables/useTheme'
import { hasPermission } from '@/stores/session'
import { BRAND_CONFIRM, DANGER_CONFIRM } from '@/utils/dialog'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen, fenToYuan, yuanToFen } from '@/utils/money'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 应收详情 + 收款登记（FR-SAL-07/08）。
 *
 * ★ 余额是后端给的，页面只做**自洽复述**：出货 − 退货 − 回款 是否等于 balance。
 *   等式由数据库 CHECK 守住（ReceivableService:20），页面算出来的那份不是账上的那份；
 *   所以这里显示的是「校验结论」，不是「重算结果」。不一致时把两个数都摊开，
 *   而不是静默取一个 —— 那种不一致正是账出问题的现场。
 *
 * ★ 超额核销**不在前端拦**。后端 ReceiptService 有两条独立拒绝：
 *   - 合计与收款金额不等 → SAL_RECEIPT_UNBALANCED（「核销明细合计 X 分与收款金额 Y 分不一致（必须精确相等）」）
 *   - 单笔超过余额 → SAL_RECEIPT_OVER_ALLOCATED（「核销超额：应收 N 余额 X 分，本次核销 Y 分」）
 *   页面只给一条非阻断提示，提交照常发出，消息逐字显示 —— 那是账实一致的口径来源。
 */

const { themeClass } = useTheme()

const receivableId = ref(0)
const row = ref<Receivable | null>(null)
const receipts = ref<CustomerReceipt[]>([])
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const amountYuan = ref('')
const remark = ref('')
const submitting = ref(false)
const actionError = ref('')
const lastReceiptNo = ref('')

const { loadDictionary, customerText } = useDictionary()

const canReceipt = computed(() => hasPermission('sal:receipt'))

const statusLabel = computed(() => {
  const status = row.value?.status
  if (status === 'OPEN') return '未结清'
  if (status === 'SETTLED') return '已结清'
  if (status === 'VOIDED') return '已作废'
  return status ?? '-'
})

const expectedBalance = computed(() => {
  const current = row.value
  if (!current) return null
  return current.shippedAmount - current.returnedAmount - current.receivedAmount
})

const selfConsistent = computed(() => {
  const current = row.value
  if (!current || expectedBalance.value === null) return null
  return expectedBalance.value === current.balance
})

const inputFen = computed(() => yuanToFen(amountYuan.value || '0'))

/** 非阻断提示：超余额仍然发得出去，由后端拒并给逐字消息。 */
const overBalance = computed(
  () => Boolean(row.value) && inputFen.value > 0 && inputFen.value > (row.value?.balance ?? 0),
)

function allocatedText(receipt: CustomerReceipt): string {
  return receipt.allocations
    .map((allocation) => `#${allocation.receivableId} ${formatFen(allocation.amount)}`)
    .join('，')
}

async function load(): Promise<void> {
  if (!receivableId.value) {
    error.value = '缺少应收 ID，请从应收列表进入。'
    loaded.value = true
    return
  }
  if (loading.value) return

  loading.value = true
  error.value = ''
  const result = await safe(receivableApi.get(receivableId.value))
  if (result.ok) {
    row.value = result.value
    if (!amountYuan.value && result.value.balance > 0) {
      amountYuan.value = fenToYuan(result.value.balance)
    }
    const list = await safe(
      receiptApi.list({ customerId: result.value.customerId, page: 1, size: 10 }),
    )
    if (list.ok) {
      // 只留与本应收有关的收款单：核销记录要能追到这一笔，不是整个客户
      receipts.value = list.value.items.filter((receipt) =>
        receipt.allocations.some((allocation) => allocation.receivableId === result.value?.id),
      )
    }
  } else {
    row.value = null
    error.value = errorMessage(result.error)
  }
  loaded.value = true
  loading.value = false
}

function confirmReceipt(): void {
  const current = row.value
  if (!current || submitting.value) return
  if (inputFen.value <= 0) {
    actionError.value = '收款金额必须大于 0（按元填写，提交时换算成整数分）。'
    return
  }
  uni.showModal({
    title: '登记收款',
    content: `收款 ${formatFen(inputFen.value)}，全额核销到本应收。${overBalance.value ? '注意：本次金额已超过该应收余额，后端会拒绝。' : ''}确定登记吗？`,
    confirmColor: overBalance.value ? DANGER_CONFIRM : BRAND_CONFIRM,
    success: (modal) => {
      if (modal.confirm) void doReceipt()
    },
  })
}

async function doReceipt(): Promise<void> {
  const current = row.value
  if (!current || submitting.value) return

  submitting.value = true
  actionError.value = ''
  lastReceiptNo.value = ''

  // X-Request-Id 由 api/client.ts 注入；后端用它做收款幂等（同键重放返回首次收款单）
  const result = await safe(
    receiptApi.create({
      customerId: current.customerId,
      amount: inputFen.value,
      remark: remark.value.trim() || undefined,
      allocations: [{ receivableId: current.id, amount: inputFen.value }],
    }),
  )

  if (result.ok) {
    lastReceiptNo.value = result.value.receiptNo
    uni.showToast({ title: `收款单 ${result.value.receiptNo}`, icon: 'success' })
    remark.value = ''
    await load()
  } else {
    // 后端消息已带应收 id、余额与本次核销额，逐字显示、不改写
    actionError.value = errorMessage(result.error)
  }

  submitting.value = false
}

function openSourceOrder(): void {
  const current = row.value
  if (!current) return
  uni.navigateTo({ url: `/pages/sales/detail?id=${current.salesOrderId}` })
}

onLoad((options: Record<string, string> | undefined) => {
  receivableId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
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

function onAmountChange(event: unknown): void {
  amountYuan.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function onRemarkChange(event: unknown): void {
  remark.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}
</script>

<template>
  <view class="page detail" :class="themeClass">
    <view v-if="loading && !loaded" class="card loading-block">正在读取应收…</view>

    <view v-else-if="error && !row" class="card error-block">
      <text class="block-text">{{ error }}</text>
      <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
    </view>

    <template v-else-if="row">
      <view class="card hero-card">
        <view class="row-between">
          <text class="order-no">应收 #{{ row.id }}</text>
          <text
            class="status-tag"
            :class="row.status === 'OPEN' ? 'tone-open' : row.status === 'SETTLED' ? 'tone-settled' : 'tone-void'"
          >
            {{ statusLabel }}
          </text>
        </view>
        <text class="status-desc">
          {{ row.overdue === undefined ? '逾期判定只在详情接口给出' : row.overdue ? '已逾期，请联系客户回款' : '未逾期' }}
        </text>

        <view class="hero-grid">
          <view class="hero-cell">
            <text class="hero-label">应收余额</text>
            <text class="hero-value amount">{{ formatFen(row.balance) }}</text>
          </view>
          <view class="hero-cell">
            <text class="hero-label">已回款</text>
            <text class="hero-value">{{ formatFen(row.receivedAmount) }}</text>
          </view>
        </view>

        <view class="kv">
          <text class="kv-label">客户</text>
          <text class="kv-value">{{ customerText(row.customerId) }}</text>
        </view>
        <view class="kv">
          <text class="kv-label">来源出货单</text>
          <text class="kv-value link" @click="openSourceOrder">#{{ row.salesOrderId }} ›</text>
        </view>
        <view class="kv">
          <text class="kv-label">业务日期</text>
          <text class="kv-value">{{ formatDate(row.bizDate) }}</text>
        </view>
        <view class="kv">
          <text class="kv-label">到期日</text>
          <text class="kv-value">{{ formatDate(row.dueDate) }}</text>
        </view>
      </view>

      <view class="card check-card" data-testid="balance-check">
        <text class="check-title">余额自洽</text>
        <view class="check-row">
          <text class="check-cell">出货 {{ formatFen(row.shippedAmount) }}</text>
          <text class="check-op">−</text>
          <text class="check-cell">退货 {{ formatFen(row.returnedAmount) }}</text>
          <text class="check-op">−</text>
          <text class="check-cell">回款 {{ formatFen(row.receivedAmount) }}</text>
        </view>
        <view class="check-result">
          <text class="check-line">
            等式算得 {{ expectedBalance === null ? '-' : formatFen(expectedBalance) }} ·
            后端余额 {{ formatFen(row.balance) }}
          </text>
          <text v-if="selfConsistent" class="check-ok">一致（该等式由数据库 CHECK 守住）</text>
          <text v-else-if="selfConsistent === false" class="check-bad">
            不一致：以数据库余额为准，请核对是否刚有退货 / 红冲未同步
          </text>
        </view>
      </view>

      <view v-if="canReceipt && row.status === 'OPEN'" class="card form-card">
        <text class="form-title">登记收款并核销</text>
        <text class="form-desc">
          收款金额与核销明细必须精确相等（本页把整笔收款核销到这一张应收上）。
          部分回款就填部分金额，超额会被后端拒绝并给出余额与本次核销额。
        </text>

        <view class="form-row">
          <text class="form-label">收款金额（元）</text>
          <input
            class="form-input receipt-amount-input"
            type="digit"
            :value="amountYuan"
            placeholder="例如 199.00"
            :disabled="submitting"
            @input="onAmountChange"
          />
        </view>
        <view class="form-row">
          <text class="form-label">备注</text>
          <input
            class="form-input receipt-remark-input"
            :value="remark"
            placeholder="打款方式、流水号等"
            :disabled="submitting"
            @input="onRemarkChange"
          />
        </view>

        <view class="form-computed">
          <text>换算 {{ formatFen(inputFen) }} · 应收余额 {{ formatFen(row.balance) }}</text>
          <text v-if="overBalance" class="form-warn">
            本次金额已超过余额，提交后由后端拒绝（不在前端拦截，避免两套口径）
          </text>
        </view>

        <view v-if="actionError" class="inline-error receipt-error" data-testid="receipt-error">
          <text class="block-text">{{ actionError }}</text>
        </view>

        <button
          class="btn-primary btn-receipt"
          hover-class="none"
          :loading="submitting"
          :disabled="submitting"
          @click="confirmReceipt"
        >
          {{ submitting ? '登记中…' : '确认收款' }}
        </button>

        <text v-if="lastReceiptNo" class="receipt-done">已生成收款单 {{ lastReceiptNo }}</text>
      </view>

      <view v-else-if="row.status === 'SETTLED'" class="card action-note">
        <text class="action-note-text">这张应收已结清，无需再登记收款。</text>
      </view>
      <view v-else-if="row.status === 'VOIDED'" class="card action-note">
        <text class="action-note-text">这张应收已作废，不能登记收款。</text>
      </view>
      <view v-else class="card action-note">
        <text class="action-note-text">
          当前账号没有收款权限（sal:receipt），只能查看应收与核销记录。
        </text>
      </view>

      <view class="section-head row-between">
        <text class="section-title">本张应收的收款记录（{{ receipts.length }}）</text>
      </view>

      <view v-if="!receipts.length" class="card empty-block">还没有针对这张应收的收款。</view>
      <view v-else class="list">
        <view
          v-for="receipt in receipts"
          :key="receipt.id"
          class="card receipt-card"
          :data-receipt-no="receipt.receiptNo"
        >
          <view class="row-between">
            <text class="receipt-no">{{ receipt.receiptNo }}</text>
            <text class="receipt-amount">{{ formatFen(receipt.amount) }}</text>
          </view>
          <view class="row-between receipt-meta">
            <text class="meta-text">{{ formatDate(receipt.bizDate) }}</text>
            <text class="meta-text">核销 {{ allocatedText(receipt) }}</text>
          </view>
          <text v-if="receipt.remark" class="receipt-remark">{{ receipt.remark }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.detail {
  padding-top: 20rpx;
}

.hero-card {
  padding: 28rpx;
}

.order-no {
  flex: 1;
  min-width: 0;
  font-size: 32rpx;
  font-weight: 700;
  color: var(--c-text);
}

.status-tag {
  flex-shrink: 0;
  margin-left: 14rpx;
  padding: 6rpx 18rpx;
  border-radius: 999rpx;
  font-size: 22rpx;
  font-weight: 600;
}

.tone-open {
  background: var(--c-warn-wash);
  color: var(--c-warn);
}

.tone-settled {
  background: var(--c-ok-wash);
  color: var(--c-success);
}

.tone-void {
  background: var(--c-fill-strong);
  color: var(--c-text-3);
}

.status-desc {
  display: block;
  margin-top: 12rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
}

.hero-grid {
  display: flex;
  margin-top: 22rpx;
  padding: 18rpx 0;
  background: var(--c-fill);
  border-radius: 16rpx;
}

.hero-cell {
  flex: 1;
  text-align: center;
}

.hero-label {
  display: block;
  font-size: 21rpx;
  color: var(--c-text-3);
}

.hero-value {
  display: block;
  margin-top: 8rpx;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.hero-value.amount {
  color: var(--c-warn);
}

.kv {
  display: flex;
  justify-content: space-between;
  margin-top: 14rpx;
  font-size: 24rpx;
}

.kv-label {
  color: var(--c-text-3);
}

.kv-value {
  flex: 1;
  min-width: 0;
  margin-left: 20rpx;
  text-align: right;
  color: var(--c-text);
}

.link {
  color: var(--c-brand-text);
  font-weight: 600;
}

.check-card {
  margin-top: 18rpx;
  padding: 24rpx;
}

.check-title {
  display: block;
  font-size: 25rpx;
  font-weight: 700;
  color: var(--c-text);
}

.check-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-top: 14rpx;
}

.check-cell {
  font-size: 22rpx;
  color: var(--c-text-2);
}

.check-op {
  margin: 0 10rpx;
  font-size: 22rpx;
  color: var(--c-text-4);
}

.check-result {
  margin-top: 14rpx;
  padding-top: 14rpx;
  border-top: 1rpx solid var(--c-line);
}

.check-line {
  display: block;
  font-size: 22rpx;
  color: var(--c-text-2);
}

.check-ok {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: var(--c-success);
  font-weight: 600;
}

.check-bad {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: var(--c-danger);
  font-weight: 600;
}

.form-card {
  margin-top: 22rpx;
  padding: 26rpx;
}

.form-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.form-desc {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.form-row {
  display: flex;
  align-items: center;
  margin-top: 18rpx;
}

.form-label {
  width: 200rpx;
  font-size: 24rpx;
  color: var(--c-text-2);
}

.form-input {
  flex: 1;
  min-width: 0;
  height: 74rpx;
  padding: 0 20rpx;
  background: var(--c-fill);
  border-radius: 12rpx;
  font-size: 25rpx;
  color: var(--c-text);
}

.form-computed {
  margin-top: 14rpx;
  font-size: 22rpx;
  color: var(--c-text-3);
}

.form-warn {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: var(--c-warn);
}

.btn-receipt {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  margin-top: 22rpx;
  font-size: 27rpx;
}

.receipt-done {
  display: block;
  margin-top: 14rpx;
  text-align: center;
  font-size: 22rpx;
  color: var(--c-success);
}

.inline-error {
  margin-top: 16rpx;
  padding: 20rpx 24rpx;
  background: var(--c-danger-wash);
  border-radius: 14rpx;
  font-size: 23rpx;
  color: var(--c-danger);
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.action-note {
  margin-top: 22rpx;
  padding: 24rpx;
}

.action-note-text {
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.section-head {
  margin: 28rpx 4rpx 14rpx;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.receipt-card {
  padding: 22rpx 24rpx;
}

.receipt-no {
  font-size: 26rpx;
  font-weight: 700;
  color: var(--c-text);
}

.receipt-amount {
  font-size: 25rpx;
  font-weight: 700;
  color: var(--c-success);
}

.receipt-meta {
  margin-top: 8rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-2);
}

.receipt-remark {
  display: block;
  margin-top: 8rpx;
  font-size: 21rpx;
  color: var(--c-text-3);
}
</style>
