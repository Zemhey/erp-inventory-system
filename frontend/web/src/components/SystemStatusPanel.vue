<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getToken } from '@/api/client'

/**
 * 工作台顶部的「系统运行状态」。
 *
 * 为什么放在第一屏：这张页面的第一问题永远是"系统现在是活的吗"，
 * 而不是"这个月卖了多少"。KPI 是结论，运行状态是前提。
 *
 * 三个探针各自独立，任何一个失败都不影响其它两个的展示：
 *   1. 后端  GET /readyz               —— 容器 nginx 把它反代到
 *                                        backend 的 /actuator/health/readiness。
 *                                        ★ 不能探 /actuator/health：生产入口只放行
 *                                        /api/ 与 /readyz，且宿主 nginx 屏蔽 /actuator，
 *                                        直连会让面板永远红。
 *                                        就绪组本身含 db 指示器，故整体 UP 即可判定库可用；
 *                                        拿不到 components 时按整体状态回退，不谎报"异常"。
 *   2. MCP   GET /mcp-bridge/health    —— 由 Vite 代理到 MCP Server 的 HTTP 端口；
 *                                        没启动就是"未接入"，不是错误
 *   3. 前端  本地环境信息              —— 不联网，永远有值
 *
 * ★ 轮询只在标签页可见时跑：后台标签页每 20 秒打一次后端没有意义。
 */

type ProbeState = 'up' | 'down' | 'idle' | 'checking'

interface Probe {
  key: string
  label: string
  state: ProbeState
  /** 主值：UP / 延迟 / 版本 */
  value: string
  /** 一行补充说明 */
  hint: string
  /** 悬浮时的细节（地址、原因） */
  detail: string
}

interface HealthPayload {
  status?: string
  components?: Record<string, { status?: string } | undefined>
}

interface McpPayload {
  status?: string
  name?: string
  version?: string
  tools?: number
  transport?: string
  auth?: string
}

const POLL_MS = 20_000

const backend = ref<ProbeState>('idle')
const backendLatency = ref<number | null>(null)
const dbState = ref<ProbeState>('idle')
const mcpState = ref<ProbeState>('idle')
const mcpInfo = ref<McpPayload | null>(null)
const mcpError = ref('')

const checking = ref(false)
const lastChecked = ref<Date | null>(null)
const failures = ref(0)

/** 计时器与可见性监听都要能回收，否则离开工作台后仍在打接口。 */
let timer: ReturnType<typeof setInterval> | undefined

async function timedFetch(url: string, timeoutMs = 4000): Promise<Response> {
  const controller = new AbortController()
  const abort = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const token = getToken()
    return await fetch(url, {
      signal: controller.signal,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
  } finally {
    clearTimeout(abort)
  }
}

async function probeBackend(): Promise<void> {
  backend.value = 'checking'
  const started = performance.now()
  try {
    const response = await timedFetch('/readyz')
    backendLatency.value = Math.round(performance.now() - started)
    if (!response.ok) {
      backend.value = 'down'
      dbState.value = 'down'
      return
    }
    const payload = (await response.json()) as HealthPayload
    backend.value = payload.status === 'UP' ? 'up' : 'down'
    // show-details=never 时 components 不下发；就绪组本身包含 db 指示器，
    // 所以整体 UP 就是"库连得上"，按整体状态回退比报"未知"更有用。
    const components = payload.components
    const db = components?.db?.status
    dbState.value = db ? (db === 'UP' ? 'up' : 'down') : backend.value
  } catch {
    backendLatency.value = null
    backend.value = 'down'
    dbState.value = 'down'
  }
}

async function probeMcp(): Promise<void> {
  mcpState.value = 'checking'
  try {
    const response = await timedFetch('/mcp-bridge/health', 2500)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const payload = (await response.json()) as McpPayload
    mcpInfo.value = payload
    mcpState.value = payload.status === 'UP' ? 'up' : 'down'
    mcpError.value = ''
  } catch (cause) {
    // 未启动是常态：MCP 由 AI Agent 按需拉起，不占用常驻端口
    mcpState.value = 'idle'
    mcpInfo.value = null
    mcpError.value = (cause as Error)?.message ?? ''
  }
}

async function refresh(): Promise<void> {
  if (checking.value) return
  checking.value = true
  try {
    await Promise.all([probeBackend(), probeMcp()])
    failures.value = backend.value === 'down' ? failures.value + 1 : 0
  } finally {
    checking.value = false
    lastChecked.value = new Date()
  }
}

function onVisibility() {
  if (document.visibilityState === 'visible') void refresh()
}

onMounted(() => {
  void refresh()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') void refresh()
  }, POLL_MS)
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibility)
})

