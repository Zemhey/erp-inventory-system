<script setup lang="ts">
/**
 * 手绘 SVG 图表（本泳道不引 echarts，见交付约束 4）。
 *
 * ★ 为什么自己画：node_modules 是共享 junction，装不了新依赖；而报表需要的
 *   只有折线 / 柱 / 横条 / 环形 / 堆叠 5 种，SVG 各一段 computed 就够，
 *   还能顺手把「深色大屏」和「浅色报表中心」用同一套组件喂不同配色。
 *
 * ★ viewBox 缩放：内部固定 880×300 的坐标系，外层 width:100%，
 *   所以父容器变窄时图等比缩小、不会溢出也不会裁字。
 */
import { computed } from 'vue'
import type { ChartType } from '../core/types'

interface SeriesInput {
  name: string
  values: number[]
  color?: string
}

const props = defineProps<{
  type: ChartType
  categories: string[]
  series: SeriesInput[]
  unit?: 'money' | 'qty' | 'rate'
  /** 环形图中间挖空的颜色（浅色页 #fff，深色大屏用底色） */
  holeColor?: string
  /** 深色模式配色 */
  dark?: boolean
}>()

const W = 880
const H = 300
const PAD = { top: 18, right: 18, bottom: 48, left: 66 }

const PALETTE = ['#2f7ed8', '#3fb27f', '#e6a23c', '#8b5cf6', '#f56c6c', '#18c0d8', '#a5c965', '#ff8f5e']

const axisColor = computed(() => (props.dark ? 'rgba(148,180,220,0.28)' : '#e3e8f0'))
const textColor = computed(() => (props.dark ? '#8fa8c8' : '#8492a6'))
const gridColor = computed(() => (props.dark ? 'rgba(120,160,210,0.16)' : '#eef2f7'))

const plotW = W - PAD.left - PAD.right
const plotH = H - PAD.top - PAD.bottom

/** 数值 → 展示文本（金额按分传入，折成元/万）。 */
function tickText(raw: number): string {
  if (props.unit === 'money') {
    const yuan = raw / 100
    if (Math.abs(yuan) >= 10000) return `${(yuan / 10000).toFixed(1)}万`
    if (Math.abs(yuan) >= 1000) return `${(yuan / 1000).toFixed(1)}k`
    return yuan.toFixed(0)
  }
  if (props.unit === 'rate') return `${(raw / 100).toFixed(1)}%`
  if (Math.abs(raw) >= 10000) return `${(raw / 10000).toFixed(1)}万`
  return String(Math.round(raw * 100) / 100)
}

