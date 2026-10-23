import { defineConfig, devices } from '@playwright/test'
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'

// 本包不是 ESM（uni-app 构建要求），Playwright 会把配置转成 CommonJS，因此用 __dirname
const miniappRoot = __dirname
const artifactsDir = path.join(miniappRoot, '.e2e-artifacts')
const adminStorage = path.join(artifactsDir, 'admin-storage.json')
const tmpDir = path.join(artifactsDir, 'tmp')
mkdirSync(artifactsDir, { recursive: true })
mkdirSync(tmpDir, { recursive: true })

const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3005'
const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8085'
const reuseExistingServer = process.env.E2E_REUSE_SERVERS === '1'

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

const mobileViewport = { width: 390, height: 844 }

export default defineConfig({
  testDir: './e2e',
  outputDir: path.join(artifactsDir, 'test-results'),
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 120_000,
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
    viewport: mobileViewport,
    deviceScaleFactor: 1,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  projects: [
    {
      name: 'setup',
      testMatch: /seed\.setup\.ts/,
      use: { ...devices['Desktop Chrome'], channel, viewport: mobileViewport },
    },
    {
      name: 'login',
      testMatch: /01-login\.spec\.ts/,
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'], channel, viewport: mobileViewport },
    },
    {
      name: 'app',
      testIgnore: [/seed\.setup\.ts/, /01-login\.spec\.ts/],
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        channel,
        viewport: mobileViewport,
        storageState: adminStorage,
      },
    },
  ],
  webServer: [
    {
      command: 'node e2e/start-backend.mjs',
      url: `${backendUrl}/actuator/health`,
      timeout: 360_000,
      reuseExistingServer,
      env: {
        ...serverEnv,
        E2E_BACKEND_PORT: new URL(backendUrl).port,
        DB_URL: process.env.DB_URL ?? 'jdbc:postgresql://127.0.0.1:15441/erp',
      },
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
    {
      command: 'npm run dev:h5',
      url: baseURL,
      timeout: 180_000,
      reuseExistingServer,
      env: {
        ...serverEnv,
        MINIAPP_DEV_PORT: new URL(baseURL).port,
        MINIAPP_API_TARGET: backendUrl,
      },
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
  ],
})
