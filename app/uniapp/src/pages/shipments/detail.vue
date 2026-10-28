<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { shipmentApi, type Shipment, type ShipmentLine } from '@/api/client'
import { IS_H5 } from '@/config'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { shipmentStatusMeta } from '@/utils/shipmentStatus'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 发货扫码作业（FR-SAL-06）—— 移动端存在的理由就是这颗扫码键。
 *
 * 作业闭环：出货单详情「生成发货计划」→ 本页逐行扫码 → 全部扫满后整单确认发货。
 *
 * ★ 扫码必须能降级。摄像头只在小程序 / App 可用，H5 浏览器与微信开发者工具里调不起来；
 *   那种环境下本页退化为「条码手录 + 提交」，而不是画一个点了没反应的按钮。
 *   两条路径打的是同一个接口 `POST /shipments/{id}/scan`，
 *   条码是否匹配由后端逐字判定（ShipmentApplicationService:197），降级不放松校验。
 *
 * 数量默认给「本行还需扫」，可改：多扫会被后端按超扫拒掉（SAL_SHIPMENT_OVER_PICK），
 * 消息原样显示，移动端不自作主张截断数量。
 */

const shipmentId = ref(0)
const shipment = ref<Shipment | null>(null)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

const actionLoading = ref(false)
const actionError = ref('')
const scanFeedback = ref('')

/** 当前聚焦的拣货行：扫码结果与手录数量都落到这一行。 */
const focusedLineId = ref<number | null>(null)
const barcodeInput = ref('')
const qtyInput = ref('')

/** 手录模式：H5 直接进；小程序/App 在调起失败后也进，并带上失败原因。 */
const manualMode = ref(IS_H5)
const manualReason = ref(IS_H5 ? 'H5 浏览器不能调起摄像头扫码，已切为条码手录。' : '')

const { loadDictionary, skus, skuText, warehouseText, warning: dictWarning } = useDictionary()

const canPick = computed(() => hasPermission('sal:pick'))
const canShip = computed(() => hasPermission('sal:ship'))

const statusMeta = computed(() => shipmentStatusMeta(shipment.value?.status))
const scannable = computed(() => statusMeta.value.scannable && canPick.value)
const confirmable = computed(() => statusMeta.value.confirmable && canShip.value)

const lines = computed<ShipmentLine[]>(() => shipment.value?.lines ?? [])

const plannedTotal = computed(() => lines.value.reduce((sum, line) => sum + line.plannedQty, 0))
const pickedTotal = computed(() => lines.value.reduce((sum, line) => sum + line.pickedQty, 0))

/**
 * 详情接口把 `id` 也镜像成 `shipmentLineId`（ShipmentController:111），
 * 但列表是领域记录、没有这个别名 —— 取扫码要用的行 id 必须两者兜底。
 */
function lineIdOf(line: ShipmentLine): number {
  return line.shipmentLineId ?? line.id
}

function remainingOf(line: ShipmentLine): number {
  return Math.max(0, line.plannedQty - line.pickedQty)
}

function barcodeOf(skuId: number): string {
  return skus.value.find((sku) => sku.id === skuId)?.barcode ?? ''
}

const focusedLine = computed(() => lines.value.find((line) => lineIdOf(line) === focusedLineId.value) ?? null)

function focusLine(line: ShipmentLine): void {
  focusedLineId.value = lineIdOf(line)
  qtyInput.value = String(remainingOf(line) || 1)
  scanFeedback.value = `已切到第 ${line.lineNo} 行`
  actionError.value = ''
}

/** 扫到的条码若唯一命中某一行，自动把焦点切过去 —— 仓里没人想先点行再扫码。 */
function autoFocusByBarcode(code: string): number | null {
  const hits = lines.value.filter((line) => barcodeOf(line.skuId) === code && code.length > 0)
  if (hits.length !== 1) return null
  focusLine(hits[0])
  return hits[0].plannedQty - hits[0].pickedQty
}