// ─────────────────────────── 派生视图 ───────────────────────────

function latencyText(): string {
  if (backend.value !== 'up') return '—'
  return backendLatency.value === null ? '—' : `${backendLatency.value} ms`
}

const probes = computed<Probe[]>(() => [
  {
    key: 'api',
    label: '后端服务',
    state: backend.value,
    value: backend.value === 'up' ? '运行中' : backend.value === 'checking' ? '检测中' : '不可达',
    hint: `接口延迟 ${latencyText()}`,
    detail: 'Spring Boot · /readyz 就绪探针',
  },
  {
    key: 'db',
    label: '数据库',
    state: dbState.value,
    value: dbState.value === 'up' ? '已连接' : dbState.value === 'down' ? '异常' : '未知',
    hint: dbState.value === 'idle' ? '尚未探测' : 'PostgreSQL · 连接池常态',
    detail: 'PostgreSQL · 由 /readyz 就绪组判定',
  },
  {
    key: 'mcp',
    label: 'MCP 智能体',
    state: mcpState.value,
    value:
      mcpState.value === 'up'
        ? `${mcpInfo.value?.tools ?? 0} 个工具`
        : mcpState.value === 'checking'
          ? '检测中'
          : '按需接入',
    hint:
      mcpState.value === 'up'
        ? `${mcpInfo.value?.transport ?? 'http'} · 可用于豆包 / WorkBuddy`
        : '未常驻启动，Agent 需要时再连',
    detail: mcpState.value === 'up' ? 'MCP Server · /health' : mcpError.value || 'MCP Server 未启动',
  },
  {
    key: 'web',
    label: '前端服务',
    state: 'up',
    value: import.meta.env.PROD ? '生产构建' : '开发模式',
    hint: `Vite · ${import.meta.env.MODE}`,
    detail: window.location.origin,
  },
])

const overall = computed<'ok' | 'degraded' | 'down' | 'checking'>(() => {
  if (checking.value && !lastChecked.value) return 'checking'
  if (backend.value === 'down') return 'down'
  if (dbState.value === 'down') return 'degraded'
  return 'ok'
})

const overallText = computed(() => {
  switch (overall.value) {
    case 'down':
      return '后端不可达'
    case 'degraded':
      return '部分依赖异常'
    case 'checking':
      return '正在检测'
    default:
      return '系统运行正常'
  }
})

const overallHint = computed(() => {
  switch (overall.value) {
    case 'down':
      return '前端仍在响应，但接口不可用。请确认后端进程是否还活着。'
    case 'degraded':
      return '后端在线，但数据库健康检查未通过，写操作可能失败。'
    case 'checking':
      return '正在读取后端与 MCP 的健康状态…'
    default:
      return '后端、数据库与前端的健康检查均已通过，可以正常作业。'
  }
})

