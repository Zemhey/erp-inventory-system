<script setup lang="ts">
/**
 * 报表外壳（FR-V3-31 框架 + FR-V3-34 导出打印）。
 *
 * ★ 一张报表 = 一份 ReportDefinition，这里负责所有重复劳动：
 *   条件区渲染、期间预设与联动、加载态、空状态、错误重试、
 *   千分位格式化、合计行、汇总指标、SVG 图表、CSV 导出、打印。
 *
 * ★ 三条刻意的设计：
 *   1. 「查询条件未提交」与「提交后查到空集」分开显示 —— 前者提示可以点查询，
 *      后者提示换条件，两者文案不同（计划书 §3.1 的老坑）。
 *   2. 错误态不吞后端 message：后端契约要求错误消息含定位信息，
 *      groupBy / type 传错时 400 的 message 里就列着允许值，直接显示最有用。
 *   3. 导出优先走服务端端点（导出 == 页面），后端没有 /export 的报表
 *      才用当前结果集本地生成，且一律补 UTF-8 BOM。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Printer, Refresh, Search } from '@element-plus/icons-vue'
import { mdmApi, type Sku, type Product, type Warehouse } from '@/api/client'
import { downloadReportCsv } from '@/api/report'
import { useSessionStore } from '@/stores/session'
import MiniChart from './MiniChart.vue'
import { PRESET_LABELS, PRESET_ORDER, isoDate, isoMonth, presetRange, type RangePreset } from '../core/dates'
import { buildCsv, downloadTextCsv, formatCell } from '../core/format'
import { defaultValues, syncPeriodAndRange } from '../core/registry'
import { columnValue, type ReportColumn, type ReportDefinition, type ReportParam, type Row } from '../core/types'

const props = defineProps<{ definition: ReportDefinition }>()

const session = useSessionStore()

const values = ref<Record<string, string | number | undefined>>({})
const rows = ref<Row[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const dirty = ref(false)
const lastRunAt = ref<string | null>(null)
const chartOpen = ref(true)

const warehouses = ref<Warehouse[]>([])
const dictTruncated = ref(false)
const dictLoaded = ref(false)

const needsDict = computed(() => props.definition.params.some((p) => p.kind === 'warehouse') || props.definition.needsSkuDict)
const canReadMaster = computed(() => session.hasPermission('mdm:read'))

const warehouseOptions = computed(() =>
  warehouses.value.map((w) => ({ value: w.id, label: w.enabled ? w.name : `${w.name}（已停用）` })),
)

// ─────────────────────────── 条件区 ───────────────────────────

function reset(): void {
  values.value = defaultValues(props.definition)
  dirty.value = false
}

/** 当前命中的期间预设（手工改日期后回到「自定义」）。 */
const activePreset = ref<RangePreset | 'custom'>('last30')

function applyPreset(preset: RangePreset, pair: [string, string], run = true): void {
  activePreset.value = preset
  const [from, to] = presetRange(preset)
  values.value = { ...values.value, [pair[0]]: from, [pair[1]]: to }
  dirty.value = true
  if (run) void runQuery()
}

/**
 * 条件变更 → 联动。
 *
 * ★ 两层：框架层的「期间 ↔ 区间」互相跟随，报表层再跑自己的 linkage。
 *   例如经营报表切「按月」把区间对齐整月、呆滞表改阈值把下限一起带走。
 *
 * ★ pair 型条件（日期区间 / 账期区间）要显式传 changedKey —— 否则框架分不清
 *   用户改的是区间头还是区间尾，联动会算错方向。
 */
function onParamChange(param: ReportParam, raw: unknown, changedKey?: string): void {
  const key = changedKey ?? param.key
  const next = { ...values.value }
  next[key] = raw === '' || raw == null ? undefined : (raw as string | number)
  if (param.pair) activePreset.value = 'custom'
  values.value = props.definition.linkage
    ? props.definition.linkage(syncPeriodAndRange(next, key), key)
    : syncPeriodAndRange(next, key)
  dirty.value = true
  // 单选/数字/快照日改完即查；日期区间要两头都填完，交给「查询」按钮
  if (['select', 'warehouse', 'period', 'date'].includes(param.kind)) void runQuery()
}

