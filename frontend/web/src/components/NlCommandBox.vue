<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { nlApi, type NlCommandResult, type NlPlanResult, type NlReview } from '@/api/client'
import { intentLabel, verdictLabel } from '@/utils/nl'

/**
 * 工作台底部的「自然语言操作台」（FR-SYS-04）。
 *
 * 从"表单"改成"终端"的三个理由：
 *  1. 它本质是命令行 —— 一句话进去、结果出来，多行输入框反而暗示"写作文"；
 *  2. 企业用户会连着问（查完库存顺手查单据），所以要有历史，能一键重跑；
 *  3. 键盘优先：Enter 执行、↑ 取上一条，手不离开键盘。
 *
 * 权限、幂等键、业务规则全部在后端（NL 层只解析意图 + 调度 ApplicationService）；
 * 组件只负责输入、调用、展示，不复制任何业务判断。
 */

interface FlatRow {
  [key: string]: unknown
}

const HISTORY_KEY = 'erp.nl.history'
const HISTORY_MAX = 8

const command = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<NlCommandResult | null>(null)
const plan = ref<NlPlanResult | null>(null)
/** 复核意见（S4）：写操作的卡片上要显示"另一个复核方怎么看"。 */
const review = ref<NlReview | null>(null)
/** /nl/plan 签发的确认回执；写意图执行时原样带回服务端，缺了会 409。 */
const planToken = ref('')
const pendingCommand = ref('')
const cmdRow = ref<HTMLElement | null>(null)

const period = (() => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
})()

/**
 * 示例指令 —— 只能放 `NlIntent` 里真实存在、且 stub 解析器认得的写法。
 *
 * 后端当前支持 9 个意图（8 个原子 + 1 个目标级）：help / query_inventory / list_documents /
 * get_document / inventory_summary / query_master_data / create_purchase_order /
 * create_sales_order，以及 V4 新增的 replenish_advice（补货建议，一次组合可用量/在途/上下限）。
 *
 * 这里刻意不放写意图的示例，避免用户误点产生单据；想知道完整能力就点「帮助」，
 * 它直接问后端要意图清单。
 */
const examples = [
  '帮助',
  '补货建议',
  '查询库存',
  '进货单列表 第1页',
  `账期 ${period} 进销存汇总`,
  '查询商品 关键字 T恤',
]

// ─────────────────────────── 历史 ───────────────────────────

function loadHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    const list = raw ? (JSON.parse(raw) as unknown) : []
    return Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string').slice(0, HISTORY_MAX) : []
  } catch {
    return []
  }
}

const history = ref<string[]>(loadHistory())

function remember(text: string): void {
  const next = [text, ...history.value.filter((h) => h !== text)].slice(0, HISTORY_MAX)
  history.value = next
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(next))
  } catch {
    // 存不下不影响本次使用
  }
}

function clearHistory(): void {
  history.value = []
  try {
    localStorage.removeItem(HISTORY_KEY)
  } catch {
    // 忽略
  }
}

// ─────────────────────────── 结果解析 ───────────────────────────

const items = computed<FlatRow[]>(() => {
  const data = result.value?.data
  if (data && typeof data === 'object') {
    // 读意图返回 data.items；help 返回 data.intents（意图清单），两者都是列表，同样用表格渲染。
    for (const key of ['items', 'intents']) {
      const list = (data as Record<string, unknown>)[key]
      if (Array.isArray(list)) {
        return list.filter((item): item is FlatRow => !!item && typeof item === 'object')
      }
    }
  }
  return []
})

/** 动态列只取标量字段；嵌套的 lines 等对象/数组留给原始 JSON。 */
const columns = computed(() => {
  const first = items.value[0]
  if (!first) return []
  return Object.keys(first).filter((key) => {
    const value = first[key]
    return value === null || typeof value !== 'object'
  })
})

