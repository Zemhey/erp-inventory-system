<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  invApi,
  stocktakeApi,
  type InventoryBalance,
  type Stocktake,
} from '@/api/client'
import { IS_H5 } from '@/config'
import { useDictionary } from '@/composables/useDictionary'
import { hasPermission } from '@/stores/session'
import { errorMessage } from '@/utils/error'
import { formatDate, signedQty } from '@/utils/format'
import { openInventoryLedger } from '@/utils/navigation'
import { ensureLoggedIn } from '@/utils/routeGuard'
import { safe } from '@/utils/safe'

/**
 * 扫码盘点（FR-INV-08）。
 *
 * 与「先建单再逐行填数」相反：现场先扫 SKU 条码，实盘数量按 (SKU, 批次) 归并，
 * 提交时一次性建单；`bookQty` / `diffQty` 由后端建单时快照与数据库生成列给出，
 * 页面上的「账面 / 预计差异」只是扫码时的参考。
 *
 * 条码只做 SKU 定位；批次必须能唯一定位库存行：
 * 同一 SKU 只有一条余额时自动带出批次；多条时必须手填，避免把两批库存合并盘点。
 */

const canWrite = computed(() => hasPermission('inv:write'))
const canRead = computed(() => hasPermission('inv:read'))

const { loadDictionary, warehouses, skus, skuText, warehouseText, warning: dictWarning } =
  useDictionary()

interface DraftLine {
  skuId: number
  batchNo: string
  countedQty: number
}

interface DraftLineView extends DraftLine {
  index: number
  skuName: string
  bookQty: number
  diffQty: number
  barcode: string
}

const warehouseId = ref(0)
const remark = ref('')
const balances = ref<InventoryBalance[]>([])
const balancesError = ref('')

const draftLines = ref<DraftLine[]>([])
const created = ref<Stocktake | null>(null)

const barcodeInput = ref('')
const qtyInput = ref('')
const batchInput = ref('')
const focusedKey = ref('')

const manualMode = ref(IS_H5)
const manualReason = ref(IS_H5 ? 'H5 浏览器不能调起摄像头扫码，已切为条码手录。' : '')

const initialized = ref(false)
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
const scanFeedback = ref('')
const actionError = ref('')
const submitting = ref(false)
const posting = ref(false)

const warehouseRange = computed(() =>
  warehouses.value.map((warehouse) =>
    warehouse.enabled ? warehouse.name : `${warehouse.name}（已停用）`,
  ),
)

const warehouseIndex = computed(() => {
  const index = warehouses.value.findIndex((warehouse) => warehouse.id === warehouseId.value)
  return index >= 0 ? index : 0
})

function keyOf(skuId: number, batchNo: string): string {
  return `${skuId}\u0000${batchNo}`
}

function bookQtyOf(skuId: number, batchNo: string): number {
  const row = balances.value.find(
    (balance) => balance.skuId === skuId && (balance.batchNo || '') === (batchNo || ''),
  )
  return row ? row.onHandQty : 0
}

function barcodeOf(skuId: number): string {
  return skus.value.find((sku) => sku.id === skuId)?.barcode ?? ''
}

const lineViews = computed<DraftLineView[]>(() =>
  draftLines.value.map((line, index) => {
    const bookQty = bookQtyOf(line.skuId, line.batchNo)
    return {
      ...line,
      index,
      skuName: skuText(line.skuId),
      bookQty,
      diffQty: line.countedQty - bookQty,
      barcode: barcodeOf(line.skuId),
    }
  }),
)

const totalCounted = computed(() =>
  lineViews.value.reduce((sum, line) => sum + line.countedQty, 0),
)
const totalDiffPreview = computed(() =>
  lineViews.value.reduce((sum, line) => sum + line.diffQty, 0),
)
const totalBook = computed(() => lineViews.value.reduce((sum, line) => sum + line.bookQty, 0))

const resultDiff = computed(() => created.value?.totalDiffQty ?? 0)
const resultStatus = computed(() => (created.value?.status === 'POSTED' ? '已过账' : '草稿'))
const canPost = computed(() => !!created.value && created.value.status === 'DRAFT' && canWrite.value)