function paramModel(param: ReportParam): string | number | undefined {
  if (param.kind === 'daterange' && param.pair) return values.value[param.pair[0]]
  if (param.kind === 'periodrange' && param.pair) return values.value[param.pair[1]]
  return values.value[param.key]
}

function paramModel2(param: ReportParam): string | number | undefined {
  return param.pair ? values.value[param.pair[1]] : undefined
}

// ─────────────────────────── 查询 ───────────────────────────

async function ensureDictionary(): Promise<{ skus: { id: number; code: string; name: string }[] }> {
  if (!needsDict.value || !canReadMaster.value) return { skus: [] }
  if (dictLoaded.value) return { skus: skuIndex.value }
  const [wh, sku, prod] = await Promise.allSettled([
    mdmApi.listWarehouses(false),
    mdmApi.listSkus({ page: 1, pageSize: 200 }),
    mdmApi.listProducts({ page: 1, size: 200 }),
  ])
  if (wh.status === 'fulfilled') warehouses.value = wh.value
  const skuRows: Sku[] = sku.status === 'fulfilled' ? sku.value.items : []
  const products: Product[] = prod.status === 'fulfilled' ? prod.value.items : []
  const productNames = new Map<number, string>(products.map((p) => [p.id, p.name]))
  skuIndex.value = skuRows.map((s) => ({
    id: s.id,
    code: s.barcode?.trim() || `SKU#${s.id}`,
    // SKU 本身没有 name，业务上的「名称」是 商品名 · 颜色-尺码
    name: `${productNames.get(s.productId) ?? `商品#${s.productId}`} · ${s.color}-${s.size}`,
  }))
  dictTruncated.value =
    (sku.status === 'fulfilled' && sku.value.total > skuRows.length) ||
    (prod.status === 'fulfilled' && prod.value.total > products.length)
  dictLoaded.value = true
  return { skus: skuIndex.value }
}

const skuIndex = ref<{ id: number; code: string; name: string }[]>([])

async function runQuery(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const dict = await ensureDictionary()
    rows.value = await props.definition.load({
      values: values.value,
      warehouses: warehouses.value.map((w) => ({ id: w.id, name: w.name })),
      skus: dict.skus,
      dictTruncated: dictTruncated.value,
    })
    lastRunAt.value = isoDate(new Date()) + ' ' + new Date().toLocaleTimeString('zh-CN', { hour12: false })
    dirty.value = false
  } catch (e) {
    rows.value = []
    error.value = e instanceof Error ? e.message : '查询失败'
  } finally {
    loading.value = false
  }
}

function retry(): void {
  void runQuery()
}

// ─────────────────────────── 合计行 ───────────────────────────

const columns = computed(() => props.definition.columns)

function columnRaw(col: (typeof columns.value)[number], row: Row): unknown {
  return columnValue(col, row)
}

/** 负数标红：恒等式差额、四桶差额这类列最常见的错误信号。 */
function isNegative(col: ReportColumn, row: Row): boolean {
  if (col.kind === 'bool' || col.kind === 'text') return false
  const n = Number(columnRaw(col, row) ?? 0)
  return Number.isFinite(n) && n < 0
}

function sumColumn(index: number): number {
  const col = columns.value[index]
  let total = 0
  for (const row of rows.value) {
    const n = Number(columnRaw(col, row) ?? 0)
    if (Number.isFinite(n)) total += n
  }
  return total
}

/** 表尾合计：money/qty/int 求和，text 列标「合计」，rate/days/bool 不算（口径不同）。 */
function summaryMethod(info: { columns: unknown[]; data: unknown[] }): string[] {
  const out: string[] = []
  columns.value.forEach((col, i) => {
    if (col.total === 'sum') out.push(formatCell(col.kind, sumColumn(i)))
    else if (col.total === 'count') out.push(`共 ${info.data.length} 条`)
    else out.push(i === 0 ? '合计' : '')
  })
  return out
}

// ─────────────────────────── 图表 ───────────────────────────

