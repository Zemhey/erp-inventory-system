<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageTable from '@/components/PageTable.vue'
import { nlQueueApi, type NlExecutionRecord } from '@/api/client'
import { intentLabel, sourceLabel, verdictLabel } from '@/utils/nl'

/**
 * 待确认队列（FR-V4-03 · S5）。
 *
 * ★ 这个页面存在的唯一理由：**看不见的待确认 = 没审。**
 *   自然语言指令在等人确认时，后端什么都没发生；如果只把"待确认"放在操作台那张卡片上，
 *   用户关掉页面、换个浏览器、或者第二天再看，那条计划就消失了——
 *   而它明明还挂在那里等一个决定。
 *
 * ★ 为什么这里没有"状态"列：能进队列的只有 PENDING。
 *   已执行 / 已驳回 / 被否决 / 已过期都在「操作审计」里看，
 *   两页各管一件事，比一页塞七种状态、再让人自己筛要清楚。
 *
 * ★ 为什么确认要二次弹窗：这一步会真的生成单据。
 *   页面上多点一下的成本，远小于"手滑建了一张单"的返工。
 */

const rows = ref<NlExecutionRecord[]>([])
const total = ref(0)
const pendingCount = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref('')
const detail = ref<NlExecutionRecord | null>(null)
/** 弹窗开关单独一个布尔：el-dialog 的 v-model 只认 boolean，直接绑对象在类型上就是错的。 */
const detailOpen = ref(false)
const acting = ref<number | null>(null)