/**
 * 画像类结果（`{基本信息:{...}, 往来情况:{...}}`）的分组展示。
 *
 * ★ 为什么需要它：表格只认数组，而"供应商画像"是**两层对象** ——
 *   光靠摘要那一句话，用户看不到基本信息与往来账；不渲染就等于白查。
 * ★ 判定刻意收窄：有 items/intents 就走表格，是数组就走表格，
 *   只有"对象且不含列表键"才当画像。避免同一个响应被渲染两遍。
 */
const profileGroups = computed(() => {
  const data = result.value?.data
  if (!data || typeof data !== 'object' || Array.isArray(data)) return []
  const record = data as Record<string, unknown>
  if ('items' in record || 'intents' in record) return []
  return Object.entries(record).map(([group, value]) => {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return {
        title: group,
        rows: Object.entries(value as Record<string, unknown>).map(([key, v]) => ({
          key,
          value: display(v, key),
        })),
      }
    }
    return { title: '', rows: [{ key: group, value: display(value, group) }] }
  })
})

/**
 * 列头中文名。后端返回的是字段名（skuId / suggestedQty…），
 * 直接当表头给用户看等于让他读数据库 —— 这里做一层展示名映射，
 * 没收录的字段原样显示，避免"新增字段就不显示"。
 */
const COLUMN_LABELS: Record<string, string> = {
  skuId: 'SKU',
  warehouseId: '仓库',
  batchNo: '批次',
  onHandQty: '实存',
  reservedQty: '预占',
  availableQty: '可用',
  minLevel: '下限',
  maxLevel: '上限',
  suggestedQty: '建议补货',
  warehousePriority: '仓优先级',
  totalSuggestedQty: '建议合计',
  expiryDate: '有效期',
  // 单据列表 / 详情
  id: '编号',
  orderNo: '单号',
  docNo: '单号',
  docType: '单据类型',
  docId: '单据 id',
  status: '状态',
  bizDate: '业务日期',
  totalAmount: '金额',
  totalQty: '数量',
  period: '账期',
  remark: '备注',
  customerId: '客户',
  supplierId: '供应商',
  originSalesOrderId: '原出货单',
  version: '版本',
  lines: '明细行',
  total: '共',
  page: '页码',
  size: '每页',
  intent: '意图',
  write: '写操作',
  requiredPermission: '所需权限',
  example: '示例',
}

/**
 * 状态码中文名。模型/接口返回的是 `APPROVED` 这类枚举，直接给用户看
 * 等于让他背代码。未收录的仍原样显示，新增状态不会消失。
 */
const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已审核',
  PARTIAL: '部分完成',
  COMPLETED: '已完成',
  VOIDED: '已作废',
  REJECTED: '已驳回',
}

function columnLabel(key: string): string {
  return COLUMN_LABELS[key] ?? key
}

/**
 * 复核来源的展示文案（这一段说的是"谁看的"，不是"谁调用的"，两者别混）。
 */
function reviewerLabel(source: string): string {
  if (source.includes('+')) return '规则 + AI'
  return source === 'AI' ? 'AI 复核' : '规则校验'
}

/** 结果里的 JSON 原文，供"复制 JSON"与排障。 */
const pretty = computed(() =>
  result.value ? JSON.stringify(result.value.data, null, 2) : '',
)

/** 结果里的标量摘出来做"结论行"，不用每次都展开 JSON 才看到关键数字。 */
const summaryChips = computed(() => {
  const data = result.value?.data
  if (!data || typeof data !== 'object' || Array.isArray(data)) return []
  return Object.entries(data as Record<string, unknown>)
    .filter(([, value]) => value === null || typeof value !== 'object')
    .slice(0, 6)
    .map(([key, value]) => ({ key: columnLabel(key), value: display(value, key) }))
})

function useExample(text: string): void {
  command.value = text
  focusInput()
}