function diffText(value: number): string {
  return value === 0 ? '0' : signedQty(value)
}

function uniqueBatchFor(skuId: number): string | null {
  const batches = Array.from(
    new Set(
      balances.value
        .filter((balance) => balance.skuId === skuId)
        .map((balance) => balance.batchNo || ''),
    ),
  )
  return batches.length === 1 ? batches[0] : null
}

async function loadBalances(): Promise<void> {
  if (!warehouseId.value) {
    balances.value = []
    return
  }
  if (!canRead.value) {
    balances.value = []
    balancesError.value = '当前账号没有 inv:read，账面量显示为 0（不影响提交，最终以建单快照为准）。'
    return
  }

  const result = await safe(invApi.query({ warehouseId: warehouseId.value }))
  if (result.ok) {
    balances.value = result.value
    balancesError.value = ''
  } else {
    balances.value = []
    balancesError.value = `账面量读取失败：${errorMessage(result.error)}`
  }
}

async function bootstrap(): Promise<void> {
  if (loading.value) return
  loading.value = true
  error.value = ''

  await loadDictionary()
  if (!warehouseId.value) {
    const first = warehouses.value.find((warehouse) => warehouse.enabled) ?? warehouses.value[0]
    if (first) warehouseId.value = first.id
  }
  await loadBalances()

  loaded.value = true
  loading.value = false
}

function onWarehouseChange(event: unknown): void {
  if (created.value) return
  const index = Number((event as { detail?: { value?: string | number } })?.detail?.value ?? 0)
  const next = warehouses.value[index]?.id ?? 0
  if (!next || next === warehouseId.value) return

  warehouseId.value = next
  if (draftLines.value.length) {
    draftLines.value = []
    scanFeedback.value = '已切换仓库，未提交的盘点行已清空。'
  }
  focusedKey.value = ''
  void loadBalances()
}

async function applyCode(code: string, qtyOverride?: number): Promise<void> {
  const value = code.trim()
  if (!value) {
    actionError.value = '请先输入或扫描条码。'
    return
  }
  if (!warehouseId.value) {
    actionError.value = '请先选择盘点仓库。'
    return
  }
  if (!skus.value.length) await loadDictionary()

  const sku = skus.value.find((item) => item.barcode === value)
  if (!sku) {
    actionError.value = `条码「${value}」不在主数据中，请核对标签或先到 Web 维护 SKU 条码。`
    return
  }

  let batch = batchInput.value.trim()
  if (!batch) {
    const unique = uniqueBatchFor(sku.id)
    if (unique === null) {
      actionError.value =
        `SKU「${skuText(sku.id)}」在该仓库有多个批次，请先在「批次」里填写要盘点的批次号。`
      return
    }
    batch = unique
  }
  batchInput.value = batch

  const bookQty = bookQtyOf(sku.id, batch)
  const rawQty = qtyOverride ?? Number(qtyInput.value === '' ? bookQty : qtyInput.value)
  if (!Number.isInteger(rawQty) || rawQty < 0) {
    actionError.value = `实盘数量必须是不小于 0 的整数，当前是「${qtyInput.value}」。`
    return
  }

  const key = keyOf(sku.id, batch)
  const index = draftLines.value.findIndex(
    (line) => keyOf(line.skuId, line.batchNo) === key,
  )
  if (index >= 0) {
    draftLines.value = draftLines.value.map((line, current) =>
      current === index ? { ...line, countedQty: rawQty } : line,
    )
    scanFeedback.value = `已更新 ${skuText(sku.id)} · 批次 ${batch || '无'}：实盘 ${rawQty}`
  } else {
    draftLines.value = [...draftLines.value, { skuId: sku.id, batchNo: batch, countedQty: rawQty }]
    scanFeedback.value = `已加入 ${skuText(sku.id)} · 批次 ${batch || '无'}：实盘 ${rawQty}`
  }

  focusedKey.value = key
  actionError.value = ''
  barcodeInput.value = ''
  qtyInput.value = ''
  uni.showToast({ title: '已计入盘点', icon: 'success' })
}

function startScan(): void {
  if (!canWrite.value || created.value) return
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
      void applyCode(code)
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
}

function submitManual(): void {
  void applyCode(barcodeInput.value)
}