function tooltipText(raw: number): string {
  if (props.unit === 'money') return `¥ ${(raw / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  if (props.unit === 'rate') return `${(raw / 100).toFixed(2)}%`
  return raw.toLocaleString('zh-CN')
}

/** 轴上取 5 个刻度（含 0）。 */
const ticks = computed(() => {
  const max = maxOf.value
  const step = max <= 0 ? 1 : niceStep(max / 4)
  const top = Math.ceil(max / step) * step
  const out: number[] = []
  for (let v = 0; v <= top + 1e-9; v += step) out.push(v)
  return { list: out, top: top || 1 }
})

function maxOfSeries(): number {
  let max = 0
  if (props.type === 'stack') {
    const count = props.categories.length
    for (let i = 0; i < count; i += 1) {
      const sum = props.series.reduce((s, se) => s + Math.max(0, se.values[i] ?? 0), 0)
      if (sum > max) max = sum
    }
  } else {
    for (const se of props.series) for (const v of se.values) if (v > max) max = v
  }
  return max
}

const maxOf = computed(maxOfSeries)

function niceStep(raw: number): number {
  if (!Number.isFinite(raw) || raw <= 0) return 1
  const exp = Math.floor(Math.log10(raw))
  const base = 10 ** exp
  const norm = raw / base
  const mult = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10
  return mult * base
}

function yOf(value: number, top: number): number {
  return PAD.top + plotH - (Math.max(0, value) / top) * plotH
}

/** 折线：每条 series 一个 polyline。 */
const lineShapes = computed(() => {
  const { top } = ticks.value
  const n = props.categories.length
  const dx = n <= 1 ? 0 : plotW / (n - 1)
  return props.series.map((se, si) => {
    const pts = se.values
      .map((v, i) => `${(PAD.left + dx * i).toFixed(1)},${yOf(v, top).toFixed(1)}`)
      .join(' ')
    const area = n > 1 ? `M ${PAD.left},${PAD.top + plotH} ${se.values.map((v, i) => `L ${(PAD.left + dx * i).toFixed(1)},${yOf(v, top).toFixed(1)}`).join(' ')} L ${(PAD.left + dx * (n - 1)).toFixed(1)},${PAD.top + plotH} Z` : ''
    return { name: se.name, pts, area, color: se.color ?? PALETTE[si % PALETTE.length] }
  })
})

/** 分组柱 & 堆叠柱共用一套 x 槽位。 */
const barSlots = computed(() => {
  const n = Math.max(1, props.categories.length)
  const slot = plotW / n
  const gap = Math.min(14, slot * 0.25)
  return { slot, gap }
})

const barShapes = computed(() => {
  const { top } = ticks.value
  const { slot, gap } = barSlots.value
  const out: { x: number; y: number; w: number; h: number; color: string }[] = []
  if (props.type === 'stack') {
    const bw = Math.max(8, slot - gap * 2)
    props.categories.forEach((_, ci) => {
      let acc = 0
      props.series.forEach((se, si) => {
        const v = Math.max(0, se.values[ci] ?? 0)
        const h = (v / top) * plotH
        const y = PAD.top + plotH - h - acc
        acc += h
        out.push({ x: PAD.left + slot * ci + gap, y, w: bw, h, color: se.color ?? PALETTE[si % PALETTE.length] })
      })
    })
    return out
  }
  const count = Math.max(1, props.series.length)
  const bw = Math.max(6, (slot - gap * 2) / count)
  props.series.forEach((se, si) => {
    se.values.forEach((v, ci) => {
      const h = (Math.max(0, v) / top) * plotH
      out.push({
        x: PAD.left + slot * ci + gap + bw * si,
        y: PAD.top + plotH - h,
        w: bw,
        h,
        color: se.color ?? PALETTE[si % PALETTE.length],
      })
    })
  })
  return out
})

/** 横条：只画第一个 series，按值降序已经由调用方排好。 */
const hbarShapes = computed(() => {
  const max = maxOf.value || 1
  const n = Math.max(1, props.categories.length)
  const rowH = plotH / n
  const bw = Math.min(26, rowH * 0.62)
  const se = props.series[0]
  return props.categories.map((label, i) => {
    const v = Math.max(0, se?.values[i] ?? 0)
    const w = (v / max) * (plotW - 90)
    return {
      label,
      value: v,
      x: PAD.left,
      y: PAD.top + rowH * i + (rowH - bw) / 2,
      w,
      h: bw,
      color: se?.color ?? PALETTE[i % PALETTE.length],
    }
  })
})

const donutShapes = computed(() => {
  const se = props.series[0]
  const values = se?.values ?? []
  const total = values.reduce((s, v) => s + Math.max(0, v), 0)
  const cx = PAD.left + plotH / 2
  const cy = H / 2
  const r = plotH / 2 - 4
  if (total <= 0) return { wedges: [] as string[], cx, cy, r, total }
  let angle = -Math.PI / 2
  const wedges = values.map((v) => {
    const sweep = (Math.max(0, v) / total) * Math.PI * 2
    const start = angle
    const end = angle + sweep
    angle = end
    const p1 = polar(cx, cy, r, start)
    const p2 = polar(cx, cy, r, end)
    const large = sweep > Math.PI ? 1 : 0
    return `M ${cx.toFixed(1)} ${cy.toFixed(1)} L ${p1.x.toFixed(1)} ${p1.y.toFixed(1)} A ${r} ${r} 0 ${large} 1 ${p2.x.toFixed(1)} ${p2.y.toFixed(1)} Z`
  })
  return { wedges, cx, cy, r, total }
})

function polar(cx: number, cy: number, r: number, a: number) {
  return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) }
}

const legend = computed(() =>
  props.series.map((se, i) => ({ name: se.name, color: se.color ?? PALETTE[i % PALETTE.length] })),
)

const donutLegend = computed(() => {
  const se = props.series[0]
  const values = se?.values ?? []
  const total = donutShapes.value.total
  return props.categories.map((label, i) => ({
    label,
    color: PALETTE[i % PALETTE.length],
    pct: total > 0 ? `${((Math.max(0, values[i] ?? 0) / total) * 100).toFixed(1)}%` : '-',
    text: tooltipText(values[i] ?? 0),
  }))
})

function shortLabel(raw: string): string {
  const s = String(raw ?? '')
  return s.length > 10 ? `${s.slice(0, 9)}…` : s
}
</script>

<template>
  <div class="mini-chart">
    <svg :viewBox="`0 0 ${W} ${H}`" role="img" preserveAspectRatio="xMidYMid meet">
      <!-- 网格 + Y 轴刻度 -->
      <template v-if="type !== 'donut'">
        <g v-for="(t, i) in ticks.list" :key="`grid-${i}`">
          <line :x1="PAD.left" :x2="W - PAD.right" :y1="yOf(t, ticks.top)" :y2="yOf(t, ticks.top)" :stroke="gridColor" stroke-width="1" />
          <text :x="PAD.left - 8" :y="yOf(t, ticks.top) + 4" class="tick" :fill="textColor" text-anchor="end">{{ tickText(t) }}</text>
        </g>
        <line :x1="PAD.left" :x2="W - PAD.right" :y1="PAD.top + plotH" :y2="PAD.top + plotH" :stroke="axisColor" stroke-width="1.5" />
      </template>

      <!-- 折线 -->
      <template v-if="type === 'line'">
        <g v-for="(l, i) in lineShapes" :key="`line-${i}`">
          <path v-if="l.area" :d="`${l.area} Z`" :fill="l.color" fill-opacity="0.10" />
          <polyline :points="l.pts" fill="none" :stroke="l.color" stroke-width="2.4" stroke-linejoin="round" stroke-linecap="round" />
          <circle v-for="(p, pi) in l.pts.split(' ')" :key="`pt-${i}-${pi}`" :cx="Number(p.split(',')[0])" :cy="Number(p.split(',')[1])" r="3" :fill="l.color" />
        </g>
      </template>

      <!-- 柱状 / 堆叠 -->
      <template v-if="type === 'bar' || type === 'stack'">
        <rect v-for="(b, i) in barShapes" :key="`bar-${i}`" :x="b.x" :y="b.y" :width="b.w" :height="Math.max(0, b.h)" :fill="b.color" rx="2" />
      </template>

      <!-- 横条 -->
      <template v-if="type === 'hbar'">
        <g v-for="(b, i) in hbarShapes" :key="`hbar-${i}`">
          <rect :x="b.x" :y="b.y" :width="Math.max(1, b.w)" :height="b.h" :fill="b.color" rx="3" />
          <text :x="b.x + Math.max(1, b.w) + 6" :y="b.y + b.h / 2 + 4" class="tick" :fill="textColor">{{ tickText(b.value) }}</text>
          <text :x="b.x - 8" :y="b.y + b.h / 2 + 4" class="tick" :fill="textColor" text-anchor="end">{{ shortLabel(b.label) }}</text>
        </g>
      </template>

      <!-- 环形 -->
      <template v-if="type === 'donut'">
        <path v-for="(d, i) in donutShapes.wedges" :key="`wedge-${i}`" :d="d" :fill="PALETTE[i % PALETTE.length]" stroke="none" />
        <circle :cx="donutShapes.cx" :cy="donutShapes.cy" :r="donutShapes.r * 0.56" :fill="holeColor ?? '#ffffff'" />
        <text :x="donutShapes.cx" :y="donutShapes.cy - 2" class="donut-total" text-anchor="middle">{{ tickText(donutShapes.total) }}</text>
        <text :x="donutShapes.cx" :y="donutShapes.cy + 20" class="tick" :fill="textColor" text-anchor="middle">合计</text>
      </template>

      <!-- X 轴分类（横条与环形不画分类轴） -->
      <template v-if="type !== 'hbar' && type !== 'donut'">
        <text
          v-for="(c, i) in categories"
          :key="`cat-${i}`"
          :x="PAD.left + barSlots.slot * i + barSlots.slot / 2"
          :y="PAD.top + plotH + 20"
          class="tick"
          :fill="textColor"
          text-anchor="middle"
        >
          {{ shortLabel(c) }}
        </text>
      </template>
    </svg>

    <!-- 图例：横条/环形用 HTML 列表，比 SVG 里排字好读 -->
    <ul v-if="type === 'donut'" class="legend legend-list">
      <li v-for="(l, i) in donutLegend" :key="`dl-${i}`">
        <i :style="{ background: l.color }" />
        <span class="legend-name">{{ l.label }}</span>
        <span class="legend-value">{{ l.text }}</span>
        <span class="legend-pct">{{ l.pct }}</span>
      </li>
    </ul>
    <ul v-else class="legend">
      <li v-for="(l, i) in legend" :key="`lg-${i}`">
        <i :style="{ background: l.color }" />
        <span class="legend-name">{{ l.name }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.mini-chart {
  width: 100%;
}
.mini-chart svg {
  width: 100%;
  height: auto;
  display: block;
}
.tick {
  font-size: 12px;
}
.donut-total {
  font-size: 20px;
  font-weight: 600;
  fill: #303133;
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  justify-content: center;
}
.legend-list {
  justify-content: flex-start;
  gap: 4px 10px;
  font-size: 12px;
}
.legend li {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 120px;
}
.legend i {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex: none;
}
.legend-value {
  color: #8492a6;
  font-variant-numeric: tabular-nums;
}
.legend-pct {
  font-weight: 600;
}
</style>
