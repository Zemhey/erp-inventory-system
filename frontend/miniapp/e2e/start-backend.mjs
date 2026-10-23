import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readdirSync, readlinkSync, realpathSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 为移动端 E2E 启动后端（8085）。
 * 与 Web E2E 同一纪律：
 * - 构建必须走 ASCII 联接 D:\erp-build-m4b，且联接必须指向**当前 worktree**；
 * - 用 bootJar + java -jar 而不是 bootRun，停止时能干净回收进程。
 */

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '../../..')
const isWin = process.platform === 'win32'
const junctionRoot = process.env.E2E_JUNCTION ?? 'D:\\erp-build-m4b'

function normalize(p) {
  try {
    return path.resolve(realpathSync(p)).toLowerCase().replace(/[\\/]$/, '')
  } catch {
    return null
  }
}

function junctionOwner(link) {
  try {
    return normalize(path.resolve(path.dirname(link), readlinkSync(link)))
  } catch {
    return normalize(link)
  }
}

const owner = junctionOwner(junctionRoot)
const mine = normalize(repoRoot)
if (owner !== null && owner !== mine) {
  console.error(
    `[e2e] 拒绝启动：${junctionRoot} 指向 ${owner}，而当前 worktree 是 ${mine}。\n` +
      '        用别的联接构建会把另一条泳道的代码当成被测对象。',
  )
  process.exit(1)
}
if (owner === null) {
  console.warn(`[e2e] 未发现 ASCII 联接 ${junctionRoot}，直接在仓库内构建（中文路径下 Gradle 可能报误导性错误）`)
}

const backendDir = owner === null
  ? path.join(repoRoot, 'backend/server')
  : path.join(junctionRoot, 'backend/server')

const port = process.env.E2E_BACKEND_PORT ?? '8085'
const dbUrl = process.env.DB_URL ?? 'jdbc:postgresql://127.0.0.1:15441/erp'

if (process.env.E2E_SKIP_BUILD !== '1') {
  console.log(`[e2e] 构建后端 jar：${backendDir}`)
  const command = isWin ? (process.env.ComSpec ?? 'cmd.exe') : './gradlew'
  const args = isWin
    ? ['/d', '/c', 'gradlew.bat', 'bootJar', '--no-daemon']
    : ['bootJar', '--no-daemon']
  const build = spawnSync(command, args, {
    cwd: backendDir,
    env: process.env,
    stdio: 'inherit',
  })
  if (build.error) {
    console.error(`[e2e] 无法执行 Gradle：${build.error.message}`)
    process.exit(1)
  }
  if (build.status !== 0) process.exit(build.status ?? 1)
}

const libsDir = path.join(backendDir, 'build', 'libs')
const jars = existsSync(libsDir)
  ? readdirSync(libsDir)
      .filter((name) => name.endsWith('.jar') && !name.endsWith('-plain.jar'))
      .map((name) => path.join(libsDir, name))
      .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
  : []
const jar = process.env.E2E_BACKEND_JAR ?? jars[0]
if (!jar || !existsSync(jar)) {
  console.error(`[e2e] 找不到 Spring Boot jar（${libsDir}）；请先成功执行 gradlew bootJar`)
  process.exit(1)
}

console.log(`[e2e] 启动后端：${path.basename(jar)}  SERVER_PORT=${port}  DB=${dbUrl}`)
const child = spawn('java', ['-jar', jar], {
  cwd: backendDir,
  env: { ...process.env, SERVER_PORT: port, DB_URL: dbUrl },
  stdio: 'inherit',
})

let stopping = false
function stop() {
  if (stopping) return
  stopping = true
  if (isWin) {
    spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
  setTimeout(() => process.exit(0), 500).unref()
}

process.on('SIGINT', stop)
process.on('SIGTERM', stop)
child.on('error', (error) => {
  console.error(`[e2e] 后端启动失败：${error.message}`)
  process.exit(1)
})
child.on('exit', (code) => {
  if (!stopping) process.exit(code ?? 0)
})