function focusInput(): void {
  // 直接找内层 textarea：el-input 实例的 focus() 在类型上不好收口，
  // 而"把光标放进输入框"这件事用 DOM 反而更稳。
  void nextTick(() => cmdRow.value?.querySelector('textarea')?.focus())
}

/**
 * 金额类字段：后端一律以「分」为单位存（避免浮点误差），
 * 直接显示 98550 会让人以为是九万八，实际是 985.50 元。
 * 这里只做展示换算，不改数据。
 */
const MONEY_KEYS = new Set(['totalAmount', 'unitPrice', 'lineAmount', 'amount', 'price'])

/**
 * 单元格展示：状态码转中文、金额转元，其余原样。
 *
 * @param column 列名。没有它就无法区分"status 列的 APPROVED"与
 *               "备注里恰好写了 APPROVED"，所以状态映射只在 status 列生效。
 */
function display(value: unknown, column?: string): string {
  if (value === null || value === undefined) return '-'
  // 列名可能是 `status`，也可能是模型写的中文别名 `状态` —— 两种都要翻译，
  // 否则自定义查询里状态码会以 COMPLETED 这种形式露出来。
  if ((column === 'status' || column === '状态')
    && typeof value === 'string' && STATUS_LABELS[value]) {
    return STATUS_LABELS[value]
  }
  if (column && MONEY_KEYS.has(column) && typeof value === 'number') {
    return '¥' + (value / 100).toFixed(2)
  }
  return String(value)
}

async function copyJson(): Promise<void> {
  if (!pretty.value) return
  try {
    await navigator.clipboard.writeText(pretty.value)
    ElMessage.success('结果 JSON 已复制')
  } catch {
    ElMessage.warning('浏览器不允许写剪贴板，请手动选择复制')
  }
}

/**
 * 执行入口：**先出计划，再决定要不要人点头**。
 *
 * 这条分支是整个 V4 的核心，逻辑只有三行：
 *   - 只读意图（requiresReview=false）→ 直接执行，不让用户多点一次；
 *   - 写意图（requiresReview=true）→ 停在计划卡上，等人点「确认执行」；
 *   - 计划本身有阻塞项（executable=false）→ 连确认按钮都不给。
 *
 * 注意"预览"和"执行"是两次请求、同一个 X-Request-Id：
 * 这样后端审计能把"它当时打算干什么"和"最终干了什么"对上。
 */
async function run(): Promise<void> {
  const text = command.value.trim()
  if (!text) {
    error.value = '请输入一条指令，例如「查询库存」'
    focusInput()
    return
  }
  loading.value = true
  error.value = ''
  result.value = null
  plan.value = null
  try {
    const preview = await nlApi.plan(text)
    // 复核意见无论只读还是写都先收下：只读那份是"无副作用"的说明，不展示也不浪费
    review.value = preview.review
    if (preview.plan.requiresReview) {
      // 写操作：先把"将要发生什么"和"另一个人怎么看"摆出来，一步都不落地
      plan.value = preview.plan
      planToken.value = preview.confirmation.token ?? ''
      pendingCommand.value = text
      return
    }
    // 只读：查错了重问一次即可，不设审核成本
    await execute(text)
  } catch (e) {
    error.value = (e as Error).message || '无法生成执行计划'
  } finally {
    loading.value = false
  }
}

/** 人点「确认执行」——这一步之后才真正产生数据，凭据随请求带回服务端。 */
async function confirmRun(): Promise<void> {
  const text = pendingCommand.value
  if (!text) return
  loading.value = true
  error.value = ''
  try {
    await execute(text, planToken.value)
    plan.value = null
    review.value = null
    planToken.value = ''
    pendingCommand.value = ''
  } catch (e) {
    error.value = (e as Error).message || '指令执行失败'
  } finally {
    loading.value = false
  }
}

function cancelPlan(): void {
  plan.value = null
  review.value = null
  planToken.value = ''
  pendingCommand.value = ''
  focusInput()
}

async function execute(text: string, planToken?: string): Promise<void> {
  result.value = await nlApi.command(text, planToken)
  remember(text)
}

