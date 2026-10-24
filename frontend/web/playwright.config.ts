import { defineConfig, devices } from '@playwright/test'
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot = fileURLToPath(new URL('.', import.meta.url))
const artifactsDir = path.join(webRoot, '.e2e-artifacts')
const adminStorage = path.join(artifactsDir, 'admin-storage.json')
const tmpDir = path.join(webRoot, '.tmp')
mkdirSync(tmpDir, { recursive: true })

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3001'
const manageServers = !process.env.E2E_BASE_URL
const reuseExistingServer = process.env.E2E_REUSE_SERVERS === '1'

/**
 * 后端地址（★ 端口参数化，多条泳道才能并行跑 E2E）。
 *
 * 改造前这里硬编码 `http://127.0.0.1:8081`，于是"泳道自己换端口"是不成立的：
 * webServer 去探 8081 的健康检查，而 `e2e/start-backend.mjs` 已按 E2E_BACKEND_PORT
 * 起了另一个端口 —— 探的是别人，测的也是别人。
 *
 * 取值口径（不设任何变量时与改造前逐字一致）：
 *   地址 = `E2E_BACKEND_URL` ?? 由端口推导出的 `http://127.0.0.1:<port>`；
 *   端口 = `E2E_BACKEND_PORT` ?? `E2E_BACKEND_URL` 里的端口 ?? `8081`。
 * 推导结果**回灌**给两个子进程，避免出现"健康检查探 8102、代理指 8081、
 * Java 其实绑在 8081"这种三方各说各话。
 */
const backendPortFromUrl = (() => {
  const raw = process.env.E2E_BACKEND_URL
  if (!raw) return undefined
  try {
    // 省略端口（如 http://127.0.0.1）时不猜 80：让它落到下面的默认值，
    // 免得把一个"指向远端后端"的配置改写成"本地 java 去绑 80 端口"。
    return new URL(raw).port || undefined
  } catch {
    return undefined
  }
})()
const backendPort = process.env.E2E_BACKEND_PORT ?? backendPortFromUrl ?? '8081'
const backendUrl = process.env.E2E_BACKEND_URL ?? `http://127.0.0.1:${backendPort}`

/**
 * 本机优先使用已安装的 Edge（无需下载 Chromium）；CI/Linux 走 Playwright 自带的 Chromium。
 * 需要强制指定时设 E2E_CHANNEL=msedge|chrome|chromium。
 */
const edgePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const channel =
  process.env.E2E_CHANNEL ??
  (process.platform === 'win32' && existsSync(edgePath) ? 'msedge' : undefined)

const serverEnv = {
  ...(Object.fromEntries(
    Object.entries(process.env).filter(([, value]) => value !== undefined),
  ) as Record<string, string>),
  TEMP: tmpDir,
  TMP: tmpDir,
}

/** 推导出的后端地址同时喂给两个子进程：被测进程与代理目标必须是同一个。 */
const backendServerEnv = {
  ...serverEnv,
  E2E_BACKEND_PORT: backendPort,
  E2E_BACKEND_URL: backendUrl,
}
const webServerEnv = {
  ...serverEnv,
  E2E_BACKEND_URL: backendUrl,
}

export default defineConfig({
  testDir: './e2e',
  outputDir: path.join(artifactsDir, 'test-results'),
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 10_000 },
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  reporter: [
    ['list'],
    ['html', { outputFolder: path.join(artifactsDir, 'report'), open: 'never' }],
  ],
  use: {
    baseURL,
    channel,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
      use: { ...devices['Desktop Chrome'], channel },
    },
    {
      name: 'chromium',
      testIgnore: /auth\.setup\.ts/,
      use: { ...devices['Desktop Chrome'], channel, storageState: adminStorage },
      dependencies: ['setup'],
    },
  ],
  webServer: manageServers
    ? [
        {
          command: 'node e2e/start-backend.mjs',
          url: `${backendUrl}/actuator/health`,
          timeout: 240_000,
          reuseExistingServer,
          env: backendServerEnv,
          gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
        },
        {
          command: 'npx vite --config e2e/vite.e2e.config.ts',
          url: 'http://127.0.0.1:3001/login',
          timeout: 90_000,
          reuseExistingServer,
          env: webServerEnv,
          gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
        },
      ]
    : undefined,
})
