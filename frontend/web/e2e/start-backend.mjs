import { spawn, spawnSync } from 'node:child_process'
import { existsSync, readlinkSync, readdirSync, realpathSync, statSync } from 'node:fs'
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
/**
 * ★ Windows 必须在 ASCII 联接路径下构建（M2 计划书 §6 坑 12），
 *   而**默认那个联接指向主工作区**（`D:\erp-build`）。并行开发时复用它，
 *   等于"跑我的 E2E、验别人的后端代码"（§5.6.1 第 1 条）。
 *   所以这里核对联接真正指向谁：不是当前 worktree 就直接拒绝，而不是默默构建。
 */
const junctionRoot = process.env.E2E_JUNCTION ?? 'D:\\erp-build'

/** 联接/真实目录的规范化绝对路径（Windows 大小写不敏感，统一转小写比较）。 */
function normalize(p) {
  try {
    return path.resolve(realpathSync(p)).toLowerCase().replace(/[\\/]$/, '')
  } catch {
    return null
  }
}

function junctionOwner(link) {
  try {
    // junction 的 readlink 给出的就是目标目录
    return normalize(path.resolve(path.dirname(link), readlinkSync(link)))
  } catch {
    return normalize(link)
  }
}

let backendDir = configuredDir
if (!backendDir && isWin) {
  const owner = junctionOwner(junctionRoot)
  const mine = normalize(repoRoot)
  if (owner === null) {
    console.log(`[e2e] 未发现 ASCII 联接 ${junctionRoot}，直接在仓库内构建（中文路径下 Gradle 可能报误导性错误）`)
    backendDir = path.join(repoRoot, 'backend/server')
  } else if (owner !== mine) {
    console.error(
      `[e2e] 拒绝启动：${junctionRoot} 指向 ${owner}，而当前仓库是 ${mine}。\n` +
        '        用那个联接构建会把**另一条泳道的代码**当成被测对象。\n' +
        '        请给本 worktree 建一个自己的联接（M2 计划书 §5.6.1 第 1/2 条），然后二选一：\n' +
        `          E2E_JUNCTION=D:\\erp-build-<lane> npm run e2e\n` +
        '          E2E_BACKEND_DIR=D:\\erp-build-<lane>\\backend\\server npm run e2e',
    )
    process.exit(1)
  } else {
    backendDir = path.join(junctionRoot, 'backend/server')
  }
}
backendDir = backendDir ?? path.join(repoRoot, 'backend/server')

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