/** 复核结论的中文说法：APPROVE 必须读成"不反对"，不能让界面写成"AI 已批准"。 */
/* verdictLabel 来自 @/utils/nl（与队列、审计共用同一份文案） */

/**
 * 卡片上真正要展示的复核意见。
 *
 * 计划里已经写过一遍的警告/阻塞项不再重复：同一句话出现两次，人会以为有两件事。
 * 后端仍然完整记录（审计要全量），只是界面上去重。
 */
const reviewFindings = computed(() => {
  const findings = review.value?.findings ?? []
  const shown = new Set([...(plan.value?.warnings ?? []), ...(plan.value?.blockers ?? [])])
  return findings.filter((finding) => finding.source !== 'SYSTEM' && !shown.has(finding.message))
})

/**
 * 系统级的说明（"AI 复核未启用""模型不可用，已降级"）。
 *
 * 单独摘出来放脚注，不混在意见列表里：它不是"挑出来的毛病"，
 * 而是"这一层有没有参与者"的事实说明。混在一起会让人以为它是第 N 个问题。
 */
const reviewNotes = computed(() =>
  (review.value?.findings ?? [])
    .filter((finding) => finding.source === 'SYSTEM')
    .map((finding) => finding.message),
)

/** ↑ 取上一条（输入框为空时），↓ 清空 —— 终端里的肌肉记忆 */
function onArrowUp(event: KeyboardEvent): void {
  if (!history.value.length) return
  event.preventDefault()
  const current = command.value.trim()
  const index = current ? history.value.indexOf(current) : -1
  const next = index <= 0 ? history.value[0] : history.value[index - 1]
  if (next) command.value = next
}

function onArrowDown(event: KeyboardEvent): void {
  if (!history.value.length) return
  event.preventDefault()
  const index = history.value.indexOf(command.value.trim())
  if (index === -1) return
  command.value = index + 1 < history.value.length ? history.value[index + 1] : ''
}
</script>