function onBarcodeChange(event: unknown): void {
  barcodeInput.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function onQtyChange(event: unknown): void {
  qtyInput.value = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function onCountedChange(index: number, event: unknown): void {
  const raw = String((event as { detail?: { value?: unknown } })?.detail?.value ?? '').trim()
  const qty = Number(raw)
  if (!Number.isInteger(qty) || qty < 0) {
    actionError.value = `实盘数量必须是不小于 0 的整数，当前是「${raw}」。`
    return
  }
  actionError.value = ''
  draftLines.value = draftLines.value.map((line, current) =>
    current === index ? { ...line, countedQty: qty } : line,
  )
}

function removeLine(index: number): void {
  const removed = draftLines.value[index]
  draftLines.value = draftLines.value.filter((_, current) => current !== index)
  if (removed && keyOf(removed.skuId, removed.batchNo) === focusedKey.value) {
    focusedKey.value = ''
  }
}

function focusLine(line: DraftLineView): void {
  focusedKey.value = keyOf(line.skuId, line.batchNo)
  batchInput.value = line.batchNo
  actionError.value = ''
}

async function createStocktake(): Promise<void> {
  if (!warehouseId.value) {
    actionError.value = '请先选择盘点仓库。'
    return
  }
  if (!draftLines.value.length) {
    actionError.value = '还没有盘点行，请先扫码或手录条码。'
    return
  }
  if (submitting.value) return

  submitting.value = true
  actionError.value = ''

  const result = await safe(
    stocktakeApi.create({
      warehouseId: warehouseId.value,
      remark: remark.value.trim() || undefined,
      lines: draftLines.value.map((line) => ({
        skuId: line.skuId,
        batchNo: line.batchNo || undefined,
        countedQty: line.countedQty,
      })),
    }),
  )

  if (result.ok) {
    created.value = result.value
    scanFeedback.value = `盘点单 ${result.value.stocktakeNo} 已创建（账面量已快照）。`
    uni.showToast({ title: '盘点单已创建', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  submitting.value = false
}

function confirmPost(): void {
  const current = created.value
  if (!current || posting.value) return
  uni.showModal({
    title: '过账盘点差异',
    content:
      `将按差异生成调整流水，本次差异合计 ${diffText(resultDiff.value)} 件。` +
      '过账后不可撤销，确定过账吗？',
    confirmColor: '#1f3a63',
    success: (modal) => {
      if (modal.confirm) void doPost()
    },
  })
}

async function doPost(): Promise<void> {
  const current = created.value
  if (!current || posting.value) return

  posting.value = true
  actionError.value = ''
  const result = await safe(stocktakeApi.post(current.id))

  if (result.ok) {
    created.value = result.value
    scanFeedback.value = `盘点单 ${result.value.stocktakeNo} 已过账，差异流水已生成。`
    uni.showToast({ title: '已过账', icon: 'success' })
  } else {
    actionError.value = errorMessage(result.error)
  }

  posting.value = false
}

function openLedger(): void {
  if (!created.value) return
  openInventoryLedger(created.value.stocktakeNo)
}

function createAnother(): void {
  created.value = null
  draftLines.value = []
  remark.value = ''
  barcodeInput.value = ''
  qtyInput.value = ''
  batchInput.value = ''
  focusedKey.value = ''
  scanFeedback.value = ''
  actionError.value = ''
  void loadBalances()
}

function goList(): void {
  uni.redirectTo({ url: '/pages/stocktakes/stocktakes' })
}

onLoad((options: Record<string, string> | undefined) => {
  const fromQuery = Number(options?.warehouseId ?? 0)
  if (fromQuery > 0) warehouseId.value = fromQuery
})

onShow(() => {
  if (!ensureLoggedIn()) return
  if (!canWrite.value) {
    loaded.value = true
    return
  }
  if (initialized.value) return
  initialized.value = true
  void bootstrap()
})

onPullDownRefresh(() => {
  void (async () => {
    if (initialized.value) {
      await loadDictionary(true)
      await loadBalances()
    } else {
      initialized.value = true
      await bootstrap()
    }
    uni.stopPullDownRefresh()
  })()
})
</script>

<template>
  <view class="page scan-stocktake" :data-warehouse-id="warehouseId">
    <view v-if="!canWrite" class="card notice">
      <text class="notice-title">当前账号没有盘点建单权限</text>
      <text class="notice-text">本页依赖 inv:write；只能查看盘点单请回列表页。</text>
    </view>

    <template v-else>
      <view v-if="loading && !loaded" class="card loading-block">正在读取仓库与账面库存…</view>

      <view v-else-if="error" class="card error-block">
        <text class="block-text">{{ error }}</text>
        <button class="btn-ghost block-btn" hover-class="none" @click="bootstrap">重试</button>
      </view>

      <template v-else>
        <view class="card intro-card">
          <text class="intro-title">扫码盘点</text>
          <text class="intro-desc">
            扫 SKU 条码 → 填实盘数量 → 建单 → 过账。差异 = 实盘 − 账面，由后端在过账时生成调整流水。
          </text>

          <view class="form-row">
            <text class="form-label">盘点仓库</text>
            <picker
              class="picker-flex"
              :range="warehouseRange"
              :value="warehouseIndex"
              :disabled="!!created"
              @change="onWarehouseChange"
            >
              <view class="picker-value" data-testid="warehouse-picker">
                <text>{{ warehouseRange[warehouseIndex] || '请选择仓库' }}</text>
                <text class="picker-arrow">⌄</text>
              </view>
            </picker>
          </view>

          <view v-if="!created" class="form-row">
            <text class="form-label">备注</text>
            <input v-model="remark" class="form-input remark-input" placeholder="可不填，例如「月末盘点」" />
          </view>

          <view v-if="!created" class="summary row-between">
            <text class="summary-text">已扫 {{ lineViews.length }} 行 · 账面 {{ totalBook }}</text>
            <text class="summary-text">
              预计差异
              <text class="summary-strong" :class="{ 'diff-active': totalDiffPreview !== 0 }">
                {{ diffText(totalDiffPreview) }}
              </text>
            </text>
          </view>
          <text v-if="balancesError" class="balance-warning">{{ balancesError }}</text>
        </view>

        <!-- ───────────── 已建单：只读结果 + 过账 ───────────── -->
        <template v-if="created">
          <view class="card result-card stocktake-result" :data-status="created.status">
            <view class="row-between">
              <text class="result-no">{{ created.stocktakeNo }}</text>
              <text class="status-tag" :class="created.status === 'POSTED' ? 'tone-done' : 'tone-wait'">
                {{ resultStatus }}
              </text>
            </view>
            <text class="result-meta">
              {{ warehouseText(created.warehouseId) }} ·
              {{ formatDate(created.bizDate) }} · {{ created.lines.length }} 行
            </text>
            <view class="result-diff-line">
              <text class="result-diff-label">差异合计</text>
              <text class="result-diff" :class="{ 'diff-active': resultDiff !== 0 }">
                {{ diffText(resultDiff) }} 件
              </text>
            </view>

            <view class="line-table">
              <view
                v-for="line in created.lines"
                :key="`${line.skuId}-${line.batchNo}`"
                class="line-row result-line"
                :data-sku-id="line.skuId"
              >
                <text class="line-sku">{{ line.lineNo }}. {{ skuText(line.skuId) }}</text>
                <text class="line-batch">{{ line.batchNo || '无批次' }}</text>
                <text class="line-qty">账 {{ line.bookQty }} / 实 {{ line.countedQty }}</text>
                <text class="line-diff" :class="line.diffQty === 0 ? '' : 'diff-active'">
                  {{ diffText(line.diffQty) }}
                </text>
              </view>
            </view>

            <button
              v-if="canPost"
              class="btn-primary post-stocktake-btn"
              hover-class="none"
              :loading="posting"
              :disabled="posting"
              data-action="post-stocktake"
              @click="confirmPost"
            >
              {{ posting ? '过账中…' : '过账并生成差异流水' }}
            </button>
            <text v-else-if="created.status === 'POSTED'" class="posted-note">
              已过账，差异流水可核对；盘点单不能再修改。
            </text>

            <view class="result-actions">
              <button
                v-if="created.status === 'POSTED'"
                class="btn-ghost result-btn ledger-link"
                hover-class="none"
                @click="openLedger"
              >
                查看差异流水
              </button>
              <button class="btn-ghost result-btn" hover-class="none" @click="createAnother">
                再开一张
              </button>
              <button class="btn-ghost result-btn" hover-class="none" @click="goList">
                返回盘点列表
              </button>
            </view>
          </view>
        </template>

        <!-- ───────────── 扫描录入 ───────────── -->
        <template v-else>
          <view class="section-head row-between">
            <text class="section-title">扫码录入</text>
            <text class="section-hint">{{ manualMode ? '手录模式' : '摄像头模式' }}</text>
          </view>

          <view class="card scan-card" :data-mode="manualMode ? 'manual' : 'camera'">
            <button
              v-if="!manualMode"
              class="btn-primary btn-scan"
              hover-class="none"
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
                @input="onBarcodeChange"
              />
            </view>

            <view class="form-row">
              <text class="form-label">批次</text>
              <input
                v-model="batchInput"
                class="form-input batch-input"
                placeholder="该 SKU 有多批次时必填"
              />
            </view>

            <view v-if="manualMode" class="form-row">
              <text class="form-label">实盘数量</text>
              <input
                class="form-input scan-qty-input"
                type="number"
                :value="qtyInput"
                placeholder="留空则默认取账面量"
                @input="onQtyChange"
              />
            </view>

            <button
              v-if="manualMode"
              class="btn-primary btn-scan-submit"
              hover-class="none"
              @click="submitManual"
            >
              计入盘点
            </button>
          </view>

          <view v-if="scanFeedback" class="feedback">{{ scanFeedback }}</view>
          <view v-if="actionError" class="inline-error scan-error" data-testid="scan-error">
            <text class="block-text">{{ actionError }}</text>
          </view>

          <view class="section-head row-between">
            <text class="section-title">盘点行（{{ lineViews.length }}）</text>
            <text class="section-hint">重复扫同一行会覆盖实盘数</text>
          </view>

          <view v-if="!lineViews.length" class="card empty-block">
            还没有盘点行。扫描 SKU 条码或手录条码后，会在这里看到账面量。
          </view>

          <view v-else class="list">
            <view
              v-for="line in lineViews"
              :key="`${line.skuId}-${line.batchNo}`"
              class="card line-card"
              :class="{ 'line-focused': focusedKey === `${line.skuId}\u0000${line.batchNo}` }"
              :data-line-key="`${line.skuId}-${line.batchNo}`"
              @click="focusLine(line)"
            >
              <view class="row-between line-head">
                <text class="line-name">{{ line.skuName }}</text>
                <text
                  class="line-diff"
                  :class="line.diffQty === 0 ? '' : 'diff-active'"
                  :data-diff="line.diffQty"
                >
                  {{ diffText(line.diffQty) }}
                </text>
              </view>
              <view class="row-between line-meta">
                <text class="meta-text">批次 {{ line.batchNo || '无' }} · 条码 {{ line.barcode || '未维护' }}</text>
                <text class="meta-text">账面 {{ line.bookQty }}</text>
              </view>
              <view class="counted-row">
                <text class="counted-label">实盘</text>
                <input
                  class="counted-input"
                  type="number"
                  :value="line.countedQty"
                  @input="onCountedChange(line.index, $event)"
                />
                <text class="line-remove" @click.stop="removeLine(line.index)">移除</text>
              </view>
            </view>
          </view>

          <view v-if="actionError && lineViews.length" class="inline-error">
            <text class="block-text">{{ actionError }}</text>
          </view>

          <view class="action-card card">
            <text class="action-desc">
              提交后账面量由后端快照，差异以数据库生成列计算；本页只做录入与展示。
            </text>
            <button
              class="btn-primary create-stocktake-btn"
              hover-class="none"
              :loading="submitting"
              :disabled="submitting || !lineViews.length"
              data-action="create-stocktake"
              @click="createStocktake"
            >
              {{ submitting ? '提交中…' : `提交盘点单（${lineViews.length} 行）` }}
            </button>
          </view>
        </template>

        <text v-if="dictWarning" class="dict-warning">{{ dictWarning }}</text>
      </template>
    </template>
  </view>
</template>

<style scoped>
.scan-stocktake {
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

.intro-card {
  padding: 26rpx;
}

.intro-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: var(--c-text);
}

.intro-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.form-row {
  display: flex;
  align-items: center;
  min-height: 78rpx;
  margin-top: 10rpx;
  border-bottom: 1rpx solid var(--c-line);
}

.form-label {
  width: 130rpx;
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--c-text-2);
}

.form-input {
  flex: 1;
  min-width: 0;
  height: 70rpx;
  padding: 0 16rpx;
  background: var(--c-fill);
  border-radius: 12rpx;
  font-size: 25rpx;
  color: var(--c-text);
}

.picker-flex {
  flex: 1;
}

.picker-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  font-size: 26rpx;
  color: var(--c-text);
}

.picker-arrow {
  margin-left: 10rpx;
  color: var(--c-text-4);
}

.summary {
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--c-line);
}

.summary-text {
  font-size: 22rpx;
  color: var(--c-text-3);
}

.summary-strong {
  color: var(--c-brand-text);
  font-weight: 700;
}

.diff-active {
  color: var(--c-warn);
}

.balance-warning {
  display: block;
  margin-top: 12rpx;
  font-size: 21rpx;
  color: var(--c-warn);
  line-height: 1.5;
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

.btn-scan-submit {
  width: 100%;
  height: 84rpx;
  line-height: 84rpx;
  margin-top: 20rpx;
  font-size: 27rpx;
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
  margin-bottom: 8rpx;
}

.line-name {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-diff {
  flex-shrink: 0;
  margin-left: 14rpx;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--c-success);
}

.line-meta {
  margin-top: 4rpx;
}

.meta-text {
  font-size: 21rpx;
  color: var(--c-text-2);
}

.counted-row {
  display: flex;
  align-items: center;
  margin-top: 14rpx;
  padding-top: 14rpx;
  border-top: 1rpx dashed #e6eaf1;
}

.counted-label {
  width: 80rpx;
  font-size: 23rpx;
  color: var(--c-text-2);
}

.counted-input {
  flex: 1;
  height: 66rpx;
  padding: 0 16rpx;
  background: var(--c-fill);
  border-radius: 10rpx;
  font-size: 26rpx;
  color: var(--c-text);
}

.line-remove {
  flex-shrink: 0;
  margin-left: 18rpx;
  font-size: 22rpx;
  color: var(--c-danger);
}

.action-card {
  margin-top: 22rpx;
  padding: 26rpx;
}

.action-desc {
  display: block;
  font-size: 23rpx;
  color: var(--c-text-2);
  line-height: 1.55;
}

.create-stocktake-btn {
  width: 100%;
  height: 84rpx;
  margin-top: 18rpx;
  font-size: 27rpx;
}

.result-card {
  margin-top: 22rpx;
  padding: 26rpx;
}

.result-no {
  flex: 1;
  min-width: 0;
  font-size: 30rpx;
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

.result-meta {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: var(--c-text-3);
}

.result-diff-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 18rpx;
  padding: 16rpx 20rpx;
  background: var(--c-fill);
  border-radius: 14rpx;
}

.result-diff-label {
  font-size: 23rpx;
  color: var(--c-text-2);
}

.result-diff {
  font-size: 34rpx;
  font-weight: 700;
  color: var(--c-success);
}

.line-table {
  margin-top: 16rpx;
  padding: 12rpx 18rpx;
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
  width: 140rpx;
  flex-shrink: 0;
  margin-left: 10rpx;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-qty {
  width: 180rpx;
  flex-shrink: 0;
  text-align: right;
}

.post-stocktake-btn {
  width: 100%;
  height: 84rpx;
  margin-top: 20rpx;
  font-size: 27rpx;
}

.posted-note {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: var(--c-success);
}

.result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 18rpx;
}

.result-btn {
  flex: 1;
  min-width: 180rpx;
  height: 72rpx;
  font-size: 24rpx;
}

.ledger-link {
  color: var(--c-brand-text);
}

.dict-warning {
  display: block;
  margin-top: 22rpx;
  text-align: center;
  font-size: 21rpx;
  color: var(--c-text-4);
}
</style>
