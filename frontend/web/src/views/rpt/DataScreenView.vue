<script setup lang="ts">
/**
 * 数据大屏（FR-V3-33）。
 *
 * ★ 数据全部来自已有的只读报表端点，一个都不新造：
 *   /reports/sales（groupBy=day / sku / customer）、/reports/inventory-summary、
 *   /reports/purchase、/reports/aging?type=AR、/reports/customer-contribution。
 *   简报里提到的 /dashboard/summary 不在本泳道，也不依赖它 —— 缺就少一个区块，
 *   不猜端点。
 *
 * ★ 口径：今日 KPI 用单据业务日期，库存块用流水发生时间的账期口径，
 *   两者在页脚各写一行说明。大屏最容易骗人，说明不能省。
 *
 * ★ 自适应：内部固定 1920×1080 设计稿，外层按 min(宽比, 高比) 做 transform: scale，
 *   所以任何分辨率下都是等比缩放、不出滚动条也不变形。
 *
 * ★ 全屏：路由骨架（router/index.ts / MainLayout.vue）不归本泳道改，所以这里用
 *   position:fixed 覆盖整视口 + 可选的原生 Fullscreen API，效果等同独立全屏页。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { mdmApi, rptApi } from '@/api/client'
import { reportApi } from '@/api/report'
import MiniChart from './components/MiniChart.vue'
import RollingNumber from './components/RollingNumber.vue'
import { isoDate, isoMonth, presetRange } from './core/dates'
import { yuan } from './core/registry'

// ─────────────────────────── 布局缩放 ───────────────────────────

const DESIGN_W = 1920
const DESIGN_H = 1080
const scale = ref(1)

function fit(): void {
  scale.value = Math.min(window.innerWidth / DESIGN_W, window.innerHeight / DESIGN_H)
}

// ─────────────────────────── 时钟与自动刷新 ───────────────────────────

const clock = ref(new Date())
let clockTimer = 0
const refreshSeconds = 60
const nextRefreshIn = ref(refreshSeconds)
const lastRefresh = ref<string | null>(null)
const autoRefresh = ref(true)
const fullscreen = ref(false)

const clockText = computed(() => {
  const d = clock.value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

// ─────────────────────────── 数据 ───────────────────────────

const loading = ref(false)
const error = ref<string | null>(null)

interface Block<T> {
  data: T
  failed: boolean
}

const todaySales = ref<Block<{ amount: number; orders: number; qty: number }>>({ data: { amount: 0, orders: 0, qty: 0 }, failed: false })
const monthStock = ref<Block<{ qty: number; period: string }>>({ data: { qty: 0, period: '' }, failed: false })
const monthPurchase = ref<Block<{ amount: number }>>({ data: { amount: 0 }, failed: false })
const trend = ref<Block<{ label: string; amount: number; qty: number }[]>>({ data: [], failed: false })
const byWarehouse = ref<Block<{ label: string; qty: number }[]>>({ data: [], failed: false })
const topSku = ref<Block<{ label: string; amount: number; qty: number }[]>>({ data: [], failed: false })
const aging = ref<Block<{ label: string; amount: number }[]>>({ data: [], failed: false })
const topCustomer = ref<Block<{ label: string; amount: number; orders: number }[]>>({ data: [], failed: false })

const pad = (n: number) => String(n).padStart(2, '0')

function clockTime(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 今天（yyyy-MM-dd），模板里当静态标签用，避免每次渲染 new Date()。 */
const todayLabel = isoDate(new Date())

/** KPI 金额：RollingNumber 的 value 已经是元，这里只做千分位。 */
function fmtYuan(n: number): string {
  return `¥ ${Math.round(n).toLocaleString('zh-CN')}`
}