<template>
  <el-card shadow="never" class="console">
    <template #header>
      <div class="console-head">
        <div class="console-title">
          <span class="console-mark" aria-hidden="true">›_</span>
          <span>自然语言操作台</span>
          <el-tag size="small" effect="plain" class="console-badge">FR-SYS-04</el-tag>
        </div>
        <div class="console-keys">
          <span class="key-hint"><kbd>Enter</kbd>执行</span>
          <span class="key-hint"><kbd>↑</kbd>上一条</span>
        </div>
      </div>
    </template>

    <!--
      说明区：三行「能查 / 能建 / 不做」，替代原来堆在操作台下方的一段散文。
      为什么用 key-value 而不是段落：这段文字直接决定用户"敢不敢按执行"，
      需要一眼扫完；顺便把系统明确不做的事写在明处（不删、不改账、不入库），
      比让用户试出来更可信。
    -->
    <dl class="spec">
      <div class="spec-row">
        <dt>能查</dt>
        <dd>库存与可用量、进货 / 出货 / 退货单列表与详情、账期进销存汇总、<strong>补货建议（含建议量与依据）</strong>、商品 / SKU / 供应商 / 客户 / 仓库</dd>
      </div>
      <div class="spec-row">
        <dt>能建</dt>
        <dd>进货单、出货单的<strong>草稿</strong> —— 先出执行计划，人确认后才落库；入库、审核、作废仍要回页面操作</dd>
      </div>
      <div class="spec-row spec-row--limit">
        <dt>不做</dt>
        <dd>不删数据、不直接改账、不做出入库与审核；识别不到的指令直接拒绝，不会猜着执行</dd>
      </div>
    </dl>

    <!-- 输入行：prompt + 输入 + 执行，视觉上是一条命令行 -->
    <div ref="cmdRow" class="cmd-row">
      <span class="prompt" aria-hidden="true">›</span>
      <el-input
        v-model="command"
        type="textarea"
        :rows="2"
        maxlength="500"
        resize="none"
        class="cmd-input"
        placeholder="用一句话操作，例如：查询 SKU 12 在仓库 3 的库存"
        @keydown.enter.exact.prevent="run"
        @keydown.up.exact.prevent="onArrowUp"
        @keydown.down.exact.prevent="onArrowDown"
      />
      <el-button type="primary" :loading="loading" class="cmd-run" @click="run">
        {{ loading ? '执行中' : '执行' }}
      </el-button>
    </div>

    <div class="chips">
      <span class="chips-label">试一试</span>
      <button
        v-for="example in examples"
        :key="example"
        type="button"
        class="chip"
        @click="useExample(example)"
      >
        {{ example }}
      </button>
    </div>

    <div v-if="history.length" class="chips chips--history">
      <span class="chips-label">最近</span>
      <button
        v-for="h in history"
        :key="h"
        type="button"
        class="chip chip--history"
        :title="`重新执行：${h}`"
        @click="useExample(h)"
      >
        {{ h }}
      </button>
      <button type="button" class="chip-clear" @click="clearHistory">清空</button>
    </div>

    <!--
      计划卡（FR-V4-03 · S3）：写操作先给人看"将要发生什么"，一步都不落地。
      只读意图不会走到这里（它们直接执行），所以这张卡出现 = 这一步会产生数据。
    -->
    <div v-if="plan" class="plan">
      <div class="plan-head">
        <span class="plan-tag">待你确认</span>
        <span class="plan-title">{{ plan.summary }}</span>
      </div>

      <!--
        复核（S4）：另一个复核方对**这份计划**的意见。
        没有它，人只能凭自己一眼判断；有了它，人才知道该往哪儿看第二眼。
        文案上 APPROVE 一律说"不反对"，绝不说"已批准"——否则人会跳过自己那一眼。
      -->
      <div v-if="review" class="review" :class="`review--${review.verdict.toLowerCase()}`">
        <div class="review-head">
          <span class="review-dot" aria-hidden="true"></span>
          <span class="review-verdict">{{ verdictLabel(review.verdict) }}</span>
          <span class="review-source">{{ reviewerLabel(review.source) }}</span>
          <span class="review-summary">{{ review.summary }}</span>
        </div>
        <ul v-if="reviewFindings.length" class="review-list">
          <li
            v-for="(finding, i) in reviewFindings"
            :key="i"
            :class="`finding finding--${finding.level.toLowerCase()}`"
          >
            <span class="finding-src">{{ finding.source === 'AI' ? 'AI' : '规则' }}</span>
            <span class="finding-text">{{ finding.message }}</span>
          </li>
        </ul>
        <p v-if="reviewNotes.length" class="review-note">{{ reviewNotes.join('；') }}</p>
      </div>

      <p class="plan-reason">{{ plan.reviewReason }}</p>

      <div class="plan-impact">
        <div v-for="row in plan.impact" :key="row.label" class="impact-row">
          <span class="impact-label">{{ row.label }}</span>
          <span class="impact-value">{{ row.value }}</span>
        </div>
      </div>

      <details class="plan-more">
        <summary>执行步骤（{{ plan.steps.length }} 步）</summary>
        <ol class="plan-steps">
          <li v-for="(step, i) in plan.steps" :key="i">{{ step }}</li>
        </ol>
      </details>

      <ul v-if="plan.warnings.length" class="plan-warns">
        <li v-for="(w, i) in plan.warnings" :key="i">{{ w }}</li>
      </ul>

      <ul v-if="plan.blockers.length" class="plan-blocks">
        <li v-for="(b, i) in plan.blockers" :key="i">{{ b }}</li>
      </ul>

      <div class="plan-actions">
        <el-button
          type="primary"
          :disabled="!plan.executable || !!review?.blocking"
          :loading="loading"
          @click="confirmRun"
        >
          确认执行
        </el-button>
        <el-button @click="cancelPlan">取消</el-button>
        <span class="plan-hint">
          {{
            review?.blocking
              ? '复核否决了这条计划：先改指令，这里不会放行。'
              : !plan.executable
                ? '还有问题没解决，先改指令。'
                : review?.verdict === 'NEED_HUMAN'
                  ? '有需要你判断的地方；确认后才会真正生成单据。'
                  : '确认后才会真正生成单据；在此之前什么都没发生。'
          }}
        </span>
      </div>
    </div>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mt-10"
      :title="error"
    />

    <!-- 执行中的"打字"反馈：等待时不至于像卡死 -->
    <div v-if="loading" class="typing">
      <span class="typing-dot" />
      <span class="typing-dot" />
      <span class="typing-dot" />
      <span class="typing-text">正在解析意图并调用后端…</span>
    </div>

    <div v-if="result" class="nl-result">
      <div class="nl-result-head">
        <el-tag
          :type="result.write ? 'warning' : 'success'"
          size="small"
          effect="dark"
          :title="result.intent"
        >
          {{ intentLabel(result.intent) }}
        </el-tag>
        <el-tag v-if="result.write" type="warning" size="small" effect="plain">写操作</el-tag>
        <span
          class="nl-description"
          :class="{ 'is-long': (result.description?.length ?? 0) > 40 }"
        >{{ result.description }}</span>
        <el-button link type="primary" size="small" class="copy-btn" @click="copyJson">
          复制 JSON
        </el-button>
      </div>

      <div v-if="summaryChips.length" class="summary-chips">
        <span v-for="chip in summaryChips" :key="chip.key" class="summary-chip">
          <span class="summary-key">{{ chip.key }}</span>
          <span class="summary-val">{{ chip.value }}</span>
        </span>
      </div>

      <div v-if="profileGroups.length" class="profile">
        <div v-for="group in profileGroups" :key="group.title || 'root'" class="profile-group">
          <div v-if="group.title" class="profile-title">{{ group.title }}</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-for="row in group.rows" :key="row.key" :label="row.key">
              {{ row.value }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </div>

      <el-table
        v-if="items.length && columns.length"
        :data="items"
        size="small"
        border
        class="mt-10"
        max-height="320"
      >
        <el-table-column
          v-for="column in columns"
          :key="column"
          :prop="column"
          :label="columnLabel(column)"
          min-width="110"
        >
          <template #default="{ row }">{{ display(row[column], column) }}</template>
        </el-table-column>
      </el-table>

      <el-collapse class="mt-10">
        <el-collapse-item title="查看原始结果（JSON）" name="raw">
          <pre class="nl-json">{{ pretty }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>
  </el-card>
</template>

<style scoped>
.console {
  border-radius: var(--ys-r-card);
}

.console-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.console-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13.5px;
}

.console-mark {
  font-family: var(--ys-font-num);
  font-size: 13px;
  color: var(--ys-daiqing);
  letter-spacing: -0.04em;
}

.console-badge {
  font-family: var(--ys-font-num);
  font-size: 10.5px;
}

/* 快捷键提示：kbd 只做视觉，读屏读的是后面的文字 */
.console-keys {
  display: flex;
  align-items: center;
  gap: 12px;
}

.key-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.key-hint kbd {
  padding: 1px 5px;
  border: 1px solid var(--ys-line);
  border-radius: 4px;
  background: var(--ys-warm-50);
  font-family: var(--ys-font-num);
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--ys-mo-3);
}

