import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * 为 E2E 启动后端（8081）。
 *
 * 用 bootJar + java -jar，而不是 gradlew bootRun：
 * bootRun 会多一层 Gradle 守护进程，Playwright 停服时容易留下 Java 子进程占着 8081；
 * 直接持有 java 进程的 pid，停止时能干净地回收。
 */
const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '../../..')
const isWin = process.platform === 'win32'

const configuredDir = process.env.E2E_BACKEND_DIR
const asciiJunction = 'D:\\erp-build\\backend\\server'
const backendDir =
  configuredDir ??
  (isWin && existsSync(asciiJunction) ? asciiJunction : path.join(repoRoot, 'backend/server'))

const port = process.env.E2E_BACKEND_PORT ?? '8081'

if (!process.env.E2E_BACKEND_JAR) {
  const gradlew = isWin ? 'gradlew.bat' : './gradlew'
  console.log(`[e2e] 构建后端 jar：${backendDir}`)
  // Windows 直接 spawn .bat 需要 shell；这里改用 cmd /c，避免 Node 的 shell:true 参数拼接告警
  const buildCommand = isWin ? (process.env.ComSpec ?? 'cmd.exe') : gradlew
  const buildArgs = isWin
    ? ['/d', '/c', gradlew, 'bootJar', '--no-daemon']
    : ['bootJar', '--no-daemon']
  const build = spawnSync(buildCommand, buildArgs, {
    cwd: backendDir,
    env: process.env,
    stdio: 'inherit',
  })
  if (build.error) {
    console.error(`[e2e] 无法执行 ${gradlew}：${build.error.message}`)
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

console.log(`[e2e] 启动后端：${path.basename(jar)}  SERVER_PORT=${port}`)
const child = spawn('java', ['-jar', jar], {
  cwd: backendDir,
  env: { ...process.env, SERVER_PORT: port },
  stdio: 'inherit',
})

let stopping = false
function stop() {
  if (stopping) return
  stopping = true
  if (isWin) {
    // Java 进程可能自己再拉起线程，taskkill /T 兜底
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