const chartData = computed(() => {
  const chart = props.definition.chart
  if (!chart || !chartOpen.value) return null
  let pool = rows.value
  if (chart.top) pool = pool.slice(0, chart.top)
  if (pool.length === 0) return null
  return {
    type: chart.type,
    title: chart.title,
    unit: chart.unit,
    categories: pool.map((r) => chart.category(r)),
    series: chart.series.map((s) => ({ name: s.name, color: s.color, values: pool.map((r) => Number(s.value(r) ?? 0)) })),
  }
})

// ─────────────────────────── 导出 / 打印 ───────────────────────────

/** 发给服务端导出端点的条件（缺省按声明的条件原样带上）。 */
function exportParams(): Record<string, string | number | undefined> {
  const csv = props.definition.csv
  if (csv.kind === 'server' && csv.params) {
    return csv.params({
      values: values.value,
      warehouses: warehouses.value.map((w) => ({ id: w.id, name: w.name })),
      skus: skuIndex.value,
      dictTruncated: dictTruncated.value,
    })
  }
  const out: Record<string, string | number | undefined> = {}
  for (const param of props.definition.params) {
    if (param.kind === 'daterange' || param.kind === 'periodrange') {
      if (!param.pair) continue
      if (param.pair[0] !== '__preset') out[param.pair[0]] = values.value[param.pair[0]]
      out[param.pair[1]] = values.value[param.pair[1]]
    } else if (param.key !== '__preset') {
      out[param.key] = values.value[param.key]
    }
  }
  return out
}

const exporting = ref(false)

