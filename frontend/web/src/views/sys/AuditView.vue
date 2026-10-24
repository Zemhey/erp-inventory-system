<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import PageTable from '@/components/PageTable.vue'
import {
  AUDIT_ACTIONS,
  AUDIT_RESULTS,
  auditActionLabel,
  auditApi,
  type AuditActionCode,
  type AuditLog,
  type AuditResultCode,
} from '@/api/auditDict'
import { diffSnapshots, diffSummary, prettyJson, type SnapshotField } from '@/utils/auditSnapshot'

/**
 * 审计查询（FR-SYS-07）。
 *
 * ★ 这一页存在的理由是**变更对照**，不是列表：
 *   "谁在什么时候动了哪张单"列表能回答，但"他把数量从 8 改成了 5"只有
 *   before/after 并排才看得出来。所以详情抽屉把两段 JSON 摊平成字段行、
 *   按 新增/删除/修改/未变 上色，而不是把 JSON 贴上去让用户自己找。
 *
 * ★ 界面**不解释业务语义**（计划书 §0）：
 *   动作怎么归类、快照取什么字段、哪些键要脱敏，全在后端；这里只显示。
 *   后端已把 token / password 遮成 ***，界面若再自己遮一次，两处规则迟早分叉。
 *
 * ★ 只读：这一页没有任何写接口可调（见 api/auditDict.ts）。审计表是追加型的，
 *   数据库触发器拒绝 UPDATE / DELETE —— 所以页面上不会提供"清理""修正"入口，
 *   那类按钮在这个系统里必然是无效的。
 */

// ─────────────────────────── 列表 ───────────────────────────

const filters = reactive({
  actorName: '',
  bizRefNo: '',
  action: '' as AuditActionCode | '',
  result: '' as AuditResultCode | '',
  /**
   * 资源维度：主数据（供应商 / 商品 / SKU…）的审计**没有** bizRefNo
   * （后端刻意不把主数据编码当单据号），所以要定位一条主数据变更的历史，
   * 只能靠 resourceType + resourceId。缺了这两个条件，主数据审计就只能按人翻。
   */
  resourceType: '',
  resourceId: '',
  /** el-date-picker 给 Date；发给后端前转 ISO-8601（半开区间 [from, to)） */
  range: null as [Date, Date] | null,
})