/* ── 说明区：能查 / 能建 / 不做 ── */
.spec {
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--ys-track-wash);
  /* 深色下 wash 与卡片底只差几个色阶，靠一圈内描边把块"框"出来 */
  box-shadow: inset 0 0 0 1px var(--ys-line-soft);
}

.spec-row {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 10px;
  align-items: baseline;
  padding: 4px 0;
}

.spec-row + .spec-row {
  border-top: 1px dashed var(--ys-line-soft);
}

.spec-row dt {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ys-mo-4);
  letter-spacing: 0.02em;
}

.spec-row dd {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--ys-mo-3);
}

.spec-row dd strong {
  font-weight: 600;
  color: var(--ys-mo-2);
}

/* "不做" 用更深的字重区分 —— 这行是用户在犹豫时最想确认的，但它是说明不是报错，不用告警色 */
.spec-row--limit dt {
  color: var(--ys-mo-2);
}

.cmd-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.prompt {
  padding-top: 7px;
  font-family: var(--ys-font-num);
  font-size: 18px;
  line-height: 1;
  color: var(--ys-daiqing);
  user-select: none;
}

.cmd-input {
  flex: 1 1 auto;
}

.cmd-input :deep(.el-textarea__inner) {
  font-family: var(--ys-font-cn);
  font-size: 13.5px;
  line-height: 1.6;
  background: var(--ys-warm-50);
  box-shadow: 0 0 0 1px var(--ys-line) inset;
}