async function exportCsv(): Promise<void> {
  const csv = props.definition.csv
  if (rows.value.length === 0) {
    ElMessage.warning('当前没有结果可导出')
    return
  }
  exporting.value = true
  try {
    if (csv.kind === 'server') {
      await downloadReportCsv(csv.path, exportParams(), csv.fallbackName)
      ElMessage.success(`已导出 ${csv.fallbackName}`)
      return
    }
    const header = columns.value.map((c) => c.label)
    const body = rows.value.map((row) => columns.value.map((c) => formatCell(c.kind, columnRaw(c, row)).replace(/,/g, '')))
    const stamp = isoMonth(new Date())
    downloadTextCsv(`${csv.prefix}-${stamp}.csv`, buildCsv(header, body))
    ElMessage.success(`已导出 ${rows.value.length} 行`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败')
  } finally {
    exporting.value = false
  }
}

function print(): void {
  window.print()
}

function reloadDictionary(): void {
  dictLoaded.value = false
}

// ─────────────────────────── 生命周期 ───────────────────────────

watch(
  () => props.definition.id,
  () => {
    reset()
    activePreset.value = 'last30'
    void runQuery()
  },
)

onMounted(() => {
  reset()
  void runQuery()
})

defineExpose({ reloadDictionary })
</script>

<template>
  <section class="report-shell">
    <header class="shell-head">
      <div class="shell-head-text">
        <h2 class="shell-title">{{ definition.title }}</h2>
        <p v-if="definition.subtitle" class="shell-subtitle">{{ definition.subtitle }}</p>
      </div>
      <div class="shell-meta no-print">
        <el-tag v-if="definition.csv.kind === 'local'" type="warning" effect="plain" size="small">
          前端 CSV（后端无 /export 端点）
        </el-tag>
        <span v-if="lastRunAt" class="shell-run-at">最近查询 {{ lastRunAt }}</span>
      </div>
    </header>

    <!-- ★ 口径说明：后端类注释要求页面照抄展示，这里不做折叠、不做 tooltip -->
    <p class="shell-caliber">{{ definition.caliber }}</p>

    <div class="param-area no-print">
      <div v-for="param in definition.params" :key="param.key" class="param-item">
        <span class="param-label">{{ param.label }}</span>

        <!-- 日期区间 + 期间预设（预设点一下就查，是「联动」的入口） -->
        <template v-if="param.kind === 'daterange' && param.pair">
          <div class="param-inline">
            <el-button-group class="preset-group">
              <el-button
                v-for="preset in PRESET_ORDER"
                :key="preset"
                size="small"
                :type="activePreset === preset ? 'primary' : 'default'"
                @click="applyPreset(preset, param.pair as [string, string])"
              >
                {{ PRESET_LABELS[preset] }}
              </el-button>
            </el-button-group>
            <el-date-picker
              :model-value="paramModel(param)"
              type="date"
              value-format="YYYY-MM-DD"
              size="small"
              placeholder="开始"
              @update:model-value="(v: unknown) => onParamChange(param, v, param.pair?.[0])"
            />
            <span class="dash">至</span>
            <el-date-picker
              :model-value="paramModel2(param)"
              type="date"
              value-format="YYYY-MM-DD"
              size="small"
              placeholder="结束"
              @update:model-value="(v: unknown) => onParamChange(param, v, param.pair?.[1])"
            />
          </div>
        </template>

        <template v-else-if="param.kind === 'periodrange' && param.pair">
          <div class="param-inline">
            <el-date-picker
              :model-value="values[param.pair[0]]"
              type="month"
              value-format="YYYY-MM"
              size="small"
              placeholder="起始账期"
              @update:model-value="(v: unknown) => onParamChange(param, v, param.pair?.[0])"
            />
            <span class="dash">至</span>
            <el-date-picker
              :model-value="paramModel2(param)"
              type="month"
              value-format="YYYY-MM"
              size="small"
              placeholder="结束账期"
              @update:model-value="(v: unknown) => onParamChange(param, v, param.pair?.[1])"
            />
          </div>
        </template>

        <el-date-picker
          v-else-if="param.kind === 'period'"
          :model-value="paramModel(param)"
          type="month"
          value-format="YYYY-MM"
          size="small"
          @update:model-value="(v: unknown) => onParamChange(param, v)"
        />

        <el-date-picker
          v-else-if="param.kind === 'date'"
          :model-value="paramModel(param)"
          type="date"
          value-format="YYYY-MM-DD"
          size="small"
          @update:model-value="(v: unknown) => onParamChange(param, v)"
        />

        <el-select
          v-else-if="param.kind === 'select'"
          :model-value="paramModel(param)"
          size="small"
          style="width: 160px"
          @update:model-value="(v: unknown) => onParamChange(param, v)"
        >
          <el-option v-for="opt in param.options ?? []" :key="String(opt.value)" :label="opt.label" :value="opt.value" />
        </el-select>

        <el-select
          v-else-if="param.kind === 'warehouse'"
          :model-value="paramModel(param)"
          size="small"
          clearable
          filterable
          style="width: 180px"
          placeholder="全部仓库"
          @update:model-value="(v: unknown) => onParamChange(param, v)"
        >
          <el-option v-for="opt in warehouseOptions" :key="String(opt.value)" :label="opt.label" :value="opt.value" />
        </el-select>

        <el-input-number
          v-else-if="param.kind === 'number'"
          :model-value="Number(paramModel(param) ?? 0)"
          size="small"
          :min="param.min ?? 1"
          :max="param.max ?? 99999"
          :step="param.step ?? 1"
          controls-position="right"
          style="width: 130px"
          @update:model-value="(v: unknown) => onParamChange(param, v)"
        />

        <el-input
          v-else
          :model-value="String(paramModel(param) ?? '')"
          size="small"
          clearable
          style="width: 180px"
          :placeholder="param.hint ?? '包含匹配'"
          @update:model-value="(v: unknown) => onParamChange(param, v)"
        />
        <span v-if="param.hint && param.kind !== 'text'" class="param-hint">{{ param.hint }}</span>
      </div>

      <div class="param-actions">
        <el-button type="primary" :icon="Search" :loading="loading" @click="runQuery()">查询</el-button>
        <el-button :icon="Refresh" text bg @click="reset">重置条件</el-button>
        <el-button :icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
        <el-button :icon="Printer" @click="print">打印</el-button>
      </div>
    </div>

    <!-- 汇总指标条 -->
    <div v-if="definition.chips?.length && rows.length" class="chip-row">
      <span v-for="chip in definition.chips" :key="chip.label" class="chip">
        <em>{{ chip.label }}</em>
        <b>{{ chip.value(rows) }}</b>
      </span>
      <el-button v-if="definition.chart" size="small" text bg @click="chartOpen = !chartOpen">
        {{ chartOpen ? '收起图表' : '展开图表' }}
      </el-button>
    </div>

    <p v-if="dictTruncated" class="dict-warning no-print">
      主数据字典只取前 200 条，超出的 SKU 名称显示为 SKU#id（数字不受影响）。
    </p>

    <!-- 错误态：保留后端 message + 重试 -->
    <el-alert v-if="error" class="state-alert" type="error" show-icon :closable="false" :title="error">
      <template #default>
        <div class="state-actions">
          <el-button size="small" type="danger" plain :icon="Refresh" @click="retry">重试</el-button>
          <el-button size="small" text bg @click="reloadDictionary">重载主数据字典</el-button>
        </div>
      </template>
    </el-alert>

    <!-- 结果表 -->
    <div v-else class="result-area">
      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        size="small"
        max-height="520"
        show-summary
        :summary-method="summaryMethod"
        class="report-table"
      >
        <el-table-column
          v-for="col in columns"
          :key="col.key"
          :label="col.label"
          :prop="col.key"
          :width="col.width"
          :align="col.align ?? (col.kind === 'text' ? 'left' : 'right')"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span :class="{ 'cell-negative': isNegative(col, row) }">{{ formatCell(col.kind, columnRaw(col, row)) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p v-if="loading">正在查询…</p>
            <p v-else-if="!lastRunAt">点击「查询」加载这张报表。</p>
            <p v-else>当前条件下没有数据。</p>
            <p v-if="!loading && lastRunAt && rows.length === 0" class="empty-tip">
              换个期间试试 —— 报表按后端业务日期/流水时间口径过滤，跨度太窄最容易查空。
            </p>
          </div>
        </template>
      </el-table>

      <figure v-if="chartData" class="chart-block">
        <figcaption class="chart-title">{{ chartData.title }}</figcaption>
        <MiniChart
          :type="chartData.type"
          :categories="chartData.categories"
          :series="chartData.series"
          :unit="chartData.unit"
        />
      </figure>
    </div>
  </section>
</template>

<style scoped>
.report-shell {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 14px 16px 18px;
}
.shell-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.shell-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.shell-subtitle {
  margin: 3px 0 0;
  font-size: 12px;
  color: #8492a6;
}
.shell-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #8492a6;
  white-space: nowrap;
}
.shell-caliber {
  margin: 8px 0 0;
  padding: 7px 10px;
  font-size: 12px;
  line-height: 1.65;
  color: #5a6b81;
  background: #f6f9fd;
  border-left: 3px solid #4a90d9;
  border-radius: 0 4px 4px 0;
}
.param-area {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 22px;
  align-items: center;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed #ebeef5;
}
.param-item {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.param-label {
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
}
.param-inline {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.preset-group :deep(.el-button) {
  padding: 5px 9px;
}
.dash {
  font-size: 12px;
  color: #909399;
}
.param-hint {
  font-size: 11px;
  color: #a0a6b0;
}
.param-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 14px;
}
.chip {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 5px 12px;
  background: #f4f7fb;
  border-radius: 20px;
  font-size: 12px;
}
.chip em {
  font-style: normal;
  color: #8492a6;
}
.chip b {
  font-variant-numeric: tabular-nums;
  color: #1f2d3d;
}
.dict-warning {
  margin: 10px 0 0;
  font-size: 11px;
  color: #b28a2e;
}
.state-alert {
  margin-top: 14px;
}
.state-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.result-area {
  margin-top: 14px;
}
.report-table :deep(.el-table__footer) td {
  background: #f7f9fc !important;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.cell-negative {
  color: #d5413f;
  font-weight: 600;
}
.empty-state {
  padding: 22px 0;
  font-size: 13px;
  color: #909399;
}
.empty-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #b1b7c0;
}
.chart-block {
  margin: 18px 0 0;
  padding: 12px 10px 4px;
  border: 1px solid #f0f2f5;
  border-radius: 6px;
}
.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: #5a6b81;
  text-align: center;
  margin-bottom: 4px;
}

/* FR-V3-34 打印：只留标题、口径、条件摘要与表格 */
@media print {
  .no-print,
  .chart-block,
  .chip-row {
    display: none !important;
  }
  .report-shell {
    border: none;
    padding: 0;
  }
  .report-table {
    max-height: none !important;
  }
  .report-table :deep(.el-table__body-wrapper) {
    max-height: none !important;
    overflow: visible !important;
  }
  @page {
    size: A4 landscape;
    margin: 12mm;
  }
}
</style>
