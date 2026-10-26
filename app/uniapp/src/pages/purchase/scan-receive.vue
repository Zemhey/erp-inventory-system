<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { purApi, type OrderLine, type PurchaseOrder, type PurchaseReceipt } from '@/api/client'
import { IS_H5 } from '@/config'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { canExecuteAction, statusMeta } from '@/utils/documentStatus'
import { errorMessage } from '@/utils/error'
import { formatDate } from '@/utils/format'
import { formatFen } from '@/utils/money'
import { openInventory } from '@/utils/navigation'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 扫码收货（FR-PUR-05 / 移动端 PDA）。
 *
 * 作业方式与「进货单详情 → 确认入库」不同：这里按 SKU 条码逐条累计，
 * 最后一次性调用同一个 `POST /purchase-orders/{id}/receive`。
 * 扫码只做**定位行**，数量与状态仍由后端判定：
 * - 条码逐字比对（条码不属于本单 / 同码多行时要求点行，不猜）；
 * - 本次累计量超过未入量时前端先提示，后端仍会再拒一次。
 *
 * 摄像头只在小程序 / App 可用；H5 与调起失败一律落入手录，打的是同一个接口。
 */

const canReceive = computed(() => hasPermission('pur:receive'))
const canRead = computed(() => hasPermission('pur:read'))

const { loadDictionary, skus, skuText, supplierText, warehouseText, warning: dictWarning } =
  useDictionary()

