import { spawnSync } from 'node:child_process'

/**
 * E2E 自有 PostgreSQL（默认宿主 15441）。
 * 由 `pree2e` 在 Playwright 启动 webServer 之前调用 —— 后端比 globalSetup 更早启动，
 * 所以数据库容器不能等到 globalSetup 才创建。
 *
 * ★ 并行泳道必须用 E2E_DB_CONTAINER / E2E_DB_PORT 换成自己的容器与端口，
 *   且要和传给后端的 DB_URL 指向同一个库，否则迁移落在 A 库、断言读 B 库。
 */

const CONTAINER = process.env.E2E_DB_CONTAINER ?? 'erp-m4b-pg'
const IMAGE = process.env.E2E_DB_IMAGE ?? 'postgres:16-alpine'
const PORT = Number(process.env.E2E_DB_PORT ?? 15441)

function docker(args, options = {}) {
  return spawnSync('docker', args, {
    encoding: 'utf8',
    timeout: 120_000,
    ...options,
  })
}

function fail(message, result) {
  console.error(`[e2e:db] ${message}`)
  if (result?.stdout) console.error(result.stdout)
  if (result?.stderr) console.error(result.stderr)
  process.exit(1)
}

const inspect = docker(['inspect', '-f', '{{.State.Running}}', CONTAINER])
if (inspect.error) fail(`无法执行 docker：${inspect.error.message}`, inspect)

if (inspect.status !== 0) {
  console.log(`[e2e:db] 创建容器 ${CONTAINER}（${IMAGE}，宿主 ${PORT}）`)
  const created = docker([
    'run',
    '-d',
    '--name',
    CONTAINER,
    '-e',
    'POSTGRES_DB=erp',
    '-e',
    'POSTGRES_USER=erp',
    '-e',
    'POSTGRES_PASSWORD=erp',
    '-p',
    `${PORT}:5432`,
    IMAGE,
  ])
  if (created.status !== 0) {
    fail(
      `创建容器失败。若本机没有 ${IMAGE} 镜像，请先在有网络的环境导入；` +
        '不要改用共享库 15432。',
      created,
    )
  }
} else if (inspect.stdout.trim() !== 'true') {
  console.log(`[e2e:db] 启动已存在的容器 ${CONTAINER}`)
  const started = docker(['start', CONTAINER])
  if (started.status !== 0) fail('启动容器失败', started)
} else {
  console.log(`[e2e:db] 容器 ${CONTAINER} 已在运行`)
}

const deadline = Date.now() + 60_000
for (;;) {
  const ready = docker(['exec', CONTAINER, 'pg_isready', '-U', 'erp', '-d', 'erp'])
  if (ready.status === 0) {
    console.log(`[e2e:db] PostgreSQL 已就绪（${CONTAINER} → 127.0.0.1:${PORT}）`)
    break
  }
  if (Date.now() > deadline) {
    fail('等待 PostgreSQL 就绪超时', ready)
  }
  await new Promise((resolve) => setTimeout(resolve, 1000))
}