.cmd-input :deep(.el-textarea__inner:focus) {
  background: var(--ys-cibai);
  box-shadow: 0 0 0 1px var(--ys-daiqing) inset, 0 0 0 3px rgba(46, 93, 99, 0.12);
}

.cmd-run {
  flex: 0 0 auto;
  height: 38px;
  min-width: 76px;
}

/* ── 示例 / 历史 ── */
.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.chips--history {
  margin-top: 6px;
}

.chips-label {
  margin-right: 2px;
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.chip {
  padding: 3px 10px;
  border: 1px solid var(--ys-line);
  border-radius: 999px;
  background: transparent;
  font-size: 12px;
  color: var(--ys-mo-2);
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background-color 0.16s ease,
    color 0.16s ease,
    transform 0.16s ease;
}

.chip:hover {
  border-color: var(--ys-daiqing);
  background: var(--ys-daiqing-wash);
  color: var(--ys-daiqing-deep);
}

.chip:active {
  transform: scale(0.97);
}

.chip--history {
  border-style: dashed;
  color: var(--ys-mo-3);
}

.chip-clear {
  margin-left: 2px;
  padding: 3px 6px;
  border: 0;
  background: none;
  font-size: 11.5px;
  color: var(--ys-mo-5);
  cursor: pointer;
}

.chip-clear:hover {
  color: var(--ys-yanzhi);
}

/* ── 执行中 ── */
.typing {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 12px;
}

.typing-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ys-daiqing);
  animation: typing 1s ease-in-out infinite;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.15s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes typing {
  0%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.typing-text {
  margin-left: 6px;
  font-size: 12px;
  color: var(--ys-mo-4);
}

/* ── 结果 ── */
.nl-result {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--ys-line-soft);
}

.nl-result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.nl-description {
  font-size: 12.5px;
  color: var(--ys-mo-2);
}

/*
 * 长描述 = 模型写的"回答"（SQL_QUERY 的结果）。
 * 它跟"共 5 条"这种一句话摘要不是一个东西：前者是给人读的结论，
 * 挤在标题右边一行会看不出重点，所以整行铺开、加底色、加大行高。
 */
.nl-description.is-long {
  display: block;
  width: 100%;
  margin-top: 8px;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
  border-left: 2px solid var(--el-color-primary-light-5);
  border-radius: 4px;
  padding: 10px 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.copy-btn {
  margin-left: auto;
}

.profile {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.profile-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.summary-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.summary-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--ys-line-soft);
  border-radius: 8px;
  background: var(--ys-warm-50);
}

.summary-key {
  font-size: 11px;
  color: var(--ys-mo-4);
}

.summary-val {
  font-family: var(--ys-font-num);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--ys-mo);
}

.nl-json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  padding: 10px;
  border-radius: 8px;
  background: var(--ys-warm-50);
  font-size: 12px;
  line-height: 1.5;
  color: var(--ys-mo-2);
}