const rows = ref<AuditLog[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const error = ref('')

const filtered = computed(
  () =>
    filters.actorName.trim() !== '' ||
    filters.bizRefNo.trim() !== '' ||
    filters.resourceType.trim() !== '' ||
    filters.resourceId.trim() !== '' ||
    filters.action !== '' ||
    filters.result !== '' ||
    filters.range !== null,
)

/** 列表页每行的变更概览：让"这条记录值不值得点开"在列表上就能判断。 */
const summaries = computed(() => {
  const map = new Map<number, string>()
  for (const row of rows.value) {
    map.set(row.id, diffSummary(diffSnapshots(row.beforeJson, row.afterJson)))
  }
  return map
})

/**
 * 只发非空条件。
 *
 * 空字符串会被后端当"不过滤"（`isBlank()` 判空），但让 URL 上挂一串
 * `actorName=&bizRefNo=` 会把 traceId 之类的日志搅乱，也不利于排查"到底发了什么"。
 */
function queryParams() {
  return {
    actorName: filters.actorName.trim() || undefined,
    bizRefNo: filters.bizRefNo.trim() || undefined,
    resourceType: filters.resourceType.trim() || undefined,
    resourceId: filters.resourceId.trim() || undefined,
    action: filters.action || undefined,
    result: filters.result || undefined,
    from: filters.range?.[0]?.toISOString(),
    to: filters.range?.[1]?.toISOString(),
  }
}

function errorMessage(cause: unknown): string {
  const e = cause as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || '请求失败'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await auditApi.list({ ...queryParams(), page: page.value, size: pageSize.value })
    rows.value = data.items
    total.value = data.total
  } catch (cause) {
    rows.value = []
    total.value = 0
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function resetFilters() {
  filters.actorName = ''
  filters.bizRefNo = ''
  filters.resourceType = ''
  filters.resourceId = ''
  filters.action = ''
  filters.result = ''
  filters.range = null
  search()
}

function onPageChange(nextPage: number, nextSize: number) {
  page.value = nextPage
  pageSize.value = nextSize
  load()
}

onMounted(load)

// ─────────────────────────── 详情抽屉 ───────────────────────────

const detailVisible = ref(false)
const detail = ref<AuditLog | null>(null)
const detailError = ref('')
const onlyDiff = ref(false)

const diff = computed(() =>
  detail.value ? diffSnapshots(detail.value.beforeJson, detail.value.afterJson) : null,
)

const visibleFields = computed<SnapshotField[]>(() => {
  const fields = diff.value?.fields ?? []
  return onlyDiff.value ? fields.filter((f) => f.state !== 'unchanged') : fields
})

/**
 * 打开详情：先用列表行立即渲染，再拉一次 `GET /audits/{id}`。
 *
 * 为什么不"只调详情接口"：点了才转圈，列表已有的数据白白浪费。
 * 为什么仍然要调：详情接口才是契约里的单条读路径，
 * 它挂了（404 / 权限）必须在页面上看得见，而不是被列表缓存掩盖成"正常"。
 */
async function openDetail(row: AuditLog) {
  detail.value = row
  detailError.value = ''
  onlyDiff.value = true
  detailVisible.value = true
  try {
    detail.value = await auditApi.get(row.id)
  } catch (cause) {
    detailError.value = errorMessage(cause)
  }
}

const beforePretty = computed(() => prettyJson(detail.value?.beforeJson ?? null))
const afterPretty = computed(() => prettyJson(detail.value?.afterJson ?? null))

function fieldRowClass({ row }: { row: SnapshotField }): string {
  return `diff-${row.state}`
}

// ─────────────────────────── 轨迹 / traceId ───────────────────────────

const traceVisible = ref(false)
const traceTitle = ref('')
const traceHint = ref('')
const traceRows = ref<AuditLog[]>([])
const traceLoading = ref(false)
const traceError = ref('')

async function openTrace(kind: 'timeline' | 'trace', value: string) {
  if (!value) return
  traceLoading.value = true
  traceError.value = ''
  traceTitle.value = kind === 'timeline' ? `单据 ${value} 的审计轨迹` : `traceId ${value} 的审计`
  traceHint.value =
    kind === 'timeline'
      ? '按发生时间正序：建单在前，之后每次动作排成这张单的经历。'
      : '同一次请求产生的全部审计（按写入顺序）：一次请求可能动多个资源。'
  traceRows.value = []
  traceVisible.value = true
  try {
    traceRows.value =
      kind === 'timeline' ? await auditApi.timeline(value) : await auditApi.byTrace(value)
  } catch (cause) {
    traceError.value = errorMessage(cause)
  } finally {
    traceLoading.value = false
  }
}

// ─────────────────────────── 显示辅助（纯文案） ───────────────────────────

function dateTimeText(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

const RESULT_LABELS: Record<string, string> = { SUCCESS: '成功', FAIL: '失败' }

function resultLabel(code: string): string {
  return RESULT_LABELS[code] ?? code
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">审计查询</h2>
      <el-button @click="load">刷新</el-button>
    </div>
    <p class="page-hint">
      只读页面：这里只能查，不能改也改不了。审计表是追加型的，数据库触发器拒绝任何
      UPDATE / DELETE，所以本页不提供"修正""清理"入口。动作归类、快照射影与敏感字段脱敏都在后端完成。
    </p>
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      class="mb-16"
      title="能看到全部操作记录，当前不按仓库或本人过滤"
      description="拥有 sys:read 的身份即可查询全公司的审计；按数据权限收窄（只看本人 / 只看本仓）尚未实现。给读权限前请确认这一点可以接受。"
    />

    <PageTable
      :rows="rows"
      :loading="loading"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :filtered="filtered"
      :load-failed="!!error"
      empty-text="还没有审计记录"
      empty-hint="审计只覆盖写操作（POST / PUT / PATCH / DELETE）；查询本身不留痕，否则审计表会被查询淹没。"
      table-size="small"
      @update:page="page = $event"
      @update:page-size="pageSize = $event"
      @change="onPageChange"
    >
      <template #filters>
        <el-input
          v-model="filters.actorName"
          placeholder="操作人（精确匹配）"
          clearable
          class="filter-input"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="filters.bizRefNo"
          placeholder="业务单据号（精确匹配）"
          clearable
          class="filter-input"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="filters.resourceType"
          placeholder="资源类型（如 SUPPLIER）"
          clearable
          class="filter-input-sm"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="filters.resourceId"
          placeholder="资源 ID"
          clearable
          class="filter-input-sm"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select
          v-model="filters.action"
          placeholder="全部操作类型"
          clearable
          class="filter-select"
          @change="search"
        >
          <el-option
            v-for="code in AUDIT_ACTIONS"
            :key="code"
            :label="`${auditActionLabel(code)}（${code}）`"
            :value="code"
          />
        </el-select>
        <el-select
          v-model="filters.result"
          placeholder="全部结果"
          clearable
          class="filter-select-sm"
          @change="search"
        >
          <el-option
            v-for="code in AUDIT_RESULTS"
            :key="code"
            :label="resultLabel(code)"
            :value="code"
          />
        </el-select>
        <el-date-picker
          v-model="filters.range"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间（不含）"
          format="YYYY-MM-DD HH:mm"
          class="filter-daterange"
          @change="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </template>

      <template #header>
        <div class="table-title">
          操作审计
          <span class="text-muted">共 {{ total }} 条</span>
        </div>
        <div class="text-muted">按发生时间倒序；点「单据号」看这张单的轨迹，点「traceId」看这次请求的全部审计。</div>
      </template>

      <el-table-column label="发生时间" width="150">
        <template #default="{ row }">{{ dateTimeText(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="110">
        <template #default="{ row }">
          <span v-if="row.actorName">{{ row.actorName }}</span>
          <el-tag v-else type="info" size="small">匿名</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="动作 / 变更" width="130">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ auditActionLabel(row.action) }}</el-tag>
          <div class="change-hint">{{ summaries.get(row.id) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="资源" min-width="130">
        <template #default="{ row }">
          <span>{{ row.resourceType }}</span>
          <span v-if="row.resourceId" class="text-muted"> #{{ row.resourceId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="单据号" min-width="150">
        <template #default="{ row }">
          <el-button v-if="row.bizRefNo" link type="primary" @click="openTrace('timeline', row.bizRefNo)">
            {{ row.bizRefNo }}
          </el-button>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ resultLabel(row.result) }}
          </el-tag>
          <div v-if="row.errorCode" class="error-code">{{ row.errorCode }}</div>
        </template>
      </el-table-column>
      <el-table-column label="traceId" min-width="130">
        <template #default="{ row }">
          <el-button v-if="row.traceId" link type="primary" @click="openTrace('trace', row.traceId)">
            {{ row.traceId }}
          </el-button>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </PageTable>

    <!-- ─────────── 详情：变更对照 ─────────── -->
    <el-drawer v-model="detailVisible" size="72%" :title="`审计详情 #${detail?.id ?? ''}`">
      <el-alert
        v-if="detailError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
        :title="`读取单条审计失败：${detailError}`"
      >
        <el-button size="small" @click="detail && openDetail(detail)">重试</el-button>
      </el-alert>

      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="mb-16">
          <el-descriptions-item label="发生时间">{{ dateTimeText(detail.occurredAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">
            {{ detail.actorName ?? '匿名' }}
            <span v-if="detail.actorId" class="text-muted">（id {{ detail.actorId }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="动作">
            {{ auditActionLabel(detail.action) }}
            <span class="text-muted">（{{ detail.action }}）</span>
          </el-descriptions-item>
          <el-descriptions-item label="结果">
            <el-tag :type="detail.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ resultLabel(detail.result) }}
            </el-tag>
            <span v-if="detail.errorCode" class="error-code">{{ detail.errorCode }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="资源">
            {{ detail.resourceType }}{{ detail.resourceId ? ` #${detail.resourceId}` : '' }}
          </el-descriptions-item>
          <el-descriptions-item label="单据号">
            {{ detail.bizRefNo ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="traceId">{{ detail.traceId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="来源 IP">{{ detail.ip ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="diff-toolbar">
          <span class="diff-title">变更前 / 变更后对照</span>
          <span v-if="diff" class="text-muted">{{ diffSummary(diff) }}</span>
          <el-checkbox v-model="onlyDiff">只看差异字段</el-checkbox>
        </div>

        <el-alert
          v-if="diff?.beforeMissing"
          type="info"
          show-icon
          :closable="false"
          class="mb-16"
          title="没有变更前快照"
          description="两种成因界面无法区分：① 该资源此前不存在（首次创建，契约如此约定）；② 这次动作的路径不带资源 id（如 /submit、/approve），后端取不到上一次快照。判断这次动作改了什么的另一种办法：按 traceId 看同请求的其余审计，或看本条的变更后快照。"
        />
        <el-alert
          v-if="diff?.afterMissing"
          type="info"
          show-icon
          :closable="false"
          class="mb-16"
          title="没有变更后快照"
          description="后端对超大响应不截断、直接不记（截断会产出非法 JSON，让快照不可还原）。这条审计仍然证明了动作与结果。"
        />
        <el-alert
          v-if="diff && (diff.rawText.before || diff.rawText.after)"
          type="warning"
          show-icon
          :closable="false"
          class="mb-16"
          title="快照片段不是可解析的 JSON，已按原文显示"
          description="下面用字段对照的方式无法展开，请直接用原文视图核对；界面不会把它当成「没有变更」。"
        />

        <el-table
          v-if="diff && diff.fields.length"
          :data="visibleFields"
          row-key="path"
          size="small"
          border
          :row-class-name="fieldRowClass"
          class="diff-table"
        >
          <template #empty>
            <div class="empty-block">
              <div class="empty-title">没有差异字段</div>
              <div class="empty-subtitle">
                这次操作留下了审计，但前后快照的字段值完全一致（例如幂等重试或只改状态不改内容）。
              </div>
            </div>
          </template>
          <el-table-column label="字段" min-width="180">
            <template #default="{ row }">
              <code class="field-path">{{ row.path }}</code>
            </template>
          </el-table-column>
          <el-table-column label="变更前" min-width="200">
            <template #default="{ row }">
              <span v-if="row.before === null" class="text-muted">（无此字段）</span>
              <span v-else class="cell-value">{{ row.before }}</span>
            </template>
          </el-table-column>
          <el-table-column label="变更后" min-width="200">
            <template #default="{ row }">
              <span v-if="row.after === null" class="text-muted">（已移除）</span>
              <span v-else class="cell-value">{{ row.after }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-collapse class="mb-16 raw-collapse">
          <el-collapse-item title="原文快照片段">
            <div class="raw-block">
              <div class="raw-label">before_json</div>
              <pre class="raw-json">{{ beforePretty || '（空）' }}</pre>
            </div>
            <div class="raw-block">
              <div class="raw-label">after_json</div>
              <pre class="raw-json">{{ afterPretty || '（空）' }}</pre>
            </div>
          </el-collapse-item>
        </el-collapse>

        <div class="drawer-actions">
          <el-button
            :disabled="!detail.bizRefNo"
            @click="detail.bizRefNo && openTrace('timeline', detail.bizRefNo)"
          >
            看该单据的轨迹
          </el-button>
          <el-button
            :disabled="!detail.traceId"
            @click="detail.traceId && openTrace('trace', detail.traceId)"
          >
            按 traceId 看时间线
          </el-button>
        </div>
      </template>
    </el-drawer>

    <!-- ─────────── 轨迹 / 同 traceId 审计 ─────────── -->
    <el-dialog v-model="traceVisible" :title="traceTitle" width="80%">
      <p class="page-hint">{{ traceHint }}</p>
      <el-alert
        v-if="traceError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
        :title="`查询失败：${traceError}`"
      />
      <el-table v-if="!traceLoading && !traceError" :data="traceRows" size="small" border>
        <template #empty>
          <div class="empty-block">
            <div class="empty-title">这里没有审计</div>
            <div class="empty-subtitle">
              单据号或 traceId 拼错时也会是空 —— 本页按精确值匹配，不做模糊查询。
            </div>
          </div>
        </template>
        <el-table-column label="#" width="80" prop="id" />
        <el-table-column label="发生时间" width="170">
          <template #default="{ row }">{{ dateTimeText(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="140">
          <template #default="{ row }">{{ row.actorName ?? '匿名' }}</template>
        </el-table-column>
        <el-table-column label="动作" width="120">
          <template #default="{ row }">{{ auditActionLabel(row.action) }}</template>
        </el-table-column>
        <el-table-column label="资源" min-width="150">
          <template #default="{ row }">{{ row.resourceType }}{{ row.resourceId ? ` #${row.resourceId}` : '' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ resultLabel(row.result) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="empty-block">加载中…</div>
      <div class="text-muted trace-count">共 {{ traceRows.length }} 条</div>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-input {
  width: 200px;
}

.filter-input-sm {
  width: 160px;
}

.filter-select {
  width: 190px;
}

.filter-select-sm {
  width: 130px;
}

.filter-daterange {
  width: 340px;
}

.table-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-weight: 600;
}

.error-code {
  margin-top: 2px;
  font-size: 12px;
  color: #f56c6c;
}

.change-hint {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.3;
  color: #909399;
}

.diff-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.diff-title {
  font-weight: 600;
}

.field-path {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #303133;
  word-break: break-all;
}

.cell-value {
  word-break: break-all;
}

/* 差异靠颜色说清"哪一侧变了"：删除只在左侧有值，新增只在右侧有值 */
.diff-table :deep(.diff-changed) {
  background-color: #fdf6ec;
}

.diff-table :deep(.diff-removed) td:first-child {
  background-color: #fef0f0;
}

.diff-table :deep(.diff-removed) .cell-value {
  color: #f56c6c;
  text-decoration: line-through;
}

.diff-table :deep(.diff-added) td:last-child {
  background-color: #f0f9eb;
}

.diff-table :deep(.diff-added) .cell-value {
  color: #67c23a;
  font-weight: 600;
}

.diff-table :deep(.diff-unchanged) {
  color: #909399;
}

.raw-collapse {
  margin-top: 16px;
}

.raw-block + .raw-block {
  margin-top: 12px;
}

.raw-label {
  font-size: 12px;
  color: #909399;
}

.raw-json {
  max-height: 260px;
  overflow: auto;
  margin: 4px 0 0;
  padding: 8px;
  background-color: #f5f7fa;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.trace-count {
  margin-top: 8px;
  text-align: right;
}

@media (max-width: 768px) {
  .filter-input,
  .filter-input-sm,
  .filter-select,
  .filter-select-sm,
  .filter-daterange {
    width: 100%;
  }
}
</style>