const checkedLabel = computed(() => {
  if (!lastChecked.value) return '尚未检查'
  const d = lastChecked.value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

const mcpHint = computed(() =>
  mcpState.value === 'up'
    ? '在豆包 / WorkBuddy 里直接说「查询库存」即可操作本系统'
    : '想看 Agent 直连效果？启动 packages/mcp-server（见 docs/MCP-接入指南.md）',
)
</script>

<template>
  <section class="status" :data-state="overall">
    <!-- 左：总体状态。一个呼吸点 + 一句话，扫一眼就知道系统活没活 -->
    <div class="status-hero">
      <span class="orb" :class="`orb--${overall}`" aria-hidden="true">
        <span class="orb-core" />
        <span class="orb-ring" />
      </span>
      <div class="hero-text">
        <div class="hero-title">
          {{ overallText }}
          <span class="hero-dot" aria-hidden="true">·</span>
          <button
            type="button"
            class="link-btn"
            :disabled="checking"
            @click="refresh"
          >
            {{ checking ? '检查中…' : '立即检查' }}
          </button>
        </div>
        <p class="hero-hint">{{ overallHint }}</p>
      </div>
      <div class="hero-meta">
        <div class="meta-label">最近检查</div>
        <div class="meta-value">{{ checkedLabel }}</div>
        <div class="meta-note">每 {{ POLL_MS / 1000 }} 秒自动刷新</div>
      </div>
    </div>

    <!-- 右：四个探针。hover 抬起一格，是"可以点/有细节"的暗示 -->
    <div class="probe-grid">
      <el-tooltip
        v-for="probe in probes"
        :key="probe.key"
        :content="probe.detail"
        placement="top"
        :show-after="260"
      >
        <div class="probe" :class="`probe--${probe.state}`">
          <div class="probe-head">
            <span class="probe-dot" />
            <span class="probe-label">{{ probe.label }}</span>
          </div>
          <div class="probe-value">{{ probe.value }}</div>
          <div class="probe-hint">{{ probe.hint }}</div>
        </div>
      </el-tooltip>
    </div>

    <div class="status-foot">
      <span class="foot-item">
        <span class="foot-key">MCP</span>
        {{ mcpHint }}
      </span>
      <span v-if="failures > 1" class="foot-warn">
        已连续 {{ failures }} 次未连通
      </span>
    </div>
  </section>
</template>

<style scoped>
.status {
  position: relative;
  margin-bottom: 16px;
  padding: 18px 20px 14px;
  border: 1px solid var(--ys-line);
  border-radius: var(--ys-r-card);
  background:
    radial-gradient(120% 120% at 0% 0%, var(--ys-daiqing-wash) 0%, transparent 46%),
    var(--ys-cibai);
  box-shadow: var(--ys-shadow-card);
  overflow: hidden;
}

/* 顶部一条状态色细线：不靠整块铺色表达状态 */
.status::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  background: var(--ys-tai);
  opacity: 0.9;
}

.status[data-state='degraded']::before {
  background: var(--ys-taotu);
}

.status[data-state='down']::before {
  background: var(--ys-yanzhi);
}

.status[data-state='checking']::before {
  background: var(--ys-daiqing);
}

/* ── 总体状态 ── */
.status-hero {
  display: flex;
  align-items: center;
  gap: 14px;
}

.orb {
  position: relative;
  width: 34px;
  height: 34px;
  flex: none;
  display: grid;
  place-items: center;
}

.orb-core {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--ys-tai);
  box-shadow: 0 0 0 4px var(--ys-tai-wash);
}

.orb-ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1px solid var(--ys-tai);
  opacity: 0;
  animation: orb-pulse 2.6s cubic-bezier(0.22, 0.58, 0.32, 1) infinite;
}

.orb--degraded .orb-core {
  background: var(--ys-taotu);
  box-shadow: 0 0 0 4px var(--ys-taotu-wash);
}

.orb--degraded .orb-ring {
  border-color: var(--ys-taotu);
}

.orb--down .orb-core {
  background: var(--ys-yanzhi);
  box-shadow: 0 0 0 4px var(--ys-yanzhi-wash);
}

.orb--down .orb-ring {
  border-color: var(--ys-yanzhi);
}

.orb--checking .orb-core {
  background: var(--ys-daiqing);
  box-shadow: 0 0 0 4px var(--ys-daiqing-wash);
}