const orderId = ref(0)
const order = ref<PurchaseOrder | null>(null)
const candidates = ref<PurchaseOrder[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')

const actionLoading = ref(false)
const actionError = ref('')
const scanFeedback = ref('')

/** 本次作业的累计量：lineId → 已扫件数。提交前只存在本地。 */
const scanned = ref<Record<number, number>>({})
const focusedLineId = ref<number | null>(null)
const barcodeInput = ref('')
const qtyInput = ref('')
const batchNo = ref('')

const manualMode = ref(IS_H5)
const manualReason = ref(IS_H5 ? 'H5 浏览器不能调起摄像头扫码，已切为条码手录。' : '')

const receipts = ref<PurchaseReceipt[]>([])
const receiptsError = ref('')

const lines = computed<OrderLine[]>(() => order.value?.lines ?? [])
const openLines = computed(() => lines.value.filter((line) => lineOpen(line) > 0))

const statusInfo = computed(() => statusMeta(order.value?.status))
const receivable = computed(
  () =>
    !!order.value &&
    canExecuteAction('PURCHASE', order.value.status, 'receive', hasPermission),
)

function lineOpen(line: OrderLine): number {
  return line.openQty ?? line.qty - (line.receivedQty ?? 0)
}

function scannedOf(line: OrderLine): number {
  return scanned.value[line.id] ?? 0
}

function remainingOf(line: OrderLine): number {
  return Math.max(0, lineOpen(line) - scannedOf(line))
}

const totalScanned = computed(() => openLines.value.reduce((sum, line) => sum + scannedOf(line), 0))
const totalOpen = computed(() => openLines.value.reduce((sum, line) => sum + lineOpen(line), 0))

const focusedLine = computed(
  () => lines.value.find((line) => line.id === focusedLineId.value) ?? null,
)

function barcodeOf(skuId: number): string {
  return skus.value.find((sku) => sku.id === skuId)?.barcode ?? ''
}

function focusLine(line: OrderLine): void {
  focusedLineId.value = line.id
  qtyInput.value = String(remainingOf(line) || 1)
  actionError.value = ''
}

/** 条码 → 单据行；聚焦行优先，其次全单唯一命中。同码多行不猜。 */
function resolveLine(code: string): OrderLine | null {
  const focused = focusedLine.value
  if (focused && barcodeOf(focused.skuId) === code) return focused

  const hits = lines.value.filter((line) => barcodeOf(line.skuId) === code)
  if (!hits.length) {
    actionError.value = `条码「${code}」不属于这张进货单，请核对标签或点选单据行后再扫。`
    return null
  }
  const openHits = hits.filter((line) => lineOpen(line) > 0)
  if (!openHits.length) {
    actionError.value = `条码「${code}」对应的单据行已全部入库，无需再扫。`
    return null
  }
  if (openHits.length > 1) {
    actionError.value = `条码「${code}」命中 ${openHits.length} 行，请点选要入库的行后再扫。`
    return null
  }
  focusLine(openHits[0])
  return openHits[0]
}

async function applyScan(code: string, qtyOverride?: number): Promise<void> {
  const value = code.trim()
  if (!value) {
    actionError.value = '请先输入或扫描条码。'
    return
  }
  if (!order.value) return

  if (!skus.value.length) {
    await loadDictionary()
  }

  // 条码可能把焦点切到另一行，而 focusLine 会重置数量输入框；
  // 先记住用户本次输入，避免「扫第二行时输入的数量被默认值覆盖」。
  const typedQty = qtyInput.value.trim()
  const line = resolveLine(value)
  if (!line) return
  if (remainingOf(line) <= 0) {
    actionError.value = `第 ${line.lineNo} 行已扫满未入量，不能继续累加。`
    return
  }

  const qty = qtyOverride ?? (typedQty === '' ? remainingOf(line) : Number(typedQty))
  if (!Number.isInteger(qty) || qty <= 0) {
    actionError.value = `入库数量必须是大于 0 的整数，当前是「${typedQty}」。`
    return
  }
  if (qty > remainingOf(line)) {
    actionError.value =
      `第 ${line.lineNo} 行未入 ${lineOpen(line)} 件，本次最多还能扫 ${remainingOf(line)} 件。`
    return
  }

  scanned.value = { ...scanned.value, [line.id]: scannedOf(line) + qty }
  actionError.value = ''
  scanFeedback.value = `第 ${line.lineNo} 行已累计 ${scannedOf(line)}/${lineOpen(line)} 件`
  barcodeInput.value = ''
  qtyInput.value = ''
  uni.showToast({ title: '已计入本次入库', icon: 'success' })
}

function startScan(): void {
  if (!receivable.value || actionLoading.value) return
  // #ifdef H5
  enterManual('H5 浏览器不能调起摄像头扫码，已切为条码手录。')
  // #endif
  // #ifndef H5
  uni.scanCode({
    scanType: ['barCode', 'qrCode'],
    success: (event) => {
      const code = String((event as unknown as { result?: string }).result ?? '').trim()
      if (!code) {
        enterManual('扫码结果为空，请改用手录。')
        return
      }
      barcodeInput.value = code
      const line = resolveLine(code)
      void applyScan(code, line ? remainingOf(line) || 1 : 1)
    },
    fail: (event) => {
      const detail = String((event as unknown as { errMsg?: string }).errMsg ?? '未知原因')
      enterManual(`未能调起摄像头（${detail}），已切为条码手录。`)
    },
  })
  // #endif
}

function enterManual(reason: string): void {
  manualMode.value = true
  manualReason.value = reason
  if (!focusedLineId.value && order.value?.lines.length) {
    focusLine(order.value.lines[0])
  }
}

function submitManual(): void {
  if (!focusedLineId.value && order.value?.lines.length) {
    focusLine(order.value.lines[0])
  }
  void applyScan(barcodeInput.value)
}

function onQtyChange(event: unknown): void {
  qtyInput.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function onBarcodeChange(event: unknown): void {
  barcodeInput.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function resetScan(): void {
  scanned.value = {}
  scanFeedback.value = ''
  actionError.value = ''
  const next = openLines.value[0]
  if (next) focusLine(next)
}

function confirmReceive(): void {
  const current = order.value
  if (!current || actionLoading.value) return
  const pending = openLines.value.filter((line) => scannedOf(line) > 0)
  if (!pending.length) {
    actionError.value = '还没有扫入任何数量，请先扫码或手录条码。'
    return
  }

  uni.showModal({
    title: '确认入库',
    content: `本次将入库 ${totalScanned.value} 件（${pending.length} 行），写入库存流水，不可撤销。确定入库吗？`,
    confirmColor: '#1f3a63',
    success: (modal) => {
      if (modal.confirm) void doReceive()
    },
  })
}

async function doReceive(): Promise<void> {
  const current = order.value
  if (!current || actionLoading.value) return

  actionLoading.value = true
  actionError.value = ''
  const result = await safe(
    purApi.receive(current.id, {
      lines: openLines.value
        .filter((line) => scannedOf(line) > 0)
        .map((line) => ({
          lineId: line.id,
          qty: scannedOf(line),
          batchNo: batchNo.value.trim() || undefined,
        })),
    }),
  )

  if (result.ok) {
    order.value = result.value
    scanned.value = {}
    qtyInput.value = ''
    const stillOpen = result.value.lines.some(
      (line) => (line.openQty ?? line.qty - (line.receivedQty ?? 0)) > 0,
    )
    scanFeedback.value = stillOpen
      ? '本次入库成功，单据还有未入数量，可继续扫码。'
      : '入库完成，单据已全部入库。'
    const next = result.value.lines.find(
      (line) => (line.openQty ?? line.qty - (line.receivedQty ?? 0)) > 0,
    )
    if (next) focusLine(next)
    uni.showToast({ title: stillOpen ? '部分入库成功' : '入库成功', icon: 'success' })
    void loadReceipts()
  } else {
    actionError.value = errorMessage(result.error)
  }

  actionLoading.value = false
}

async function loadReceipts(): Promise<void> {
  if (!orderId.value) return
  receiptsError.value = ''
  const result = await safe(purApi.receipts(orderId.value))
  if (result.ok) {
    receipts.value = result.value
  } else {
    receipts.value = []
    receiptsError.value = errorMessage(result.error)
  }
}

async function loadCandidates(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''

  const [approved, partial] = await Promise.all([
    safe(purApi.list({ status: 'APPROVED', page: 1, size: 50 })),
    safe(purApi.list({ status: 'PARTIAL', page: 1, size: 50 })),
  ])

  if (approved.ok || partial.ok) {
    const merged = [
      ...(approved.ok ? approved.value.items : []),
      ...(partial.ok ? partial.value.items : []),
    ]
    const seen = new Set<number>()
    candidates.value = merged.filter((item) => {
      if (seen.has(item.id)) return false
      seen.add(item.id)
      return true
    })
  } else {
    candidates.value = []
    error.value = errorMessage(approved.ok ? partial : approved)
  }

  loaded.value = true
  loading.value = false
}

async function loadOrder(): Promise<void> {
  if (!orderId.value) {
    await loadCandidates()
    return
  }
  if (loading.value) return
  loading.value = true
  error.value = ''

  const [detail] = await Promise.all([
    safe(purApi.get(orderId.value)),
    loadDictionary(),
    loadReceipts(),
  ])
  if (detail.ok) {
    order.value = detail.value
    const first = detail.value.lines.find((line) => lineOpen(line) > 0)
    if (first) focusLine(first)
  } else {
    order.value = null
    error.value = errorMessage(detail.error)
  }

  loaded.value = true
  loading.value = false
}

function selectOrder(candidate: PurchaseOrder): void {
  orderId.value = candidate.id
  order.value = null
  loaded.value = false
  void loadOrder()
}

function backToCandidates(): void {
  orderId.value = 0
  order.value = null
  scanned.value = {}
  loaded.value = false
  void loadCandidates()
}

function openLineSku(skuId: number): void {
  uni.navigateTo({ url: `/pages/products/sku-detail?id=${skuId}` })
}

onLoad((options: Record<string, string> | undefined) => {
  orderId.value = Number(options?.id ?? 0)
})

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!canReceive.value) {
    loaded.value = true
    return
  }
  loadDictionary()
  void loadOrder()
})

