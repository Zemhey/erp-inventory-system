<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { nlApi, type NlCommandResult, type NlPlanResult, type NlReview } from '@/api/client'
import { errorMessage } from '@/utils/error'
import { intentLabel, verdictLabel } from '@/utils/nl'
import { ensureLoggedIn } from '@/utils/routeGuard'

/**
 * 自然语言操作页（FR-SYS-04）。
 *
 * ★ 调用链与 Web 操作台同一条：先 `/nl/plan` 出计划 + 复核意见 + 确认凭据，
 *   写意图停在计划卡上等人点确认，只读意图直接执行。
 * ★ 为什么不用 `/nl/pending/{id}/execute`：
 *   那是"待确认队列"通道，需要 sys:read 且依赖一条已落库的档案记录 id；
 *   操作台这种随问随答的场景用的是 plan/command + 回执令牌，绕开页面也绕不开服务端闸门。
 * ★ 错误一律内联展示：后端返回的是中文 message，直接给用户看，不白屏、不只弹 toast code。
 */

const examples = ['查询库存', '补货建议', '进货单列表 第1页', '账期 2026-10 进销存汇总', '查询商品 关键字 T恤']

const command = ref('')
const loading = ref(false)
const error = ref('')

const result = ref<NlCommandResult | null>(null)
const planResult = ref<NlPlanResult | null>(null)
const reviewResult = ref<NlReview | null>(null)
const planToken = ref('')
const pendingCommand = ref('')

const hasPlan = computed(() => planResult.value !== null)
const needsConfirm = computed(() => planResult.value?.requiresReview ?? false)
const planExecutable = computed(() => planResult.value?.executable ?? false)
const reviewBlocking = computed(() => reviewResult.value?.blocking ?? false)

/** 结论文字：执行结果优先，否则用计划的摘要（写意图还没执行时也能先看到"将要发生什么"）。 */
const conclusion = computed(() => {
  if (result.value?.description) return result.value.description
  if (planResult.value?.summary) return planResult.value.summary
  return ''
})

/** 结果里的参数原样转 JSON，供查看；字段名是后端契约，不在这里逐项美化。 */
const resultJson = computed(() => (result.value ? JSON.stringify(result.value.data, null, 2) : ''))

/**
 * 结果的"列表形态"。
 *
 * ★ 为什么不再直接把 JSON 铺在页面上：查询类意图返回的是 {items:[...], total}，
 *   2100 行库存原样展开在 390px 宽的屏幕上，一屏看不到结论，只能看到引号和逗号。
 *   手机端要的是"结论 + 几条样本 + 想看细节再展开"，不是把调试窗口搬到手上。
 */
const resultItems = computed<Record<string, unknown>[]>(() => {
  const data = result.value?.data as { items?: unknown } | undefined
  return data && Array.isArray(data.items) ? (data.items as Record<string, unknown>[]) : []
})

const resultTotal = computed(() => {
  const data = result.value?.data as { total?: number } | undefined
  return typeof data?.total === 'number' ? data.total : resultItems.value.length
})

/** 原始返回默认收起：它不是给操作员看的第一眼内容，但排查问题时要拿得到。 */
const rawOpen = ref(false)

/**
 * 一行只给最有辨识度的字段。
 * 字段名来自各意图自己的返回结构，这里用一张小映射兜住常见的几种，
 * 认不出来的行就退回"前三个原始值"，宁可粗糙也不要空着。
 */
const ROW_LABELS: Record<string, string> = {
  no: '单号',
  bizRefNo: '单号',
  productName: '品名',
  skuName: '品名',
  warehouseName: '仓库',
  batchNo: '批次',
  onHandQty: '实存',
  availableQty: '可用',
  totalQty: '数量',
  totalAmount: '金额',
  amount: '金额',
  status: '状态',
  bizDate: '业务日',
}

function rowLine(row: Record<string, unknown>): string {
  const parts: string[] = []
  for (const [key, label] of Object.entries(ROW_LABELS)) {
    const value = row[key]
    if (value === undefined || value === null || value === '') continue
    parts.push(`${label} ${String(value)}`)
    if (parts.length >= 3) break
  }
  if (parts.length) return parts.join(' · ')
  return Object.values(row)
    .filter((v) => v !== null && v !== undefined && typeof v !== 'object')
    .slice(0, 3)
    .map(String)
    .join(' · ')
}