/* ── Agent 提示 ── */
/* ── 计划卡：写操作的"执行前预览" ── */
.plan {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--ys-daiqing-soft);
  border-radius: 10px;
  background: var(--ys-daiqing-wash);
}

.plan-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}

/* 徽标用品牌色打底、用"页面底色"写字：浅色主题下是白字，深色主题下自动变成墨字 */
.plan-tag {
  flex: 0 0 auto;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--ys-daiqing);
  color: var(--ys-nuanbai);
  font-size: 11px;
  line-height: 1.6;
}

.plan-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ys-mo);
}

.plan-reason {
  margin: 6px 0 0;
  font-size: 11.5px;
  color: var(--ys-mo-3);
}

/* ── 复核意见（S4）── */
.review {
  margin: 8px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--ys-line-soft);
  border-left-width: 3px;
  border-radius: 8px;
  background: var(--ys-track-wash);
}

/* 三档结论用三种语义色，靠左边框区分——不靠背景色，避免和"待确认"的蓝绿底打架 */
.review--approve {
  border-left-color: var(--ys-daiqing);
}

.review--need_human {
  border-left-color: var(--ys-tai);
}

.review--reject {
  border-left-color: var(--ys-yanzhi);
}

.review-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}

.review-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.review--approve .review-dot {
  background: var(--ys-daiqing);
}

.review--need_human .review-dot {
  background: var(--ys-tai);
}

.review--reject .review-dot {
  background: var(--ys-yanzhi);
}

.review-verdict {
  font-size: 12px;
  font-weight: 600;
}

.review--approve .review-verdict {
  color: var(--ys-daiqing);
}

.review--need_human .review-verdict {
  color: var(--ys-taotu);
}

.review--reject .review-verdict {
  color: var(--ys-yanzhi);
}

.review-source {
  padding: 0 4px;
  border: 1px solid var(--ys-line);
  border-radius: 4px;
  font-size: 10.5px;
  color: var(--ys-mo-4);
}

.review-summary {
  font-size: 11.5px;
  color: var(--ys-mo-3);
}

.review-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}

.finding {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 11.5px;
  line-height: 1.55;
}

.finding-src {
  flex: none;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--ys-warm-100);
  font-family: var(--ys-font-num);
  font-size: 10px;
  color: var(--ys-mo-4);
}

.finding-text {
  color: var(--ys-mo-2);
}

.finding--reject .finding-text {
  color: var(--ys-yanzhi);
}

/* 系统说明（"AI 复核未启用"之类）：比意见更淡，说明事实而不是挑毛病 */
.review-note {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ys-mo-4);
}

.plan-impact {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 0 18px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ys-line);
}

.impact-row {
  display: flex;
  gap: 8px;
  padding: 2px 0;
  font-size: 12px;
}

/* 标签定宽，值紧跟在右边：宽卡片里"左标签—右值"会被拉开成两团，读起来要来回找 */
.impact-label {
  flex: 0 0 56px;
  color: var(--ys-mo-4);
  white-space: nowrap;
}

.impact-value {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--ys-mo-2);
  font-variant-numeric: tabular-nums;
}

.plan-more {
  margin-top: 8px;
}

.plan-more summary {
  cursor: pointer;
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.plan-steps {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--ys-mo-3);
}

.plan-warns,
.plan-blocks {
  margin: 8px 0 0;
  padding-left: 16px;
  font-size: 12px;
  line-height: 1.65;
}

.plan-warns {
  color: var(--ys-taotu);
}

.plan-blocks {
  color: var(--ys-yanzhi);
}

.plan-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}

.plan-hint {
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.mt-10 {
  margin-top: 10px;
}

@media (max-width: 768px) {
  .cmd-row {
    flex-wrap: wrap;
  }

  .cmd-run {
    width: 100%;
  }

  .console-keys {
    display: none;
  }

  .spec-row {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
