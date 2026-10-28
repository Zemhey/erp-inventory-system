# ============================================================================
# 一键启动「云裳进销存」系统本机服务（Windows / PowerShell 5.1）
#
# 只启动系统本身运行所需要的两件事：
#   1. 后端 Spring Boot（:8080）
#   2. Web 前端 Vite 开发服务器（:3030）
# 数据库等容器只在“后端没起来”时才确认，后端已健康就完全不动 Docker。
#
# ★ 启动范围是刻意收窄的，不要往这里加东西：
#   不启动 app\（Android 演示包）、app\uniapp（小程序 / H5）、app\desktop（桌面端）、
#   第二个浏览器窗口，也不启动 packages\mcp-server（它由 AI Agent 按需以 stdio 拉起，
#   见 docs\MCP-接入指南.md）。
#
# 已就绪的服务一律复用，不重启 —— 用户手动开着 8080 / 3030 时不会被打断。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-system.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-system.ps1 -NoBrowser
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-system.ps1 -SkipInfra
# ============================================================================
[CmdletBinding()]
param(
    [int]$BackendPort = 8080,
    [int]$WebPort = 3030,
    [switch]$NoBrowser,
    [switch]$SkipInfra,

    # 以下两个参数已废弃：本脚本不再启动手机端。
    # 保留是为了让旧快捷方式不因“未知参数”直接报错，只会给出提示。
    [int]$MobilePort = 0,
    [switch]$RebuildH5
)

$ErrorActionPreference = 'Stop'

# ★ 控制台编码必须和 cmd 的代码页一致，否则中文全变乱码。
#   这个脚本存成 UTF-8(带 BOM) 是为了 PowerShell 能正确解析源码；
#   但**输出**到控制台时，cmd 默认用本机 ANSI 代码页（简中 = 936/GBK）接收。
#   之前硬写 UTF8 输出 + cmd 按 GBK 接收 = 满屏「鎺ュ彛鏂囨。」。
#
#   注意：不能去读 [Console]::OutputEncoding 再"沿用"——重定向到文件时它已经是 UTF-8，
#   沿用等于没改。这里直接取系统 ANSI 代码页（GetACP），它才代表 cmd 的接收编码。
try {
    $ansiCp = [System.Text.Encoding]::Default.CodePage
    if (-not $ansiCp -or $ansiCp -eq 65001) {
        # .NET Core 下 Default 恒为 UTF-8，退回用系统 ACP
        $ansiCp = 936
        try {
            $acp = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Nls\CodePage' -Name ACP -ErrorAction Stop).ACP
            if ($acp) { $ansiCp = [int]$acp }
        } catch {}
    }
    [Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding($ansiCp)
    $OutputEncoding = [System.Text.Encoding]::GetEncoding($ansiCp)
} catch {}
# 管道被重定向（非交互）时不要再问"按任意键"
$script:Interactive = -not [Console]::IsInputRedirected

$Root = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $Root '.run'
$LogDir = Join-Path $RunDir 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# 自然语言模型（DeepSeek 等）的配置不在这里注入：
# 它存在系统参数里、在「系统 → 自然语言 → AI 接入」页面填写，保存即生效。
# 刻意不提供 .env / 环境变量 的第二种入口 —— 两个地方都能配，
# 出问题时就无法判断"到底是哪一份生效"，这类问题排查成本极高。

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "=== $Text ===" -ForegroundColor Cyan
}
function Write-Ok([string]$Text) { Write-Host "  [OK]   $Text" -ForegroundColor Green }
function Write-Warn([string]$Text) { Write-Host "  [WARN] $Text" -ForegroundColor Yellow }
function Write-Fail([string]$Text) { Write-Host "  [FAIL] $Text" -ForegroundColor Red }
function Write-Info([string]$Text) { Write-Host "         $Text" -ForegroundColor DarkGray }

# 失败时把日志尾巴直接贴到控制台。
# 冷启动失败（端口冲突 / 数据库没起来 / 编译报错）时，用户对着一个"启动失败"
# 是没法自救的；让他自己去 .run\logs 里翻几 MB 的日志也不现实。
function Show-LogTail([string]$Path, [int]$Lines = 12) {
    if (-not (Test-Path $Path)) { return }
    $tail = Get-Content -Path $Path -Tail $Lines -ErrorAction SilentlyContinue
    if (-not $tail) { return }
    Write-Host "         ---- $([System.IO.Path]::GetFileName($Path)) 最后 $Lines 行 ----" -ForegroundColor DarkGray
    foreach ($line in $tail) { Write-Host "         $line" -ForegroundColor DarkGray }
}