/** 单块失败不拖垮整屏：每块自带 failed 标记，页面上点名，其余照常刷新。 */
async function guard<T>(p: Promise<T>, fallback: T): Promise<Block<T>> {
  try {
    return { data: await p, failed: false }
  } catch {
    return { data: fallback, failed: true }
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = null
  const now = new Date()
  const today = isoDate(now)
  const period = isoMonth(now)
  const [trendFrom, trendTo] = presetRange('last30', now)
  const [monthFrom] = presetRange('month', now)

  try {
    const [salesDay, salesToday, salesSku, summary, purchase, agingAr, contribution, warehouses] = await Promise.all([
      guard(rptApi.sales({ from: trendFrom, to: trendTo, groupBy: 'day' }), { items: [] }),
      guard(rptApi.sales({ from: today, to: today, groupBy: 'customer' }), { items: [] }),
      guard(rptApi.sales({ from: monthFrom, to: today, groupBy: 'sku' }), { items: [] }),
      guard(rptApi.inventorySummary({ period }), { items: [] }),
      guard(rptApi.purchase({ from: monthFrom, to: today, groupBy: 'supplier' }), { items: [] }),
      guard(reportApi.aging({ type: 'AR', asOf: today }), null),
      guard(reportApi.contribution({ from: monthFrom, to: today, limit: 10 }), null),
      guard(mdmApi.listWarehouses(false), []),
    ])

    const dayRows = salesDay.data.items
    trend.value = {
      failed: salesDay.failed,
      data: dayRows.map((r) => ({ label: r.label, amount: Number(r.amount ?? 0), qty: Number(r.qty ?? 0) })),
    }

    const todayRows = salesToday.data.items
    todaySales.value = {
      failed: salesToday.failed,
      data: {
        amount: todayRows.reduce((s, r) => s + Number(r.amount ?? 0), 0),
        orders: todayRows.reduce((s, r) => s + Number(r.orderCount ?? 0), 0),
        qty: todayRows.reduce((s, r) => s + Number(r.qty ?? 0), 0),
      },
    }

    const whNames = new Map(warehouses.data.map((w) => [w.id, w.name]))
    const stockByWh = new Map<string, number>()
    for (const row of summary.data.items) {
      const name = whNames.get(row.warehouseId) ?? `仓#${row.warehouseId}`
      stockByWh.set(name, (stockByWh.get(name) ?? 0) + Number(row.closingQty ?? 0))
    }
    byWarehouse.value = {
      failed: summary.failed,
      data: [...stockByWh.entries()].map(([label, qty]) => ({ label, qty })).sort((a, b) => b.qty - a.qty),
    }
    monthStock.value = {
      failed: summary.failed,
      data: { qty: summary.data.items.reduce((s, r) => s + Number(r.closingQty ?? 0), 0), period },
    }
    monthPurchase.value = {
      failed: purchase.failed,
      data: { amount: purchase.data.items.reduce((s, r) => s + Number(r.amount ?? 0), 0) },
    }
    topSku.value = {
      failed: salesSku.failed,
      data: [...salesSku.data.items]
        .sort((a, b) => Number(b.amount) - Number(a.amount))
        .slice(0, 10)
        .map((r) => ({ label: r.label, amount: Number(r.amount ?? 0), qty: Number(r.qty ?? 0) })),
    }
    const ar = agingAr.data
    aging.value = {
      failed: agingAr.failed,
      data: ar
        ? [
            { label: '0-30 天', amount: ar.summary.bucket0 },
            { label: '31-60 天', amount: ar.summary.bucket1 },
            { label: '61-90 天', amount: ar.summary.bucket2 },
            { label: '90 天以上', amount: ar.summary.bucket3 },
          ]
        : [],
    }
    const ct = contribution.data
    topCustomer.value = {
      failed: contribution.failed,
      data: ct ? ct.items.map((r) => ({ label: r.customerName, amount: Number(r.amount ?? 0), orders: Number(r.orderCount ?? 0) })) : [],
    }
    lastRefresh.value = clockTime(now)
    nextRefreshIn.value = refreshSeconds
  } catch (e) {
    error.value = e instanceof Error ? e.message : '大屏数据加载失败'
  } finally {
    loading.value = false
  }
}

const blockFailed = computed(() => [trend, byWarehouse, topSku, aging, topCustomer].filter((b) => b.value.failed).length)

// ─────────────────────────── 趋势图（手绘 SVG 复用 MiniChart） ───────────────────────────

const trendChart = computed(() => ({
  categories: trend.value.data.map((r) => r.label.slice(5)),
  series: [{ name: '出货金额', values: trend.value.data.map((r) => r.amount) }],
}))

const stockChart = computed(() => ({
  categories: byWarehouse.value.data.map((r) => r.label),
  series: [{ name: '期末库存', values: byWarehouse.value.data.map((r) => r.qty) }],
}))

const agingChart = computed(() => ({
  categories: aging.value.data.map((r) => r.label),
  series: [{ name: '应收余额', values: aging.value.data.map((r) => r.amount) }],
}))

const hotChart = computed(() => ({
  categories: topSku.value.data.map((r) => r.label),
  series: [{ name: '销售额', values: topSku.value.data.map((r) => r.amount) }],
}))

const maxCustomerAmount = computed(() => Math.max(1, ...topCustomer.value.data.map((r) => r.amount)))

// ─────────────────────────── 滚动公告 ───────────────────────────

const ticker = computed(() => {
  const parts: string[] = []
  if (!trend.value.failed && trend.value.data.length > 0) {
    const last = trend.value.data[trend.value.data.length - 1]
    parts.push(`最近一个出货日 ${last.label}：${yuan(last.amount)}`)
  }
  if (!topSku.value.failed && topSku.value.data.length > 0) {
    parts.push(`本月销冠 ${topSku.value.data[0].label} ${yuan(topSku.value.data[0].amount)}`)
  }
  if (!aging.value.failed && aging.value.data.length > 0) {
    parts.push(`应收 90 天以上 ${yuan(aging.value.data[3].amount)}`)
  }
  if (!monthStock.value.failed) parts.push(`库存账期 ${monthStock.value.data.period} 期末 ${monthStock.value.data.qty.toLocaleString('zh-CN')} 件`)
  if (blockFailed.value > 0) parts.push(`${blockFailed.value} 个区块取数失败，稍后自动重试`)
  return parts.join('　·　') || '等待首次刷新…'
})

// ─────────────────────────── 全屏与生命周期 ───────────────────────────

async function toggleFullscreen(): Promise<void> {
  try {
    if (!document.fullscreenElement) {
      await document.documentElement.requestFullscreen()
      fullscreen.value = true
    } else {
      await document.exitFullscreen()
      fullscreen.value = false
    }
  } catch {
    // 浏览器拒绝（非用户手势 / iframe 无 allowfullscreen）：fixed 覆盖层已经铺满视口，
    // 不需要第二套布局，只提示一下。
    ElMessage.info('浏览器未允许全屏，已按视口铺满显示')
  }
}

function tick(): void {
  clock.value = new Date()
  if (!autoRefresh.value || document.hidden) return
  nextRefreshIn.value -= 1
  if (nextRefreshIn.value <= 0) void loadAll()
}

function onResize(): void {
  fit()
}

onMounted(() => {
  fit()
  window.addEventListener('resize', onResize)
  // 一个定时器同时管时钟和倒计时：两个定时器会互相抢时序，导致偶发少刷一轮
  clockTimer = window.setInterval(tick, 1000)
  void loadAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.clearInterval(clockTimer)
  if (document.fullscreenElement) void document.exitFullscreen()
})

function retryAll(): void {
  void loadAll()
}
</script>

<template>
  <div class="screen-root">
    <div class="screen-stage" :style="{ transform: `scale(${scale})` }">
      <!-- 顶栏 -->
      <header class="screen-head">
        <div class="head-left">
          <h1>进销存经营数据大屏</h1>
          <span class="head-sub">ERP · 报表中心 · 只读快照</span>
        </div>
        <div class="head-ticker">
          <div class="ticker-track">{{ ticker }}　·　{{ ticker }}</div>
        </div>
        <div class="head-right">
          <span class="clock">{{ clockText }}</span>
          <div class="head-tools">
            <button class="tool-btn" :class="{ off: !autoRefresh }" @click="autoRefresh = !autoRefresh">
              自动刷新 {{ autoRefresh ? `${nextRefreshIn}s` : '暂停' }}
            </button>
            <button class="tool-btn" @click="retryAll">立即刷新</button>
            <button class="tool-btn" @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</button>
          </div>
        </div>
      </header>

      <p v-if="error" class="screen-error">{{ error }} —— 部分区块可能无数据，点「立即刷新」重试。</p>

      <!-- 1. KPI 四联 -->
      <section class="kpi-row">
        <div class="kpi-card">
          <span class="kpi-label">今日出货金额</span>
          <RollingNumber class="kpi-value" :value="todaySales.data.amount / 100" :format="fmtYuan" />
          <span class="kpi-foot">{{ todaySales.failed ? '取数失败' : `单据业务日期 ${todayLabel}` }}</span>
        </div>
        <div class="kpi-card">
          <span class="kpi-label">今日出货单数</span>
          <RollingNumber class="kpi-value" :value="todaySales.data.orders" />
          <span class="kpi-foot">{{ todaySales.data.qty.toLocaleString('zh-CN') }} 件</span>
        </div>
        <div class="kpi-card">
          <span class="kpi-label">期末库存件数</span>
          <RollingNumber class="kpi-value" :value="monthStock.data.qty" />
          <span class="kpi-foot">{{ monthStock.failed ? '取数失败' : `流水口径账期 ${monthStock.data.period}` }}</span>
        </div>
        <div class="kpi-card">
          <span class="kpi-label">本月进货金额</span>
          <RollingNumber class="kpi-value" :value="monthPurchase.data.amount / 100" :format="fmtYuan" />
          <span class="kpi-foot">{{ monthPurchase.failed ? '取数失败' : '按供应商聚合求和' }}</span>
        </div>
      </section>

      <!-- 2~5. 图表区 -->
      <section class="grid-main">
        <div class="panel span-8">
          <h3 class="panel-title">近 30 天出货金额趋势<em v-if="trend.failed" class="fail">取数失败</em></h3>
          <MiniChart v-if="trend.data.length" dark type="line" :categories="trendChart.categories" :series="trendChart.series" unit="money" :hole-color="'#0b1220'" />
          <p v-else class="panel-empty">{{ loading ? '加载中…' : '当前区间没有出货流水' }}</p>
        </div>
        <div class="panel span-4">
          <h3 class="panel-title">库存按仓库分布<em v-if="byWarehouse.failed" class="fail">取数失败</em></h3>
          <MiniChart v-if="byWarehouse.data.length" dark type="donut" :categories="stockChart.categories" :series="stockChart.series" unit="qty" hole-color="#111a2e" />
          <p v-else class="panel-empty">{{ loading ? '加载中…' : '期末库存为空或无仓库字典' }}</p>
        </div>
        <div class="panel span-4">
          <h3 class="panel-title">热销 SKU TOP10<em v-if="topSku.failed" class="fail">取数失败</em></h3>
          <MiniChart v-if="topSku.data.length" dark type="hbar" :categories="hotChart.categories" :series="hotChart.series" unit="money" hole-color="#0b1220" />
          <p v-else class="panel-empty">{{ loading ? '加载中…' : '本月还没有出货记录' }}</p>
        </div>
        <div class="panel span-4">
          <h3 class="panel-title">应收账龄（AR）<em v-if="aging.failed" class="fail">取数失败</em></h3>
          <MiniChart v-if="aging.data.length" dark type="bar" :categories="agingChart.categories" :series="agingChart.series" unit="money" hole-color="#0b1220" />
          <p v-else class="panel-empty">{{ loading ? '加载中…' : '没有应收余额' }}</p>
        </div>
        <div class="panel span-4">
          <h3 class="panel-title">客户销售 TOP10<em v-if="topCustomer.failed" class="fail">取数失败</em></h3>
          <ol class="rank-list">
            <li v-for="(c, i) in topCustomer.data" :key="c.label">
              <span class="rank-no" :class="`no-${i < 3 ? i + 1 : 'n'}`">{{ i + 1 }}</span>
              <span class="rank-name">{{ c.label }}</span>
              <span class="rank-bar"><i :style="{ width: `${(c.amount / maxCustomerAmount) * 100}%` }" /></span>
              <span class="rank-value">{{ yuan(c.amount) }}</span>
              <span class="rank-orders">{{ c.orders }} 单</span>
            </li>
            <li v-if="!topCustomer.data.length" class="panel-empty">{{ loading ? '加载中…' : '本月还没有客户贡献' }}</li>
          </ol>
        </div>
      </section>

      <footer class="screen-foot">
        <span>出货 / 进货 = 单据业务日期口径；期末库存 = 流水发生时间口径，两者同一天未必相等（不是 bug）。</span>
        <span v-if="lastRefresh">最近刷新 {{ lastRefresh }} · 每 {{ refreshSeconds }} 秒自动刷新</span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* 覆盖 MainLayout：本泳道不能改路由骨架，用 fixed 层实现同等的全屏视口 */
.screen-root {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: #060c17;
  overflow: hidden;
}
.screen-stage {
  width: 1920px;
  height: 1080px;
  transform-origin: top left;
  position: absolute;
  left: 50%;
  top: 50%;
  margin-left: -960px;
  margin-top: -540px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px 24px 12px;
  background:
    radial-gradient(1200px 520px at 50% -10%, rgba(24, 192, 216, 0.14), transparent 70%),
    linear-gradient(180deg, #0b1220 0%, #080f1b 100%);
  color: #d6e4f5;
  font-size: 14px;
}
.screen-head {
  display: flex;
  align-items: center;
  gap: 22px;
  height: 76px;
  flex: none;
  border-bottom: 1px solid rgba(90, 160, 220, 0.2);
  padding-bottom: 8px;
}
.head-left h1 {
  margin: 0;
  font-size: 27px;
  letter-spacing: 2px;
  background: linear-gradient(90deg, #6ee7ff, #3f8cff);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.head-sub {
  font-size: 12px;
  color: #5c7794;
  letter-spacing: 1px;
}
.head-ticker {
  flex: 1;
  overflow: hidden;
  height: 26px;
  border-left: 1px solid rgba(90, 160, 220, 0.18);
  border-right: 1px solid rgba(90, 160, 220, 0.18);
  mask-image: linear-gradient(90deg, transparent, #000 6%, #000 94%, transparent);
}
.ticker-track {
  display: inline-block;
  white-space: nowrap;
  font-size: 13px;
  color: #7fd0e8;
  animation: roll 38s linear infinite;
}
@keyframes roll {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}
.head-right {
  text-align: right;
  flex: none;
}
.clock {
  font-size: 19px;
  letter-spacing: 1px;
  color: #9fe3ff;
  font-variant-numeric: tabular-nums;
}
.head-tools {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  justify-content: flex-end;
}
.tool-btn {
  background: rgba(24, 192, 216, 0.1);
  border: 1px solid rgba(24, 192, 216, 0.35);
  color: #8fdcf0;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 3px;
  cursor: pointer;
}
.tool-btn:hover {
  background: rgba(24, 192, 216, 0.2);
}
.tool-btn.off {
  border-color: rgba(230, 162, 60, 0.5);
  color: #e6a23c;
}
.screen-error {
  margin: 0;
  color: #f56c6c;
  font-size: 13px;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  flex: none;
}
.kpi-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 18px;
  background: linear-gradient(160deg, rgba(28, 60, 102, 0.5), rgba(12, 24, 42, 0.6));
  border: 1px solid rgba(90, 160, 220, 0.22);
  border-radius: 6px;
  position: relative;
  overflow: hidden;
}
.kpi-card::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  width: 3px;
  height: 100%;
  background: linear-gradient(180deg, #18c0d8, #3f8cff);
}
.kpi-label {
  font-size: 13px;
  color: #7c9ab6;
}
.kpi-value {
  font-size: 33px;
  font-weight: 700;
  color: #eaf6ff;
  line-height: 1.2;
}
.kpi-foot {
  font-size: 11px;
  color: #5c7794;
}
.grid-main {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 14px;
  min-height: 0;
}
.panel {
  background: rgba(12, 24, 42, 0.6);
  border: 1px solid rgba(90, 160, 220, 0.18);
  border-radius: 6px;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.span-8 { grid-column: span 8; }
.span-4 { grid-column: span 4; }
.panel-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: #a8cdf0;
  letter-spacing: 1px;
  flex: none;
}
.panel-title .fail {
  font-style: normal;
  font-size: 11px;
  color: #e6a23c;
  margin-left: 8px;
}
.panel-empty {
  color: #4d6a86;
  font-size: 12px;
  text-align: center;
  padding: 26px 0;
}
.rank-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: hidden;
}
.rank-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  font-size: 12px;
  border-bottom: 1px dashed rgba(90, 160, 220, 0.12);
}
.rank-no {
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 3px;
  font-size: 11px;
  background: rgba(90, 160, 220, 0.16);
  color: #86a9c9;
  flex: none;
}
.rank-list li .no-1 { background: #d4a017; color: #1a1200; }
.rank-list li .no-2 { background: #9aa7b4; color: #15181c; }
.rank-list li .no-3 { background: #b06b32; color: #1a0e04; }
.rank-name {
  width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #cfe4f7;
}
.rank-bar {
  flex: 1;
  height: 7px;
  background: rgba(90, 160, 220, 0.12);
  border-radius: 4px;
  overflow: hidden;
}
.rank-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #18c0d8, #3f8cff);
}
.rank-value {
  width: 96px;
  text-align: right;
  color: #9fe3ff;
  font-variant-numeric: tabular-nums;
}
.rank-orders {
  width: 46px;
  text-align: right;
  color: #6e8aa6;
}
.screen-foot {
  flex: none;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #4d6a86;
  padding-top: 4px;
  border-top: 1px solid rgba(90, 160, 220, 0.14);
}
</style>