function openDetail(row: NlExecutionRecord): void {
  detail.value = row
  detailOpen.value = true
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const res = await nlQueueApi.pending({ page: page.value, size: pageSize.value })
    rows.value = res.items
    total.value = res.total
    pendingCount.value = res.pendingCount
  } catch (e) {
    error.value = (e as Error).message || '加载待确认队列失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 确认执行：走 `/nl/pending/{id}/execute`，与操作台是同一条执行链路。 */
async function execute(row: NlExecutionRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认执行这条指令？<br><b>${escapeHtml(row.commandText)}</b><br><br>` +
        '确认后会真正生成单据草稿（仍不会审核 / 入库）。',
      '确认执行',
      { dangerouslyUseHTMLString: true, confirmButtonText: '确认执行', cancelButtonText: '再想想' },
    )
  } catch {
    return // 用户取消
  }
  acting.value = row.id
  try {
    const result = await nlQueueApi.execute(row.id)
    const docNo = (result.data as Record<string, unknown> | undefined)?.orderNo
    ElMessage.success(docNo ? `已生成 ${String(docNo)}` : '已执行')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '执行失败')
    await load()
  } finally {
    acting.value = null
  }
}

async function dismiss(row: NlExecutionRecord): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '驳回只是把这条计划注销，不会改动任何业务数据。驳回后它在审计里仍可查。',
      '驳回这条指令',
      { type: 'warning', confirmButtonText: '驳回', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  acting.value = row.id
  try {
    await nlQueueApi.dismiss(row.id)
    ElMessage.success('已驳回')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '驳回失败')
  } finally {
    acting.value = null
  }
}

function onPageChange(nextPage: number, nextSize: number): void {
  page.value = nextPage
  pageSize.value = nextSize
  void load()
}

function fmt(value: string | null): string {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 弹窗里要插 HTML，先把用户输入里的标签转义掉（指令本身可能含 < >）。 */
function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c,
  )
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
      <h2 class="page-title">待确认队列</h2>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <p class="page-hint">
      这里只放<strong>会产生数据</strong>的指令：查库存、看单据这类只读操作不会进来 ——
      读不审，查错了重问一次就好。每条计划默认 5 分钟内有效，过期自动作废；
      执行成功后仍只是草稿，审核与出入库要回业务页面做。
    </p>

    <div class="stat-row">
      <div class="stat">
        <span class="stat-value">{{ pendingCount }}</span>
        <span class="stat-label">待确认</span>
      </div>
      <span class="stat-note">
        发起与拍板不必是同一个人：你确认了别人发起的指令，档案里记的拍板人是你。
      </span>
    </div>

    <el-alert
      v-if="error"
      type="error"
      show-icon
      :closable="false"
      class="mb-16"
      :title="error"
    >
      <el-button size="small" @click="load">重试</el-button>
    </el-alert>

    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      empty-text="没有待确认的指令"
      empty-hint="在操作台下指令时，写操作会先停在计划卡上；那条计划也会出现在这里。"
      table-size="small"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
    >
      <el-table-column label="时间" width="110">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>

      <el-table-column label="发起" width="132">
        <template #default="{ row }">
          <div class="who">{{ row.actorName || '-' }}</div>
          <div class="who-sub">{{ sourceLabel(row.source) }}</div>
        </template>
      </el-table-column>

      <el-table-column label="指令" min-width="300">
        <template #default="{ row }">
          <div class="cmd">{{ row.commandText }}</div>
          <el-tag size="small" effect="plain" class="intent-tag">{{ intentLabel(row.intent) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="复核" width="120">
        <template #default="{ row }">
          <span class="verdict" :class="`verdict--${(row.verdict || '').toLowerCase()}`">
            {{ verdictLabel(row.verdict) }}
          </span>
          <div class="who-sub">{{ row.reviewSource || '-' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="摘要" min-width="260">
        <template #default="{ row }">{{ row.summary || '-' }}</template>
      </el-table-column>

      <el-table-column label="操作" width="188" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button
            link
            type="success"
            :loading="acting === row.id"
            @click="execute(row)"
          >确认执行</el-button>
          <el-button link type="danger" :disabled="acting === row.id" @click="dismiss(row)">
            驳回
          </el-button>
        </template>
      </el-table-column>
    </PageTable>

    <!-- 详情：把"将要发生什么"和"另一个复核方怎么看"摆全，避免人凭一句话拍板 -->
    <el-dialog
      v-model="detailOpen"
      :title="detail ? `计划详情 #${detail.id}` : '计划详情'"
      width="640px"
      @closed="detail = null"
    >
      <template v-if="detail">
        <p class="dlg-cmd">{{ detail.commandText }}</p>
        <p class="dlg-summary">{{ detail.summary }}</p>

        <div class="dlg-block">
          <div class="dlg-title">影响预演</div>
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
            <span class="dlg-sub">{{ verdictLabel(detail.verdict) }} · {{ detail.reviewSource }}</span>
          </div>
          <ul v-if="findingsOf(detail).length" class="dlg-findings">
            <li v-for="(f, i) in findingsOf(detail)" :key="i">
              <span class="finding-src">{{ f.source === 'AI' ? 'AI' : f.source === 'SYSTEM' ? '系统' : '规则' }}</span>
              {{ f.message }}
            </li>
          </ul>
          <p v-else class="dlg-empty">复核没有提出异议。</p>
        </div>

        <p class="dlg-meta">
          发起 {{ detail.actorName || '-' }} · {{ fmt(detail.createdAt) }} ·
          {{ sourceLabel(detail.source) }} · 有效至 {{ fmt(detail.expiresAt) }}
        </p>
      </template>

      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-button v-if="detail" type="danger" plain @click="dismiss(detail); detailOpen = false">
          驳回
        </el-button>
        <el-button v-if="detail" type="primary" @click="execute(detail); detailOpen = false">
          确认执行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin: 4px 0 12px;
}

.stat-value {
  font-family: var(--ys-font-num);
  font-size: 26px;
  font-weight: 600;
  color: var(--ys-daiqing);
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 12px;
  color: var(--ys-mo-3);
}

.stat-note {
  font-size: 11.5px;
  color: var(--ys-mo-4);
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

.verdict {
  font-size: 12px;
  font-weight: 600;
}

.verdict--approve {
  color: var(--ys-daiqing);
}

.verdict--need_human {
  color: var(--ys-taotu);
}

.verdict--reject {
  color: var(--ys-yanzhi);
}

.dlg-cmd {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ys-mo);
  word-break: break-all;
}

.dlg-summary {
  margin: 6px 0 14px;
  font-size: 12.5px;
  color: var(--ys-mo-2);
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

.dlg-meta {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--ys-mo-4);
}
</style>