async function submitScan(overrides?: { barcode?: string; lineId?: number; qty?: number }): Promise<void> {
  const current = shipment.value
  if (!current || actionLoading.value) return

  const targetLineId =
    overrides?.lineId ?? focusedLineId.value ?? (lines.value.length ? lineIdOf(lines.value[0]) : null)
  if (!targetLineId) {
    actionError.value = '这张发货单没有拣货行，无法扫码。'
    return
  }

  const target = lines.value.find((line) => lineIdOf(line) === targetLineId)
  const fallbackQty = target ? remainingOf(target) : 0
  const qty = overrides?.qty ?? Number(qtyInput.value || fallbackQty)
  if (!Number.isInteger(qty) || qty <= 0) {
    actionError.value = `扫码数量必须是大于 0 的整数，当前是「${qtyInput.value}」。`
    return
  }

  const barcode = (overrides?.barcode ?? barcodeInput.value).trim()

  actionLoading.value = true
  actionError.value = ''
  // X-Request-Id 由 api/client.ts 注入；扫码本身后端按行累加，不做幂等键要求
  const result = await safe(shipmentApi.scan(current.id, { shipmentLineId: targetLineId, qty, barcode: barcode || undefined }))

  if (result.ok) {
    shipment.value = result.value
    scanFeedback.value = `第 ${target?.lineNo ?? '-'} 行已累加 ${qty} 件`
    qtyInput.value = ''
    barcodeInput.value = ''
    const next = result.value.lines.find((line) => remainingOf(line) > 0)
    if (next) focusLine(next)
    uni.showToast({ title: '扫码成功', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  actionLoading.value = false
}

function startScan(): void {
  if (!scannable.value || actionLoading.value) return
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
      const remaining = autoFocusByBarcode(code)
      void submitScan({ barcode: code, qty: remaining && remaining > 0 ? remaining : Number(qtyInput.value || 1) })
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
  if (!focusedLineId.value && lines.value.length) focusLine(lines.value[0])
}

function submitManual(): void {
  if (!focusedLine.value && lines.value.length) {
    focusLine(lines.value[0])
  }
  void submitScan()
}

function confirmShip(): void {
  const current = shipment.value
  if (!current || actionLoading.value) return
  if (pickedTotal.value < plannedTotal.value) {
    actionError.value = `还有 ${plannedTotal.value - pickedTotal.value} 件未扫，确认发货会被后端拒。`
    return
  }
  uni.showModal({
    title: '确认发货',
    content: '将按拣货行真实出库并登记应收，不可撤销。确定发货吗？',
    confirmColor: '#b4462f',
    success: (modal) => {
      if (modal.confirm) void doConfirm()
    },
  })
}

async function doConfirm(): Promise<void> {
  const current = shipment.value
  if (!current || actionLoading.value) return

  actionLoading.value = true
  actionError.value = ''
  const result = await safe(shipmentApi.confirm(current.id))
  if (result.ok) {
    shipment.value = result.value
    uni.showToast({ title: '发货成功', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }
  actionLoading.value = false
}

function openSalesOrder(): void {
  const current = shipment.value
  if (!current) return
  uni.navigateTo({ url: `/pages/sales/detail?id=${current.salesOrderId}` })
}

function onQtyChange(event: unknown): void {
  qtyInput.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function onBarcodeChange(event: unknown): void {
  barcodeInput.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

async function load(): Promise<void> {
  if (!shipmentId.value) {
    error.value = '缺少发货单 ID，请从列表进入。'
    loaded.value = true
    return
  }
  if (loading.value) return

  loading.value = true
  error.value = ''
  const result = await safe(shipmentApi.get(shipmentId.value))
  if (result.ok) {
    shipment.value = result.value
    if (!focusedLineId.value) {
      const first = result.value.lines.find((line) => remainingOf(line) > 0) ?? result.value.lines[0]
      if (first) {
        focusedLineId.value = lineIdOf(first)
        qtyInput.value = String(remainingOf(first) || 1)
      }
    }
  } else {
    shipment.value = null
    error.value = errorMessage(result.error)
  }
  loaded.value = true
  loading.value = false
}

onLoad((options: Record<string, string> | undefined) => {
  shipmentId.value = Number(options?.id ?? 0)
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
</script>

<template>
  <view class="page detail">
    <view v-if="loading && !loaded" class="card loading-block">正在读取发货单…</view>

    <view v-else-if="error && !shipment" class="card error-block">
      <text class="block-text">{{ error }}</text>
      <button class="btn-ghost block-btn" hover-class="none" @click="load">重试</button>
    </view>

    <template v-else-if="shipment">
      <view class="card hero-card">
        <view class="row-between">
          <text class="order-no">{{ shipment.shipmentNo }}</text>
          <text class="status-tag" :class="`tone-${statusMeta.tone}`">{{ statusMeta.label }}</text>
        </view>
        <text class="status-desc">{{ statusMeta.description }}</text>

        <view class="pick-bar">
          <view class="pick-track">
            <view
              class="pick-fill"
              :class="{ 'pick-error': pickedTotal > plannedTotal }"
              :style="{
                width: `${plannedTotal ? Math.min(100, (pickedTotal / plannedTotal) * 100) : 0}%`,
              }"
            />
          </view>
          <text class="pick-text">已扫 {{ pickedTotal }}/{{ plannedTotal }}</text>
        </view>

        <view class="kv">
          <text class="kv-label">发货仓库</text>
          <text class="kv-value">{{ warehouseText(shipment.warehouseId) }}</text>
        </view>
        <view class="kv">
          <text class="kv-label">来源出货单</text>
          <text class="kv-value link" @click="openSalesOrder">
            #{{ shipment.salesOrderId }} ›
          </text>
        </view>
        <view class="kv">
          <text class="kv-label">拣货行</text>
          <text class="kv-value">{{ lines.length }} 行</text>
        </view>
      </view>

      <view class="section-head row-between">
        <text class="section-title">扫码作业</text>
        <text class="section-hint">{{ manualMode ? '手录模式' : '摄像头模式' }}</text>
      </view>

      <view class="card scan-card" :data-mode="manualMode ? 'manual' : 'camera'">
        <text v-if="!canPick" class="scan-disabled">
          当前账号没有 sal:pick 权限，只能查看拣货进度，不能扫码。
        </text>
        <text v-else-if="!statusMeta.scannable" class="scan-disabled">
          当前状态「{{ statusMeta.label }}」不可继续扫码；
          {{ statusMeta.confirmable ? '可以确认发货。' : '只有待拣货 / 拣货中可扫码。' }}
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
            <text class="form-label">数量</text>
            <input
              class="form-input scan-qty-input"
              type="number"
              :value="qtyInput"
              placeholder="本行还需扫的数量"
              :disabled="actionLoading"
              @input="onQtyChange"
            />
          </view>

          <view class="focus-line">
            <text class="focus-label">当前行</text>
            <text class="focus-value">
              {{
                focusedLine
                  ? `第 ${focusedLine.lineNo} 行 · ${skuText(focusedLine.skuId)} · 还需 ${remainingOf(focusedLine)}`
                  : '未选择（点下面的拣货行切换）'
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
            {{ actionLoading ? '提交中…' : '提交扫码' }}
          </button>
        </template>
      </view>

      <view v-if="scanFeedback" class="feedback">{{ scanFeedback }}</view>
      <view v-if="actionError" class="inline-error scan-error" data-testid="scan-error">
        <text class="block-text">{{ actionError }}</text>
      </view>

      <view class="section-head row-between">
        <text class="section-title">拣货行（{{ lines.length }}）</text>
        <text class="section-hint">点行切换当前行</text>
      </view>

      <view v-if="!lines.length" class="card empty-block">
        这张发货单没有拣货行。请回到出货单重新生成发货计划。
      </view>

      <view v-else class="list">
        <view
          v-for="line in lines"
          :key="line.id"
          class="card line-card"
          :class="{ 'line-focused': lineIdOf(line) === focusedLineId }"
          :data-line-id="lineIdOf(line)"
          :data-barcode="barcodeOf(line.skuId) || 'none'"
          @click="focusLine(line)"
        >
          <view class="row-between line-head">
            <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
            <text class="line-remaining" :class="{ 'remaining-zero': remainingOf(line) === 0 }">
              {{ remainingOf(line) === 0 ? '已扫满' : `还差 ${remainingOf(line)}` }}
            </text>
          </view>
          <view class="row-between line-meta">
            <text class="meta-text">已扫 {{ line.pickedQty }}/{{ line.plannedQty }}</text>
            <text class="meta-text">批次 {{ line.batchNo || '-' }}</text>
          </view>
          <view class="row-between line-meta">
            <text class="meta-text">条码 {{ barcodeOf(line.skuId) || '未维护' }}</text>
            <text class="meta-text">订单行 #{{ line.salesOrderLineId }}</text>
          </view>
        </view>
      </view>

      <view v-if="confirmable" class="action-card card">
        <text class="action-title">确认发货</text>
        <text class="action-desc">
          全部行扫满后整单出库：扣减库存、消费预占、累计出货数量并登记客户应收。
          本动作不可撤销。
        </text>
        <button
          class="btn-primary action-btn"
          hover-class="none"
          :loading="actionLoading"
          :disabled="actionLoading"
          @click="confirmShip"
        >
          {{ actionLoading ? '处理中…' : '确认发货' }}
        </button>
      </view>
      <view v-else-if="canShip || canPick" class="card action-note">
        <text class="action-note-text">
          {{
            statusMeta.confirmable
              ? '已到拣货完成，等待有 sal:ship 权限的账号确认发货。'
              : `当前状态「${statusMeta.label}」不可确认发货（需全部行扫满）。`
          }}
        </text>
      </view>
      <view v-else class="card action-note">
        <text class="action-note-text">当前账号没有 sal:ship 权限，无法确认发货。</text>
      </view>

      <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
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

.status-desc {
  display: block;
  margin-top: 12rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
}

.pick-bar {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
}

.pick-track {
  flex: 1;
  height: 12rpx;
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
  font-size: 24rpx;
  font-weight: 700;
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

.section-head {
  margin: 28rpx 4rpx 14rpx;
}

.section-hint {
  font-size: 21rpx;
  color: var(--c-text-4);
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
  color: var(--c-brand-text);
}

.manual-note {
  padding: 18rpx 22rpx;
  background: var(--c-warn-wash);
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
  margin-top: 18rpx;
}

.form-label {
  width: 110rpx;
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

.focus-line {
  display: flex;
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  background: var(--c-fill);
  border-radius: 12rpx;
  font-size: 23rpx;
}

.focus-label {
  color: var(--c-text-3);
}

.focus-value {
  flex: 1;
  margin-left: 16rpx;
  color: var(--c-text);
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
  color: var(--c-text-2);
  line-height: 1.55;
}

.feedback {
  margin-top: 16rpx;
  padding: 16rpx 22rpx;
  background: #eef5f0;
  border-radius: 12rpx;
  font-size: 23rpx;
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

.list {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}

.line-card {
  padding: 22rpx 24rpx;
  border: 2rpx solid transparent;
}

.line-focused {
  border-color: #2c4066;
  background: var(--c-info-wash);
}

.line-head {
  margin-bottom: 10rpx;
}

.line-sku {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-remaining {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 23rpx;
  font-weight: 700;
  color: var(--c-warn);
}

.remaining-zero {
  color: var(--c-success);
}

.line-meta {
  margin-top: 4rpx;
}

.meta-text {
  font-size: 22rpx;
  color: var(--c-text-2);
}

.action-card {
  margin-top: 22rpx;
  padding: 26rpx;
}

.action-title {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  color: var(--c-text);
}

.action-desc {
  display: block;
  margin-top: 10rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.action-btn {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  margin-top: 22rpx;
  font-size: 27rpx;
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

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