.orb--checking .orb-ring {
  border-color: var(--ys-daiqing);
}

@keyframes orb-pulse {
  0% {
    transform: scale(0.72);
    opacity: 0.55;
  }
  70% {
    transform: scale(1.18);
    opacity: 0;
  }
  100% {
    opacity: 0;
  }
}

.hero-text {
  min-width: 0;
  flex: 1 1 auto;
}

.hero-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--ys-mo);
}

.hero-dot {
  color: var(--ys-mo-5);
  font-weight: 400;
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  font-size: 12px;
  font-weight: 400;
  color: var(--ys-daiqing);
  cursor: pointer;
  border-bottom: 1px solid transparent;
  transition: border-color 0.16s ease, color 0.16s ease;
}

.link-btn:hover:not(:disabled) {
  border-bottom-color: var(--ys-daiqing);
}

.link-btn:disabled {
  color: var(--ys-mo-4);
  cursor: default;
}

.hero-hint {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ys-mo-3);
  text-wrap: pretty;
}

.hero-meta {
  flex: none;
  text-align: right;
  padding-left: 16px;
  border-left: 1px solid var(--ys-line-soft);
}

.meta-label {
  font-size: 11px;
  color: var(--ys-mo-4);
  letter-spacing: 0.04em;
}

.meta-value {
  font-family: var(--ys-font-num);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  color: var(--ys-mo);
}

.meta-note {
  font-size: 11px;
  color: var(--ys-mo-5);
}

/* ── 四个探针 ── */
.probe-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-top: 14px;
}

.probe {
  padding: 10px 12px;
  border: 1px solid var(--ys-line-soft);
  border-radius: 10px;
  background: var(--ys-warm-50);
  cursor: default;
  transition:
    transform 0.2s cubic-bezier(0.22, 0.58, 0.32, 1),
    border-color 0.2s ease,
    background-color 0.2s ease;
}

.probe:hover {
  transform: translateY(-2px);
  border-color: var(--ys-line-strong);
  background: var(--ys-warm-100);
}

.probe-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.probe-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ys-mo-5);
}

.probe--up .probe-dot {
  background: var(--ys-tai);
  box-shadow: 0 0 0 3px var(--ys-tai-wash);
}

.probe--down .probe-dot {
  background: var(--ys-yanzhi);
  box-shadow: 0 0 0 3px var(--ys-yanzhi-wash);
}

.probe--checking .probe-dot {
  background: var(--ys-daiqing);
  animation: dot-blink 1s ease-in-out infinite alternate;
}

@keyframes dot-blink {
  from {
    opacity: 0.3;
  }
  to {
    opacity: 1;
  }
}

.probe-label {
  font-size: 12px;
  color: var(--ys-mo-3);
}

.probe-value {
  margin-top: 6px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ys-mo);
  font-variant-numeric: tabular-nums;
}

.probe--down .probe-value {
  color: var(--ys-yanzhi);
}

.probe-hint {
  margin-top: 2px;
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--ys-mo-4);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 底部说明 ── */
.status-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--ys-line-soft);
  font-size: 11.5px;
  color: var(--ys-mo-4);
}

.foot-item {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.foot-key {
  display: inline-block;
  margin-right: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--ys-daiqing-wash);
  color: var(--ys-daiqing);
  font-family: var(--ys-font-num);
  font-size: 10.5px;
  letter-spacing: 0.06em;
}

.foot-warn {
  color: var(--ys-yanzhi);
  flex: none;
}

@media (max-width: 1100px) {
  .probe-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .status {
    padding: 14px 14px 12px;
  }

  .status-hero {
    gap: 10px;
  }

  .hero-meta {
    display: none;
  }

  .probe-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  /* 窄屏卡片更窄：探针说明允许换行，避免 "PostgreSQL · 连接池常态" 被截成半句 */
  .probe-hint {
    white-space: normal;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  .status-foot {
    display: none;
  }
}
</style>