function Test-Http([string]$Url, [int]$TimeoutSec = 3) {
    try {
        # ★ 必须绕过系统代理。公司机器常配了 HTTP 代理，Invoke-WebRequest 默认
        #   会把 127.0.0.1 也丢给代理，于是"服务明明已经起来"却探测失败，
        #   脚本转而去 Docker + 再拉一个后端 —— 最后卡在端口冲突上，报的却是
        #   "找不到数据库"，非常难查。这里直连，不走任何代理。
        $request = [System.Net.HttpWebRequest]::Create($Url)
        $request.Proxy = $null
        $request.Timeout = $TimeoutSec * 1000
        $request.ReadWriteTimeout = $TimeoutSec * 1000
        $request.Method = 'GET'
        $request.AllowAutoRedirect = $true
        $response = $request.GetResponse()
        try {
            return ([int]$response.StatusCode -eq 200)
        } finally {
            $response.Close()
        }
    } catch {
        return $false
    }
}

# 等待某个 HTTP 端就绪。每 15 秒打一次进度，别让人对着静止窗口猜。
# 冷启动后端实测要 5–7 分钟（Gradle 编译 + Tomcat 初始化），不是文档里写的 1–3 分钟，
# 所以默认给足 600 秒。
function Wait-Http([string]$Url, [int]$Seconds, [string]$Name) {
    $start = Get-Date
    $deadline = $start.AddSeconds($Seconds)
    $lastNote = 0
    while ((Get-Date) -lt $deadline) {
        if (Test-Http $Url 3) {
            Write-Ok "$Name ready: $Url"
            return $true
        }
        $elapsed = [int]((Get-Date) - $start).TotalSeconds
        if ($elapsed - $lastNote -ge 15) {
            Write-Info "$Name 启动中 ... 已等待 ${elapsed}s / 最多 ${Seconds}s"
            $lastNote = $elapsed
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# ★ 关键修复 4（本次"双击没反应"的真凶之一）：
#   原来用 Start-Process 起进程。它内部会把当前进程的环境变量逐个塞进一个
#   **区分大小写**的字典，而本机同时存在 http_proxy 和 HTTP_PROXY（Clash / 公司代理
#   这类工具常见），于是直接抛：
#     "已添加项。字典中的关键字:“http_proxy”所添加的关键字:“HTTP_PROXY”"
#   表现就是：Docker 检查过了、日志里没有任何后端记录，窗口一闪就退。
#
#   现在改用 .NET 直接起进程：不复制环境字典（子进程直接继承父进程环境块），
#   输出用 cmd 自己的重定向写文件 —— 顺带解决了"重定向管道被写满导致子进程阻塞"
#   这个只在长时间编译时才暴露的隐患。
function Start-Background([string]$File, [string[]]$Arguments, [string]$WorkDir, [string]$OutLog, [string]$ErrLog) {
    # 第一个参数固定是 /c，其余拼成命令行；日志路径可能带空格，用引号包住
    $inner = (($Arguments | Select-Object -Skip 1) -join ' ')
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $File
    $psi.Arguments = '/c ' + $inner + ' 1> "' + $OutLog + '" 2> "' + $ErrLog + '"'
    $psi.WorkingDirectory = $WorkDir
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    # 不碰 $psi.EnvironmentVariables：一旦开始逐项复制，就会踩上面那个大小写重复的坑
    [void][System.Diagnostics.Process]::Start($psi)
}

# ★ 关键修复 3（就是它导致"双击后一直起不来"）：
#   原来直接用 $env:ComSpec 当 FilePath，但这个变量在部分环境里是空的 ——
#   精简过的会话、被清理过的环境变量、某些远程/托管终端都会这样。
#   空值传给 Start-Process 会抛
#     "无法对参数 FilePath 执行参数验证。参数为 Null 或空。"
#   而后端与前端都要靠它拉起，于是整个启动链在这里无声地断掉。
#   这里给出三层兜底，最后才退回裸命令名。
function Get-ComSpec {
    if ($env:ComSpec -and (Test-Path -LiteralPath $env:ComSpec)) { return $env:ComSpec }
    if ($env:SystemRoot) {
        $fallback = Join-Path $env:SystemRoot 'System32\cmd.exe'
        if (Test-Path -LiteralPath $fallback) { return $fallback }
    }
    return 'cmd.exe'
}

# ★ 关键修复 5：子进程里找不到 npm。
#   本脚本用 Start-Process / .NET 起的子进程不一定继承到和你终端完全一样的 PATH
#   （尤其是 node 装在 D:\Software\Node 这类非默认目录、只在用户 PATH 里注册的情况），
#   于是 `npm run dev` 直接报 "'npm' 不是内部或外部命令"，前端永远起不来，
#   而后端插件式地占着 8080，看起来像"卡住了"。
#   这里把 npm 的绝对路径找出来再用，找不到就老实报错，不再让用户猜。
# ★ 关键修复 7：Get-Command 依赖 PATHEXT。在**部分会话里 PATHEXT 是空的**
#   （精简环境、被 Agent / 守护进程拉起的终端、某些远程会话），此时
#   `Get-Command node` 连 node.exe 都找不到 —— 因为 .EXE 也要靠 PATHEXT 匹配。
#   于是脚本认定"没装 Node"，把前端永远起不来当成"没装环境"报给用户。
#   兜底：直接扫 PATH 的每一项去找 exe，这一步不依赖 PATHEXT。
function Find-InPath([string]$ExeName) {
    if (-not $env:PATH) { return $null }
    foreach ($dir in ($env:PATH -split ';')) {
        if (-not $dir) { continue }
        $candidate = Join-Path ($dir.Trim().Trim('"')) $ExeName
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

function Get-NpmPath {
    foreach ($name in @('npm.cmd', 'npm')) {
        $found = Get-Command $name -ErrorAction SilentlyContinue
        if ($found -and $found.Source) { return $found.Source }
    }
    $node = Get-NodePath
    if ($node) {
        $candidate = Join-Path (Split-Path -Parent $node) 'npm.cmd'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $dirs = @("$env:ProgramFiles\nodejs", "$env:APPDATA\npm", 'D:\Software\Node', 'C:\Program Files\nodejs')
    foreach ($dir in $dirs) {
        if (-not $dir) { continue }
        $candidate = Join-Path $dir 'npm.cmd'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

# ★ 关键修复 6：npm run dev 依赖"把 node_modules/.bin 塞进 PATH"，
#   在受限会话 / 便携版 Node / 被改写过的 PATHEXT 下会失败，报
#   "'vite' 不是内部或外部命令" —— 但 npm 本身明明跑起来了（会先打印
#   "> erp-web@0.1.0 dev"），所以特别容易被误判成 Vite 装坏了。
#   直接定位 node.exe 与 vite 的入口 js，不经过 PATH，行为就确定了。
function Get-NodePath {
    $found = Get-Command node -ErrorAction SilentlyContinue
    if ($found -and $found.Source) { return $found.Source }
    # PATHEXT 为空时 Get-Command 找不到 node.exe，退回手工扫 PATH
    $fromPath = Find-InPath 'node.exe'
    if ($fromPath) { return $fromPath }
    $dirs = @("$env:ProgramFiles\nodejs", 'D:\Software\Node', 'C:\Program Files\nodejs')
    foreach ($dir in $dirs) {
        if (-not $dir) { continue }
        $candidate = Join-Path $dir 'node.exe'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

# ★ 关键修复 1：以前这里直接 `& powershell.exe @all`，子脚本的每一行输出都会
#   混进函数的返回值，调用方拿到的是 24 行字符串数组而不是退出码，
#   于是 `if ($code -ne 0)` 恒为真 —— 容器明明全是 healthy，却每次都报失败。
# ★ 关键修复 2：后来改成 `& powershell.exe @all 2>&1 | ForEach-Object {...}`，
#   在部分环境（带 profile 包装 / 受限 shell）会直接抛
#   「无法在管道中间运行文档: ...powershell.exe」，整条启动链就此中断。
#   现在：不接管道，子进程直接继承控制台（输出照样看得见），
#   只把真正的 $LASTEXITCODE 返回给调用方。
function Invoke-ChildPowerShell([string]$ScriptPath, [string[]]$Arguments = @()) {
    $all = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & powershell.exe @all
        return [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

$backendHealth = "http://127.0.0.1:$BackendPort/actuator/health"
$webUrl = "http://127.0.0.1:$WebPort/"

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  云裳进销存 · 启动本机系统' -ForegroundColor Cyan
Write-Host '  （只启动后端与 Web 前端）' -ForegroundColor DarkGray
Write-Host '============================================' -ForegroundColor Cyan

if ($MobilePort -ne 0 -or $RebuildH5) {
    Write-Warn '-MobilePort / -RebuildH5 已废弃：本脚本不再启动手机 H5 预览，参数已忽略。'
}

# ---------------------------------------------------------------------------
# 0. 后端若已健康，就完全跳过 Docker —— 不制造无谓的副作用
# ---------------------------------------------------------------------------
$backendAlreadyUp = Test-Http $backendHealth 3

Write-Step "1/2 后端 Spring Boot (:$BackendPort)"
if ($backendAlreadyUp) {
    Write-Ok "后端已在运行，直接复用：$backendHealth"
    Write-Info '（后端已就绪，跳过 Docker 检查）'
} elseif (Test-TcpPort $BackendPort) {
    # 端口开着但健康检查没过：很可能是自己上一次启动的后端还在编译/初始化。
    # 这里不该报错退出，而应该等它把 /actuator/health 变成 200。
    Write-Warn "端口 $BackendPort 已占用但尚未健康，可能正在启动中 —— 等它就绪。"
    if (-not (Wait-Http $backendHealth 600 '后端')) {
        Write-Fail "等了 600 秒，$BackendPort 上的进程仍未健康。"
        Write-Info "看是谁占着：netstat -ano | findstr :$BackendPort"
        throw '端口被占且始终不健康。'
    }
} else {

    # 数据库是后端的依赖，只在需要拉起后端时才确认
    if ($SkipInfra) {
        Write-Warn '按 -SkipInfra 跳过数据库容器检查。'
    } else {
        Write-Info '确认数据库容器（PostgreSQL / Redis / RabbitMQ）...'
        $code = Invoke-ChildPowerShell (Join-Path $Root 'scripts\dev-up.ps1')
        if ($code -ne 0) {
            throw '数据库容器没有就绪。多半是 Docker Desktop 没启动，先打开它再重试。'
        }
    }

    # 项目路径含中文，Gradle 测试有编码问题，构建统一走 ASCII 联接路径
    $buildRoot = 'D:\erp-build'
    if (-not (Test-Path (Join-Path $buildRoot 'backend\server\gradlew.bat'))) {
        Write-Info '创建 ASCII 构建联接 D:\erp-build ...'
        $code = Invoke-ChildPowerShell (Join-Path $Root 'scripts\build-env.ps1')
        if ($code -ne 0) { throw 'build-env.ps1 失败，无法创建构建目录。' }
    }

    $serverDir = Join-Path $buildRoot 'backend\server'
    if (-not (Test-Path (Join-Path $serverDir 'gradlew.bat'))) {
        throw "找不到 $serverDir\gradlew.bat，请先执行 scripts\build-env.ps1。"
    }

    Write-Info '启动 Spring Boot（冷启动实测约 5–7 分钟，会一直显示进度）...'
    Start-Background (Get-ComSpec) @('/c', 'gradlew.bat bootRun --console=plain') $serverDir `
        (Join-Path $LogDir 'backend.out.log') (Join-Path $LogDir 'backend.err.log')

    if (-not (Wait-Http $backendHealth 600 '后端')) {
        Write-Fail '后端未在 600 秒内就绪。'
        Write-Info "日志：$(Join-Path $LogDir 'backend.out.log')"
        Write-Info "      $(Join-Path $LogDir 'backend.err.log')"
        Show-LogTail (Join-Path $LogDir 'backend.err.log')
        Show-LogTail (Join-Path $LogDir 'backend.out.log') 20
        throw '后端启动失败。'
    }
}

# ---------------------------------------------------------------------------
# 2. Web 前端
# ---------------------------------------------------------------------------
Write-Step "2/2 Web 前端 (:$WebPort)"
if (Test-Http $webUrl 3) {
    Write-Ok "Web 已在运行，直接复用：$webUrl"
} elseif (Test-TcpPort $WebPort) {
    # 同后端：端口开着但还没返回 200，通常是 Vite 正在启动
    Write-Warn "端口 $WebPort 已占用但尚未就绪，等它起来。"
    if (-not (Wait-Http $webUrl 180 'Web')) {
        Write-Fail "等了 180 秒，$WebPort 上的进程仍未就绪。"
        Write-Info "看是谁占着：netstat -ano | findstr :$WebPort"
        throw '端口被占且始终不健康。'
    }
} else {
    $webDir = Join-Path $Root 'frontend\web'

    # 只有真正需要装依赖时才要 npm；启动本身只要 node。
    if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
        $npm = Get-NpmPath
        if (-not $npm) {
            throw '首次运行需要安装前端依赖，但找不到 npm。请安装 Node.js（>=18）后重试。'
        }
        Write-Info "首次运行，安装前端依赖（几分钟，npm：$npm）..."
        Push-Location $webDir
        try {
            & $npm install
            if ($LASTEXITCODE -ne 0) { throw 'frontend/web 的 npm install 失败。' }
        } finally {
            Pop-Location
        }
    }

    $webOut = Join-Path $LogDir 'web.out.log'
    $webErr = Join-Path $LogDir 'web.err.log'
    Write-Info '启动 Vite 开发服务器 ...'

    # ★ 关键修复 8：直接用 node 跑 vite 的入口 js，彻底不过 npm、不过 PATH。
    #   `npm run dev` 要同时满足两件事才能成功：PATHEXT 里有 .CMD，且 npm 把
    #   node_modules\.bin 注入了子进程 PATH。两者任一异常（本机就实测 PATHEXT 为空）
    #   都会报 "'vite' 不是内部或外部命令" —— 看起来像依赖没装好，实际是环境问题，
    #   排查方向会被带偏。这里直接指定 node.exe 和 vite.js 的绝对路径，行为确定。
    $node = Get-NodePath
    if (-not $node) {
        throw '找不到 node.exe。请安装 Node.js（>=18），或把 node 安装目录加入 PATH 后重试。'
    }
    $viteEntry = Join-Path $webDir 'node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $viteEntry)) {
        Write-Warn 'node_modules 里没有 vite（依赖不完整），补装一次 ...'
        $npm = Get-NpmPath
        if (-not $npm) { throw "缺少 $viteEntry，且找不到 npm 补装。请手动执行 cd frontend\web && npm install。" }
        Push-Location $webDir
        try {
            & $npm install
            if ($LASTEXITCODE -ne 0) { throw 'frontend/web 的 npm install 失败。' }
        } finally {
            Pop-Location
        }
        if (-not (Test-Path -LiteralPath $viteEntry)) {
            throw "补装后仍找不到 $viteEntry，请手动排查前端依赖。"
        }
    }

    Write-Info "使用 node：$node"
    $launch = "`"$node`" `"$viteEntry`""
    Start-Background (Get-ComSpec) @('/c', "set VITE_DEV_PORT=$WebPort&& $launch") $webDir $webOut $webErr

    if (-not (Wait-Http $webUrl 180 'Web')) {
        Write-Fail 'Web 未在 180 秒内就绪。'
        Write-Info "日志：$webOut"
        Write-Info "      $webErr"
        Show-LogTail $webErr
        Show-LogTail $webOut 20
        throw 'Web 启动失败。'
    }
}

# ---------------------------------------------------------------------------
# 完成
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '============================================' -ForegroundColor Green
Write-Host '  系统已就绪' -ForegroundColor Green
Write-Host '============================================' -ForegroundColor Green
Write-Host "  访问地址 : $webUrl" -ForegroundColor White
Write-Host "  后端接口 : http://127.0.0.1:$BackendPort/api/v1"
Write-Host "  接口文档 : http://127.0.0.1:$BackendPort/swagger-ui.html"
Write-Host "  运行日志 : $LogDir"
Write-Host ''
Write-Host '  服务在后台运行，关掉这个窗口不会停止它们。'
Write-Host '  要停止：结束对应的 java / node 进程即可。'
Write-Host ''
Write-Host '  本次未启动（按设计）：app\ 安卓演示、小程序 / H5、桌面端。' -ForegroundColor DarkGray
Write-Host '  AI Agent 接入：见 docs\MCP-接入指南.md（MCP 由 Agent 按需拉起，不随本脚本启动）。' -ForegroundColor DarkGray
Write-Host ''

# 只有交互式（双击 / 正常终端）才开浏览器。
# 被重定向 / 由 CI 或 Agent 调用时开浏览器没有意义，而且部分环境下
# ShellExecute 会回一句 "Input redirection is not supported" 吓人一跳。
if (-not $NoBrowser -and $script:Interactive) {
    try { Start-Process $webUrl } catch { Write-Info "自动打开浏览器失败，请手动访问 $webUrl" }
}
exit 0