onPullDownRefresh(() => {
  void (async () => {
    await loadDictionary(true)
    await loadOrder()
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page scan-receive">
    <view v-if="!canReceive" class="card notice">
      <text class="notice-title">当前账号没有收货权限</text>
      <text class="notice-text">
        本页需要 pur:receive。可先到进货单详情查看单据；权限由管理员分配，后端仍会做最终校验。
      </text>
    </view>

    <template v-else>
      <view v-if="loading && !loaded" class="card loading-block">
        {{ orderId ? '正在读取进货单…' : '正在读取待收货进货单…' }}
      </view>

      <view v-else-if="error && !order && !candidates.length" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="orderId ? loadOrder() : loadCandidates()">
          重试
        </button>
      </view>

      <!-- ───────────── 无 id：先选单据（工作台入口） ───────────── -->
      <template v-else-if="!orderId">
        <view class="section-head row-between">
          <text class="section-title">待收货进货单（{{ candidates.length }}）</text>
          <text class="section-hint">点单据开始扫码</text>
        </view>

        <view v-if="!candidates.length" class="card empty-block">
          没有待收货的进货单。已审核 / 部分入库的进货单会出现在这里。
        </view>

        <view v-else class="list">
          <view
            v-for="candidate in candidates"
            :key="candidate.id"
            class="card order-card"
            :data-order-no="candidate.orderNo"
            @click="selectOrder(candidate)"
          >
            <view class="row-between">
              <text class="order-no">{{ candidate.orderNo }}</text>
              <text class="status-tag" :class="`tone-${statusMeta(candidate.status).tone}`">
                {{ statusMeta(candidate.status).label }}
              </text>
            </view>
            <view class="row-between order-meta">
              <text class="meta-text">{{ supplierText(candidate.supplierId) }}</text>
              <text class="meta-text">{{ warehouseText(candidate.warehouseId) }}</text>
            </view>
            <view class="row-between order-meta">
              <text class="meta-text">数量 {{ candidate.totalQty }}</text>
              <text class="meta-text amount">{{ formatFen(candidate.totalAmount) }}</text>
            </view>
          </view>
        </view>
        <text v-if="error" class="inline-error block-text">{{ error }}</text>
      </template>

      <!-- ───────────── 有 id：扫码作业 ───────────── -->
      <template v-else-if="order">
        <view class="card hero-card">
          <view class="row-between">
            <text class="order-no">{{ order.orderNo }}</text>
            <text class="status-tag" :class="`tone-${statusInfo.tone}`">{{ statusInfo.label }}</text>
          </view>
          <text class="status-desc">{{ statusInfo.description }}</text>

          <view class="hero-grid">
            <view class="hero-cell">
              <text class="hero-label">单据数量</text>
              <text class="hero-value">{{ order.totalQty }}</text>
            </view>
            <view class="hero-cell">
              <text class="hero-label">未入</text>
              <text class="hero-value open">{{ totalOpen }}</text>
            </view>
            <view class="hero-cell">
              <text class="hero-label">金额</text>
              <text class="hero-value amount">{{ formatFen(order.totalAmount) }}</text>
            </view>
          </view>

          <view class="kv"><text class="kv-label">供应商</text><text class="kv-value">{{ supplierText(order.supplierId) }}</text></view>
          <view class="kv"><text class="kv-label">入库仓库</text><text class="kv-value">{{ warehouseText(order.warehouseId) }}</text></view>
          <view class="kv"><text class="kv-label">业务日期</text><text class="kv-value">{{ formatDate(order.bizDate) }}</text></view>
          <text class="back-link" @click="backToCandidates">‹ 重新选择进货单</text>
        </view>

        <view class="section-head row-between">
          <text class="section-title">扫码作业</text>
          <text class="section-hint">{{ manualMode ? '手录模式' : '摄像头模式' }}</text>
        </view>

        <view class="card scan-card" :data-mode="manualMode ? 'manual' : 'camera'">
          <text v-if="!receivable" class="scan-disabled">
            当前状态「{{ statusInfo.label }}」不可收货；只有已审核 / 部分入库的进货单能扫码入库。
          </text>

          <template v-else>
            <button
              v-if="!manualMode"
              class="btn-primary btn-scan"
              hover-class="none"
              :disabled="actionLoading"
              @click="startScan"
            >
              扫码
            </button>
            <text v-if="!manualMode" class="scan-switch" @click="enterManual('已手动切换为条码手录。')">
              摄像头不好用？改用手录条码 ›
            </text>

            <view v-if="manualMode" class="manual-note">
              <text class="manual-note-text">{{ manualReason }}</text>
            </view>

            <view v-if="manualMode" class="form-row">
              <text class="form-label">条码</text>
              <input
                class="form-input scan-barcode-input"
                :value="barcodeInput"
                placeholder="扫码枪可直接打入，也可手输"
                :disabled="actionLoading"
                @input="onBarcodeChange"
              />
            </view>

            <view v-if="manualMode" class="form-row">
              <text class="form-label">本次数量</text>
              <input
                class="form-input scan-qty-input"
                type="number"
                :value="qtyInput"
                placeholder="本行本次要入库的数量"
                :disabled="actionLoading"
                @input="onQtyChange"
              />
            </view>

            <view class="form-row">
              <text class="form-label">统一批次</text>
              <input
                v-model="batchNo"
                class="form-input batch-input"
                placeholder="可不填；填写后本次各行使用同一批次"
                :disabled="actionLoading"
              />
            </view>

            <view class="focus-line">
              <text class="focus-label">当前行</text>
              <text class="focus-value">
                {{
                  focusedLine
                    ? `第 ${focusedLine.lineNo} 行 · ${skuText(focusedLine.skuId)} · 未入 ${lineOpen(focusedLine)}，本次已扫 ${scannedOf(focusedLine)}`
                    : '未选择（点下面的单据行切换）'
                }}
              </text>
            </view>

            <button
              v-if="manualMode"
              class="btn-primary btn-scan-submit"
              hover-class="none"
              :disabled="actionLoading || !focusedLine"
              @click="submitManual"
            >
              计入本次入库
            </button>
          </template>
        </view>

        <view v-if="scanFeedback" class="feedback">{{ scanFeedback }}</view>
        <view v-if="actionError" class="inline-error scan-error" data-testid="scan-error">
          <text class="block-text">{{ actionError }}</text>
        </view>

        <view class="section-head row-between">
          <text class="section-title">单据行（{{ lines.length }}）</text>
          <text class="section-hint">点行切换当前行</text>
        </view>

        <view v-if="!lines.length" class="card empty-block">这张进货单没有明细行。</view>

        <view v-else class="list">
          <view
            v-for="line in lines"
            :key="line.id"
            class="card line-card"
            :class="{ 'line-focused': line.id === focusedLineId, 'line-done': lineOpen(line) === 0 }"
            :data-line-id="line.id"
            :data-barcode="barcodeOf(line.skuId) || 'none'"
            @click="focusLine(line)"
          >
            <view class="row-between line-head">
              <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
              <text class="line-remaining" :class="{ 'remaining-zero': lineOpen(line) === 0 }">
                {{ lineOpen(line) === 0 ? '已入完' : `未入 ${lineOpen(line)}` }}
              </text>
            </view>
            <view class="row-between line-meta">
              <text class="meta-text">
                已入 {{ line.receivedQty ?? 0 }}/{{ line.qty }} · 本次 {{ scannedOf(line) }}
              </text>
              <text class="meta-text">{{ formatFen(line.unitPriceAmount) }} / 件</text>
            </view>
            <view class="row-between line-meta">
              <text class="meta-text">条码 {{ barcodeOf(line.skuId) || '未维护' }}</text>
              <text class="meta-text" @click.stop="openLineSku(line.skuId)">看 SKU ›</text>
            </view>
          </view>
        </view>

        <view v-if="receivable" class="action-card card">
          <text class="action-title">提交本次入库</text>
          <text class="action-desc">
            本次已扫 {{ totalScanned }} 件；提交后写入库存流水并累加已入库数量，不可撤销。
          </text>
          <view class="action-row">
            <button class="btn-ghost action-reset" hover-class="none" :disabled="actionLoading" @click="resetScan">
              清空重扫
            </button>
            <button
              class="btn-primary btn-receive"
              hover-class="none"
              :loading="actionLoading"
              :disabled="actionLoading || totalScanned === 0"
              @click="confirmReceive"
            >
              {{ actionLoading ? '入库中…' : `确认入库（${totalScanned}）` }}
            </button>
          </view>
        </view>
        <view v-else class="card action-note">
          <text class="action-note-text">
            当前状态「{{ statusInfo.label }}」不可入库；审核与作废请使用 Web 端。
          </text>
        </view>

        <view v-if="scanFeedback" class="card success-card">
          <text class="success-title">{{ scanFeedback }}</text>
          <text class="success-link" @click="openInventory('balance')">去库存余额核对 ›</text>
        </view>

        <template v-if="receipts.length">
          <view class="section-head row-between">
            <text class="section-title">到货记录（{{ receipts.length }}）</text>
            <text class="section-hint">FR-PUR-05</text>
          </view>
          <view class="list">
            <view v-for="receipt in receipts" :key="receipt.id" class="card receipt-card">
              <view class="row-between">
                <text class="receipt-no">{{ receipt.receiptNo }}</text>
                <text class="meta-text">{{ formatDate(receipt.bizDate) }}</text>
              </view>
              <view class="row-between line-meta">
                <text class="meta-text">到货 {{ receipt.totalQty }} · 合格 {{ receipt.acceptedQty }}</text>
                <text class="meta-text amount">{{ formatFen(receipt.acceptedAmount) }}</text>
              </view>
            </view>
          </view>
        </template>
        <text v-if="receiptsError" class="inline-error block-text">{{ receiptsError }}</text>

        <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
      </template>
    </template>
  </view>
</template>

<style scoped>
.scan-receive {
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
  font-size: 32rpx;
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
  margin-top: 22rpx;
  padding: 18rpx 0;
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
  font-size: 34rpx;
  font-weight: 700;
  color: #1c2230;
}

.hero-value.open {
  color: #b4702f;
}

.hero-value.amount {
  font-size: 28rpx;
  color: #2c4066;
}

.kv {
  display: flex;
  margin-top: 12rpx;
  font-size: 24rpx;
}

.kv-label {
  width: 130rpx;
  flex-shrink: 0;
  color: #8a90a2;
}

.kv-value {
  flex: 1;
  color: #1c2230;
}

.back-link {
  display: block;
  margin-top: 18rpx;
  font-size: 23rpx;
  color: #2c4066;
}

.section-head {
  margin: 28rpx 4rpx 14rpx;
}

.section-hint {
  font-size: 21rpx;
  color: #a2a8b6;
}

.scan-card {
  padding: 26rpx;
}

.btn-scan {
  width: 100%;
  height: 96rpx;
  line-height: 96rpx;
  font-size: 30rpx;
  letter-spacing: 4rpx;
}

.scan-switch {
  display: block;
  margin-top: 16rpx;
  text-align: center;
  font-size: 23rpx;
  color: #2c4066;
}

.manual-note {
  padding: 18rpx 22rpx;
  background: #fbf4e8;
  border-radius: 14rpx;
}

.manual-note-text {
  font-size: 23rpx;
  color: #8a5a1b;
  line-height: 1.5;
}

.form-row {
  display: flex;
  align-items: center;
  min-height: 78rpx;
  margin-top: 12rpx;
  border-bottom: 1rpx solid #f0f2f6;
}

.form-label {
  width: 140rpx;
  flex-shrink: 0;
  font-size: 24rpx;
  color: #586176;
}

.form-input {
  flex: 1;
  min-width: 0;
  height: 70rpx;
  padding: 0 16rpx;
  background: #f4f6fa;
  border-radius: 12rpx;
  font-size: 25rpx;
  color: #2b3245;
}

.focus-line {
  display: flex;
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  background: #f7f9fc;
  border-radius: 12rpx;
  font-size: 23rpx;
}

.focus-label {
  flex-shrink: 0;
  color: #8a90a2;
}

.focus-value {
  flex: 1;
  margin-left: 16rpx;
  color: #2b3245;
}

.btn-scan-submit {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  margin-top: 20rpx;
  font-size: 27rpx;
}

.scan-disabled {
  display: block;
  font-size: 24rpx;
  color: #6e7585;
  line-height: 1.55;
}

.feedback {
  margin-top: 16rpx;
  padding: 16rpx 22rpx;
  background: #eef5f0;
  border-radius: 12rpx;
  font-size: 23rpx;
  color: #2f7d5b;
}

.inline-error {
  margin-top: 16rpx;
  padding: 20rpx 24rpx;
  background: #fdf1ee;
  border-radius: 14rpx;
  font-size: 23rpx;
  color: #9c3a26;
}

.block-text {
  display: block;
  line-height: 1.5;
}

.block-btn {
  width: 240rpx;
  margin: 24rpx auto 0;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.order-card {
  padding: 24rpx;
}

.order-meta {
  margin-top: 8rpx;
}

.meta-text {
  font-size: 22rpx;
  color: #6e7585;
}

.amount {
  color: #2c4066;
  font-weight: 600;
}

.line-card {
  padding: 22rpx 24rpx;
  border: 2rpx solid transparent;
}

.line-focused {
  border-color: #2c4066;
  background: #f6f9ff;
}

.line-done {
  opacity: 0.72;
}

.line-head {
  margin-bottom: 8rpx;
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

.line-remaining {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 23rpx;
  font-weight: 700;
  color: #b4702f;
}

.remaining-zero {
  color: #2f7d5b;
}

.line-meta {
  margin-top: 4rpx;
}

.action-card {
  margin-top: 24rpx;
  padding: 26rpx;
}

.action-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: #1c2230;
}

.action-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 23rpx;
  color: #6e7585;
  line-height: 1.55;
}

.action-row {
  display: flex;
  gap: 18rpx;
  margin-top: 20rpx;
}

.action-reset {
  width: 220rpx;
  height: 84rpx;
}

.btn-receive {
  flex: 1;
  height: 84rpx;
  font-size: 27rpx;
}

.action-note {
  margin-top: 22rpx;
  padding: 24rpx;
}

.action-note-text {
  font-size: 23rpx;
  color: #6e7585;
  line-height: 1.55;
}

.success-card {
  margin-top: 20rpx;
  padding: 24rpx;
  border-left: 8rpx solid #2f7d5b;
}

.success-title {
  display: block;
  font-size: 24rpx;
  color: #2f7d5b;
}

.success-link {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  font-weight: 600;
  color: #2c4066;
}

.receipt-card {
  padding: 22rpx 24rpx;
}

.receipt-no {
  font-size: 25rpx;
  font-weight: 600;
  color: #1c2230;
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: #a2a8b6;
}
</style>
