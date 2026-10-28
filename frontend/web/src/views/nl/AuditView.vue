<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PageTable from '@/components/PageTable.vue'
import { nlAuditApi, type NlExecutionRecord } from '@/api/client'
import { INTENT_LABELS, intentLabel, sourceLabel, statusTone, verdictLabel } from '@/utils/nl'

/**
 * 操作审计（FR-V4-03 · S6）。
 *
 * ★ 这个页面要回答的不是"成功了没有"，而是**四件事**：
 *   谁说的（actor）、说了什么（commandText）、当时怎么判的（plan / review 快照）、
 *   最后怎么了（status + 单号 + 失败原因）。
 *
 * ★ 为什么把"被挡住的那条"也显示出来（BLOCKED / VETOED / DISMISSED / EXPIRED）：
 *   只记成功的审计等于把最需要复盘的失败藏起来。
 *   "它想干什么、被什么挡住"本身就是审计价值——这是判断"要不要放宽"的唯一依据。
 *
 * ★ 为什么详情里显示的是快照而不是实时重算：审计认证的是**当时看到的东西**。
 *   解析器换了模型、补货公式改了，历史记录都不该跟着变，否则事后对不上账。
 */

interface Filters {
  status: string
  intent: string
  source: string
  write: '' | 'true' | 'false'
  range: [string, string] | null
}

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待确认' },
  { value: 'EXECUTED', label: '已执行' },
  { value: 'FAILED', label: '执行失败' },
  { value: 'DISMISSED', label: '已驳回' },
  { value: 'VETOED', label: '被否决' },
  { value: 'BLOCKED', label: '不可执行' },
  { value: 'EXPIRED', label: '已过期' },
]

const SOURCE_OPTIONS = [
  { value: '', label: '全部来源' },
  { value: 'WEB', label: '网页' },
  { value: 'MCP', label: 'MCP Agent' },
  { value: 'API', label: '接口调用' },
]

const rows = ref<NlExecutionRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref('')
const detail = ref<NlExecutionRecord | null>(null)
const detailOpen = ref(false)

const filters = ref<Filters>({ status: '', intent: '', source: '', write: '', range: null })

const intentOptions = computed(() =>
  Object.entries(INTENT_LABELS).map(([value, label]) => ({ value, label })),
)

/** 只把真正填了的条件发给后端：空字符串会被后端当成"筛 ''"，反而查不到。 */
const filtered = computed(
  () =>
    !!filters.value.status ||
    !!filters.value.intent ||
    !!filters.value.source ||
    !!filters.value.write ||
    !!filters.value.range,
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const f = filters.value
    const res = await nlAuditApi.page({
      status: f.status || undefined,
      intent: f.intent || undefined,
      source: f.source || undefined,
      write: f.write === '' ? undefined : f.write === 'true',
      from: f.range ? new Date(`${f.range[0]}T00:00:00`).toISOString() : undefined,
      to: f.range ? new Date(`${f.range[1]}T23:59:59`).toISOString() : undefined,
      page: page.value,
      size: pageSize.value,
    })
    rows.value = res.items
    total.value = res.total
  } catch (e) {
    error.value = (e as Error).message || '加载审计记录失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function reset(): void {
  filters.value = { status: '', intent: '', source: '', write: '', range: null }
  page.value = 1
  void load()
}

function onPageChange(nextPage: number, nextSize: number): void {
  page.value = nextPage
  pageSize.value = nextSize
  void load()
}

function openDetail(row: NlExecutionRecord): void {
  detail.value = row
  detailOpen.value = true
}