function useExample(text: string): void {
  command.value = text
}

async function run(): Promise<void> {
  const text = command.value.trim()
  if (!text) {
    error.value = '请输入一条指令，例如「查询库存」'
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  planResult.value = null
  reviewResult.value = null
  planToken.value = ''
  pendingCommand.value = ''
  try {
    // 先出计划：只读意图会直接给出可执行结果吗？不会——plan 只生成计划，执行另走 command。
    // 但 plan 已经带复核意见；若不需要人确认，这里立刻执行。
    const preview = await nlApi.plan(text, true)
    reviewResult.value = preview.review
    if (preview.plan.requiresReview) {
      // 写意图：把"将要发生什么"和"复核怎么看"摆出来，一步都不落地，等人点头
      planResult.value = preview.plan
      planToken.value = preview.confirmation.token ?? ''
      pendingCommand.value = text
      return
    }
    // 只读意图：查错了重问一次即可，不设确认成本
    await execute(text)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function confirmRun(): Promise<void> {
  const text = pendingCommand.value
  if (!text) return
  loading.value = true
  error.value = ''
  try {
    await execute(text, planToken.value)
    planResult.value = null
    reviewResult.value = null
    planToken.value = ''
    pendingCommand.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function cancelPlan(): void {
  planResult.value = null
  reviewResult.value = null
  planToken.value = ''
  pendingCommand.value = ''
}

async function execute(text: string, token?: string): Promise<void> {
  result.value = await nlApi.command(text, token, true)
}

function reviewFindings(): { level: string; message: string }[] {
  const findings = reviewResult.value?.findings ?? []
  // 计划里已说过的警告/阻塞不再重复，避免同一句话出现两次让人以为有两件事
  const shown = new Set([...(planResult.value?.warnings ?? []), ...(planResult.value?.blockers ?? [])])
  return findings.filter((finding) => !shown.has(finding.message))
}

onShow(() => {
  ensureLoggedIn()
})
</script>

<template>
  <view class="page nl">
    <view class="hero">
      <text class="hero-title">自然语言操作</text>
      <text class="hero-sub">一句话查库存、看单据、建草稿；写操作需你确认后才落地</text>
    </view>

    <view class="cmd-card">
      <textarea
        v-model="command"
        class="cmd-input"
        :maxlength="500"
        placeholder="用一句话操作，例如：查询 SKU 12 在仓库 3 的库存"
        auto-height
      />
      <button class="btn-primary cmd-run" :loading="loading" hover-class="none" @click="run">
        {{ loading ? '执行中' : '发送' }}
      </button>
    </view>

    <view class="chips">
      <text
        v-for="example in examples"
        :key="example"
        class="chip"
        @click="useExample(example)"
      >{{ example }}</text>
    </view>

    <view v-if="error" class="card error-block">
      <text class="block-text">{{ error }}</text>
    </view>

    <!-- 写意图计划卡：人确认前什么都不发生 -->
    <view v-if="hasPlan" class="card plan-card">
      <view class="row-between plan-head">
        <text class="plan-tag">待你确认</text>
        <text class="plan-intent">{{ intentLabel(planResult?.intent) }}</text>
      </view>
      <text class="plan-summary">{{ planResult?.summary }}</text>

      <view v-if="reviewResult" class="review" :class="`review--${reviewResult.verdict.toLowerCase()}`">
        <view class="review-head">
          <text class="review-verdict">{{ verdictLabel(reviewResult.verdict) }}</text>
          <text class="review-summary">{{ reviewResult.summary }}</text>
        </view>
        <view v-for="(f, i) in reviewFindings()" :key="i" class="finding">
          <text class="finding-text">{{ f.message }}</text>
        </view>
      </view>

      <view v-if="planResult && planResult.impact.length" class="impact">
        <view v-for="row in planResult.impact" :key="row.label" class="impact-row">
          <text class="impact-label">{{ row.label }}</text>
          <text class="impact-value">{{ row.value }}</text>
        </view>
      </view>

      <view v-if="planResult && planResult.warnings.length" class="plan-warns">
        <text v-for="(w, i) in planResult.warnings" :key="i" class="warn-text">· {{ w }}</text>
      </view>
      <view v-if="planResult && planResult.blockers.length" class="plan-blocks">
        <text v-for="(b, i) in planResult.blockers" :key="i" class="block-text">· {{ b }}</text>
      </view>

      <view class="plan-actions">
        <button
          class="btn-primary"
          :disabled="!planExecutable || reviewBlocking || loading"
          :loading="loading"
          hover-class="none"
          @click="confirmRun"
        >确认执行</button>
        <button class="btn-ghost" hover-class="none" @click="cancelPlan">取消</button>
      </view>
      <text class="plan-hint">
        {{
          reviewBlocking
            ? '复核否决了这条计划：先改指令，这里不会放行。'
            : !planExecutable
              ? '还有问题没解决，先改指令。'
              : '确认后才会真正生成单据；在此之前什么都没发生。'
        }}
      </text>
    </view>

    <!-- 执行结果 -->
    <view v-if="result" class="card result-card">
      <view class="row-between result-head">
        <text class="result-intent">{{ intentLabel(result.intent) }}</text>
        <text v-if="result.write" class="result-write">写操作</text>
      </view>
      <text v-if="conclusion" class="result-desc">{{ conclusion }}</text>

      <!-- 列表型结果只给前 5 条样本，剩下的靠"原始返回"看，不占屏 -->
      <view v-if="resultItems.length" :style="{ marginTop: '16rpx' }">
        <text :style="{ display: 'block', fontSize: '22rpx', color: '#8a90a2' }">
          共 {{ resultTotal }} 条，展示前 {{ Math.min(resultItems.length, 5) }} 条
        </text>
        <text
          v-for="(row, i) in resultItems.slice(0, 5)"
          :key="i"
          :style="{
            display: 'block',
            marginTop: '10rpx',
            padding: '12rpx 16rpx',
            background: '#f6f7f9',
            borderRadius: '10rpx',
            fontSize: '24rpx',
            color: '#1c2230',
          }"
        >{{ rowLine(row) }}</text>
        <text
          v-if="resultItems.length > 5"
          :style="{ display: 'block', marginTop: '10rpx', fontSize: '22rpx', color: '#8a90a2' }"
        >还有 {{ resultItems.length - 5 }} 条未展开</text>
      </view>

      <!-- 原始返回默认收起：第一眼该看到的是结论，不是引号和逗号 -->
      <view v-if="resultJson && resultJson !== 'null'" :style="{ marginTop: '16rpx' }">
        <text
          :style="{ fontSize: '22rpx', color: '#2f5d8a' }"
          @click="rawOpen = !rawOpen"
        >{{ rawOpen ? '收起原始返回' : '查看原始返回' }}</text>
        <view v-if="rawOpen" class="result-json">
          <text class="json-text">{{ resultJson }}</text>
        </view>
      </view>
    </view>

    <view v-if="loading" class="typing">
      <text class="typing-text">正在解析意图并调用后端…</text>
    </view>
  </view>
</template>

<style scoped>
.nl {
  padding-top: 20rpx;
}

.hero {
  padding: 8rpx 12rpx 20rpx;
}

.hero-title {
  display: block;
  font-size: 38rpx;
  font-weight: 700;
  color: #1c2230;
}

.hero-sub {
  display: block;
  margin-top: 8rpx;
  font-size: 23rpx;
  color: #8a90a2;
}

.cmd-card {
  display: flex;
  align-items: flex-end;
  gap: 16rpx;
  padding: 20rpx;
  background: #ffffff;
  border-radius: 20rpx;
  box-shadow: 0 6rpx 20rpx rgba(21, 33, 56, 0.06);
}

.cmd-input {
  flex: 1;
  min-height: 80rpx;
  max-height: 240rpx;
  padding: 16rpx;
  font-size: 27rpx;
  line-height: 1.5;
  background: #f7f9fc;
  border-radius: 14rpx;
  color: #1c2230;
}

.cmd-run {
  flex-shrink: 0;
  height: 76rpx;
  padding: 0 30rpx;
  font-size: 26rpx;
  background: #1f3a63;
  color: #ffffff;
  border-radius: 14rpx;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin: 20rpx 0;
}

.chip {
  padding: 12rpx 22rpx;
  border-radius: 999rpx;
  background: #e9edf3;
  color: #586176;
  font-size: 23rpx;
}

.error-block {
  padding: 24rpx;
  background: #fdf1ee;
  border: 1rpx solid #f3d3ca;
  border-radius: 16rpx;
}

.block-text {
  display: block;
  line-height: 1.5;
  font-size: 24rpx;
  color: #9c3a26;
}

.plan-card {
  padding: 24rpx;
  border: 1rpx solid #cdd9e8;
  border-radius: 18rpx;
  background: #f3f7fc;
}

.plan-head {
  margin-bottom: 14rpx;
}

.plan-tag {
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  background: #1f3a63;
  color: #ffffff;
  font-size: 21rpx;
}

.plan-intent {
  font-size: 24rpx;
  font-weight: 600;
  color: #2c4066;
}

.plan-summary {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #1c2230;
  line-height: 1.5;
}

.review {
  margin-top: 16rpx;
  padding: 16rpx;
  border-radius: 12rpx;
  border-left: 6rpx solid #b4702f;
  background: #ffffff;
}

.review--approve {
  border-left-color: #2f7d5b;
}

.review--need_human {
  border-left-color: #b4702f;
}

.review--reject {
  border-left-color: #b4462f;
}

.review-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10rpx;
}

.review-verdict {
  font-size: 24rpx;
  font-weight: 700;
}

.review-summary {
  font-size: 22rpx;
  color: #6e7585;
}

.finding {
  margin-top: 6rpx;
}

.finding-text {
  font-size: 22rpx;
  color: #6e7585;
  line-height: 1.5;
}

.impact {
  margin-top: 16rpx;
  padding-top: 14rpx;
  border-top: 1rpx dashed #dbe2ec;
}

.impact-row {
  display: flex;
  gap: 12rpx;
  padding: 4rpx 0;
  font-size: 23rpx;
}

.impact-label {
  flex-shrink: 0;
  width: 140rpx;
  color: #8a90a2;
}

.impact-value {
  flex: 1;
  min-width: 0;
  color: #2b3245;
}

.plan-warns {
  margin-top: 12rpx;
  display: flex;
  flex-direction: column;
}

.warn-text {
  font-size: 22rpx;
  color: #b4702f;
  line-height: 1.5;
}

.plan-blocks {
  margin-top: 8rpx;
  display: flex;
  flex-direction: column;
}

.plan-actions {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 20rpx;
}

.plan-actions .btn-primary {
  flex: 1;
  height: 76rpx;
  font-size: 26rpx;
  background: #1f3a63;
  color: #ffffff;
  border-radius: 14rpx;
}

.plan-actions .btn-ghost {
  flex: 1;
  height: 76rpx;
  font-size: 26rpx;
  background: #ffffff;
  color: #586176;
  border: 1rpx solid #dbe2ec;
  border-radius: 14rpx;
}

.plan-hint {
  display: block;
  margin-top: 12rpx;
  font-size: 21rpx;
  color: #a2a8b6;
  line-height: 1.5;
}

.result-card {
  padding: 24rpx;
}

.result-head {
  margin-bottom: 12rpx;
}

.result-intent {
  font-size: 26rpx;
  font-weight: 700;
  color: #1c2230;
}

.result-write {
  padding: 4rpx 14rpx;
  border-radius: 999rpx;
  background: #fdf0ec;
  color: #b4462f;
  font-size: 20rpx;
}

.result-desc {
  display: block;
  font-size: 25rpx;
  line-height: 1.6;
  color: #2b3245;
  white-space: pre-wrap;
  word-break: break-word;
}

.result-json {
  margin-top: 14rpx;
  padding: 16rpx;
  background: #f3f5f8;
  border-radius: 12rpx;
}

.json-text {
  font-size: 20rpx;
  color: #586176;
  white-space: pre-wrap;
  word-break: break-all;
}

.typing {
  margin-top: 16rpx;
}

.typing-text {
  font-size: 23rpx;
  color: #8a90a2;
}
</style>
