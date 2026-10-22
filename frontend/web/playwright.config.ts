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
          url: 'http://127.0.0.1:8081/actuator/health',
          timeout: 240_000,
          reuseExistingServer,
          env: serverEnv,
          gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
        },
        {
          command: 'npx vite --config e2e/vite.e2e.config.ts',
          url: 'http://127.0.0.1:3001/login',
          timeout: 90_000,
          reuseExistingServer,
          env: serverEnv,
          gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
        },
      ]
    : undefined,
})