function fmt(value: string | null): string {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function impactOf(row: NlExecutionRecord): { label: string; value: string }[] {
  const impact = row.plan?.impact
  return Array.isArray(impact) ? impact : []
}

function findingsOf(row: NlExecutionRecord): { level: string; source: string; message: string }[] {
  const findings = row.review?.findings
  return Array.isArray(findings) ? findings : []
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">操作审计</h2>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <p class="page-hint">
      每一行是"一句话"的完整档案：谁说的、说的什么、当时算出的影响、另一个复核方怎么看、
      最后落成了哪张单（或为什么没落成）。被否决与被驳回的记录同样保留 ——
      只记成功的审计等于把最该复盘的失败藏起来。
    </p>

    <div class="filters">
      <el-select
        v-model="filters.status"
        size="small"
        class="f-select"
        placeholder="全部状态"
        @change="search"
      >
        <el-option
          v-for="opt in STATUS_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-select
        v-model="filters.intent"
        size="small"
        class="f-select"
        placeholder="全部意图"
        @change="search"
      >
        <el-option label="全部意图" value="" />
        <el-option
          v-for="opt in intentOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-select
        v-model="filters.source"
        size="small"
        class="f-select-sm"
        placeholder="全部来源"
        @change="search"
      >
        <el-option
          v-for="opt in SOURCE_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-select
        v-model="filters.write"
        size="small"
        class="f-select-sm"
        placeholder="读写不限"
        @change="search"
      >
        <el-option label="读写不限" value="" />
        <el-option label="只看写操作" value="true" />
        <el-option label="只看只读" value="false" />
      </el-select>
      <el-date-picker
        v-model="filters.range"
        type="daterange"
        size="small"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="f-range"
        @change="search"
      />
      <el-button size="small" @click="reset">重置</el-button>
    </div>

    <el-alert v-if="error" type="error" show-icon :closable="false" class="mb-16" :title="error">
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      empty-text="还没有操作记录"
      empty-hint="在工作台底部用自然语言下一条会写数据的指令，这里就会出现它的档案。"
      table-size="small"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
    >
      <el-table-column label="时间" width="140">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>

      <el-table-column label="发起" width="120">
        <template #default="{ row }">
          <div class="who">{{ row.actorName || '-' }}</div>
          <div class="who-sub">{{ sourceLabel(row.source) }}</div>
        </template>
      </el-table-column>

      <el-table-column label="指令" min-width="260">
        <template #default="{ row }">
          <div class="cmd">{{ row.commandText }}</div>
          <el-tag size="small" effect="plain" class="intent-tag">{{ intentLabel(row.intent) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <span class="status" :class="`status--${statusTone(row.status)}`">
            {{ row.statusLabel }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="复核" width="112">
        <template #default="{ row }">
          <div>{{ verdictLabel(row.verdict) }}</div>
          <div class="who-sub">{{ row.reviewSource || '-' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="结果" min-width="200">
        <template #default="{ row }">
          <span v-if="row.docNo" class="doc-no">{{ row.docNo }}</span>
          <span v-else-if="row.errorMessage" class="err">{{ row.errorMessage }}</span>
          <span v-else class="who-sub">{{ row.status === 'PENDING' ? '等待人工确认' : '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="拍板" width="110">
        <template #default="{ row }">
          <div class="who-sub">{{ row.decidedByName || '-' }}</div>
          <div class="who-sub">{{ row.decidedAt ? fmt(row.decidedAt) : '' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="76" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <el-dialog
      v-model="detailOpen"
      :title="detail ? `操作记录 #${detail.id}` : '操作记录'"
      width="680px"
      @closed="detail = null"
    >
      <template v-if="detail">
        <p class="dlg-cmd">{{ detail.commandText }}</p>
        <p class="dlg-meta">
          {{ intentLabel(detail.intent) }} · {{ detail.statusLabel }} ·
          {{ detail.actorName || '-' }} · {{ fmt(detail.createdAt) }}
        </p>

        <div class="dlg-block">
          <div class="dlg-title">影响预演（当时算出的）</div>
          <div v-if="impactOf(detail).length" class="impact-grid">
            <div v-for="row in impactOf(detail)" :key="row.label" class="impact-row">
              <span class="impact-label">{{ row.label }}</span>
              <span class="impact-value">{{ row.value }}</span>
            </div>
          </div>
          <p v-else class="dlg-empty">这条记录没有留下影响预演。</p>
        </div>

        <div class="dlg-block">
          <div class="dlg-title">
            复核意见
            <span class="dlg-sub">{{ verdictLabel(detail.verdict) }} · {{ detail.reviewSource || '-' }}</span>
          </div>
          <ul v-if="findingsOf(detail).length" class="dlg-findings">
            <li v-for="(f, i) in findingsOf(detail)" :key="i">
              <span class="finding-src">
                {{ f.source === 'AI' ? 'AI' : f.source === 'SYSTEM' ? '系统' : '规则' }}
              </span>
              {{ f.message }}
            </li>
          </ul>
          <p v-else class="dlg-empty">复核没有提出异议。</p>
        </div>

        <div v-if="detail.docNo || detail.errorMessage" class="dlg-block">
          <div class="dlg-title">结果</div>
          <p v-if="detail.docNo" class="dlg-result">
            落成 {{ detail.docType === 'PURCHASE' ? '进货单' : '出货单' }}
            <span class="doc-no">{{ detail.docNo }}</span>
          </p>
          <p v-if="detail.errorMessage" class="err">{{ detail.errorMessage }}</p>
        </div>
      </template>

      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
}

.f-select {
  width: 132px;
}

.f-select-sm {
  width: 116px;
}

/*
 * el-date-picker 的根节点没有继承父组件的 scopeId（它的根是片段/多层包装），
 * 所以 `.f-range` 这样写在这个组件里根本不生效 —— 必须走 :deep 才能落到它的根节点上。
 * 这也是为什么第一版怎么改都是 838px 宽。
 */
.filters :deep(.f-range) {
  flex: 0 0 auto;
  width: 240px !important;
}

.who {
  font-size: 12.5px;
  color: var(--ys-mo-2);
}

.who-sub {
  font-size: 11px;
  color: var(--ys-mo-4);
}

.cmd {
  font-size: 12.5px;
  color: var(--ys-mo);
  word-break: break-all;
}

.intent-tag {
  margin-top: 4px;
  font-size: 10.5px;
}

.status {
  font-size: 12px;
  font-weight: 600;
}

.status--pending {
  color: var(--ys-taotu);
}

.status--ok {
  color: var(--ys-daiqing);
}

.status--bad {
  color: var(--ys-yanzhi);
}

.status--muted {
  color: var(--ys-mo-4);
}

.doc-no {
  font-family: var(--ys-font-num);
  font-size: 12px;
  color: var(--ys-daiqing);
}

.err {
  font-size: 12px;
  color: var(--ys-yanzhi);
}

.dlg-cmd {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ys-mo);
  word-break: break-all;
}

.dlg-meta {
  margin: 5px 0 14px;
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.dlg-block {
  margin-bottom: 14px;
}

.dlg-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ys-mo-2);
  margin-bottom: 6px;
}

.dlg-sub {
  margin-left: 8px;
  font-weight: 400;
  font-size: 11px;
  color: var(--ys-mo-4);
}

.impact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 2px 18px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--ys-track-wash);
}

.impact-row {
  display: flex;
  gap: 8px;
  font-size: 12px;
  padding: 2px 0;
}

.impact-label {
  flex: 0 0 56px;
  color: var(--ys-mo-4);
}

.impact-value {
  color: var(--ys-mo-2);
  font-variant-numeric: tabular-nums;
}

.dlg-findings {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.dlg-findings li {
  font-size: 12px;
  line-height: 1.55;
  color: var(--ys-mo-2);
}

.finding-src {
  flex: none;
  margin-right: 4px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--ys-warm-100);
  font-family: var(--ys-font-num);
  font-size: 10px;
  color: var(--ys-mo-4);
}

.dlg-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ys-mo-4);
}

.dlg-result {
  margin: 0;
  font-size: 12.5px;
  color: var(--ys-mo-2);
}
</style>
