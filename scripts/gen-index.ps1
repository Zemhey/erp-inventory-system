# ════════════════════════════════════════════════════════════════════════════
# gen-index.ps1：scripts/gen-index.sh 的 Windows 原生等价实现
#
# 存在原因：gen-index.sh 依赖 bash + find + awk + python3。
# 本项目的主开发环境是 Windows（见 ADR-0010），而本机 WSL 只装了
# docker-desktop 发行版，没有可用的 bash 工具链 —— 于是"刷新索引"这一步
# 反而成了只能在 CI 上完成的事，违背了 docs/00 §4「一条命令重生成全部索引」。
#
# 本脚本与 gen-index.sh **保持相同的产物格式与相同的前置条件策略**：
#   · 不写时间戳（保证产物确定性，否则 index-check 永远失败）
#   · 缺少前置条件时"跳过"而不是写占位内容（否则 CI 与本地结果不一致）
#   · --check 只校验三份纯由源码决定的产物
#
# 用法：
#   .\scripts\gen-index.ps1            # 重新生成
#   .\scripts\gen-index.ps1 -Check     # CI 模式：有差异则失败
# ════════════════════════════════════════════════════════════════════════════
[CmdletBinding()]
param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (Resolve-Path (Split-Path -Parent $ScriptDir)).Path
$IndexDir = Join-Path $RootDir 'index'
$Contract = Join-Path $RootDir 'packages/api-contract/openapi.yaml'
$ServerDir = Join-Path $RootDir 'apps/server'

if (-not (Test-Path $IndexDir)) { New-Item -ItemType Directory -Path $IndexDir -Force | Out-Null }

# ★ 统一按 UTF-8（无 BOM）+ LF 写文件。
#   必须与 bash 版产物的字节完全一致，否则 CI 的 index-check 会把
#   "换行风格/编码差异"误判成"索引过期"。
function Write-Text($path, $lines) {
    $content = ($lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
}

Write-Host '=== 生成代码索引 ===' -ForegroundColor Cyan
Write-Host "root: $RootDir"
if ($Check) { Write-Host '模式: -Check（有差异则失败）' }

# ─────────────────────────── 1. modules.md ───────────────────────────
function Gen-Modules {
    $out = Join-Path $IndexDir 'modules.md'
    $lines = @(
        '# 模块索引（自动生成，请勿手写）',
        '',
        '> 生成脚本：`scripts/gen-index.sh` · 来源：`apps/server/src/main/java/com/erp/`',
        '',
        '| 模块 | 职责 | Java 文件 | 代码行 | 测试文件 |',
        '|---|---|---|---|---|'
    )

    $srcRoot = Join-Path $ServerDir 'src/main/java/com/erp'
    if (Test-Path $srcRoot) {
        $dirs = Get-ChildItem $srcRoot -Directory | Sort-Object Name
        foreach ($d in $dirs) {
            $mod = $d.Name
            $files = @(Get-ChildItem $d.FullName -Recurse -Filter '*.java' -File)
            $fileCount = $files.Count
            # 代码行数与 bash 版 `cat + wc -l` 等价：统计换行符数量
            $lineCount = 0
            foreach ($f in $files) {
                $raw = [System.IO.File]::ReadAllText($f.FullName)
                $lineCount += ([regex]::Matches($raw, "`n")).Count
            }
            $testDir = Join-Path $ServerDir "src/test/java/com/erp/$mod"
            $testCount = 0
            if (Test-Path $testDir) {
                $testCount = @(Get-ChildItem $testDir -Recurse -Filter '*.java' -File).Count
            }
            $duty = switch ($mod) {
                'iam'    { '认证、RBAC、数据权限' }
                'mdm'    { '商品/SKU/供应商/客户/仓库' }
                'inv'    { '库存余额、流水、预占、调拨、FEFO' }
                'pur'    { '采购进货单与入库' }
                'sal'    { '销售出货单与出库' }
                'rma'    { '退货入库（逆向）' }
                'set'    { '日清月结、对账' }
                'rpt'    { '统计报表（只读）' }
                'sys'    { '幂等、审计、测试钩子、NL/MCP' }
                'shared' { '通用：时间、金额、错误码' }
                default  { '（未登记，请补充 docs/03 模块表）' }
            }
            $lines += "| ``$mod`` | $duty | $fileCount | $lineCount | $testCount |"
        }
    } else {
        $lines += '| （源码目录尚未创建） | — | 0 | 0 | 0 |'
    }

    $lines += ''
    Write-Text $out $lines
    Write-Host '  ✓ modules.md' -ForegroundColor Green
}

# ─────────────────────────── 2. api-map.md ───────────────────────────
function Gen-ApiMap {
    $out = Join-Path $IndexDir 'api-map.md'
    $lines = @(
        '# 接口索引（自动生成，请勿手写）',
        '',
        '> 来源：`packages/api-contract/openapi.yaml`（单一事实源，ADR-0008）',
        '',
        '| 方法 | 路径 | operationId | 摘要 |',
        '|---|---|---|---|'
    )

    if (Test-Path $Contract) {
        # 与 awk 版逻辑一致：path 行记路径；method 行开启一个操作；
        # 收集 operationId 与 summary；读到 responses 时才输出该行
        # （因为 YAML 里 summary 常出现在 operationId 之前）。
        $path = ''
        $method = ''
        $op = ''
        $sum = ''
        $inOp = $false
        foreach ($rawLine in [System.IO.File]::ReadAllLines($Contract)) {
            if ($rawLine -match '^  (/[^ ]*):$') {
                $path = $Matches[1]
                continue
            }
            if ($rawLine -match '^    (get|post|put|patch|delete):$') {
                $method = $Matches[1].ToUpperInvariant()
                $inOp = $true
                $op = ''
                $sum = ''
                continue
            }
            if ($inOp -and $rawLine -match '^\s+operationId:\s*(.*)$') {
                $op = $Matches[1]
                continue
            }
            if ($inOp -and $rawLine -match '^\s+summary:\s*(.*)$') {
                $sum = $Matches[1]
                continue
            }
            if ($inOp -and $rawLine -match '^\s+responses:$') {
                $lines += "| $method | ``$path`` | ``$op`` | $sum |"
                $inOp = $false
            }
        }
    } else {
        $lines += '| — | — | — | （契约尚未创建） |'
    }

    $lines += ''
    Write-Text $out $lines
    Write-Host '  ✓ api-map.md' -ForegroundColor Green
}

# ─────────────────────────── 3. test-map.md ───────────────────────────
function Gen-TestMap {
    $out = Join-Path $IndexDir 'test-map.md'
    $lines = @(
        '# 测试用例索引（自动生成，请勿手写）',
        '',
        '> 来源：扫源码中的 `@CaseId` 注解 + 追踪矩阵（docs/00-索引与追踪矩阵.md）',
        '',
        '| 用例ID | 用例名称 | 所在文件 |',
        '|---|---|---|'
    )

    $found = 0
    $testRoot = Join-Path $ServerDir 'src/test/java'
    if (Test-Path $testRoot) {
        $javaFiles = Get-ChildItem $testRoot -Recurse -Filter '*.java' -File |
            Sort-Object FullName
        foreach ($f in $javaFiles) {
            $rel = $f.FullName.Substring($RootDir.Length + 1).Replace('\', '/')
            $cid = ''
            foreach ($line in [System.IO.File]::ReadAllLines($f.FullName)) {
                if ($line -match '@CaseId\(') {
                    if ($line -match '"([^"]+)"') { $cid = $Matches[1] }
                    continue
                }
                if ($cid -ne '' -and $line -match 'DisplayName\s*\(') {
                    # ★ 匹配用 `DisplayName(` 而不是 `@DisplayName(`：
                    #   注解可能写成全限定形式 `@org.junit.jupiter.api.DisplayName(...)`，
                    #   要求 @ 紧邻 DisplayName 会漏掉这类写法（M1 真实踩到：3 条架构用例被静默丢弃）。
                    #   名字取「括号内整体内容再去掉首尾引号」，而不是「第一对引号」——
                    #   用例名里本身可能带引号，取第一对会截断。
                    $name = ''
                    $m = [regex]::Match($line, 'DisplayName\s*\((.*)\)\s*$')
                    if ($m.Success) {
                        $inner = $m.Groups[1].Value.Trim()
                        if ($inner.Length -ge 2 -and $inner.StartsWith('"') -and $inner.EndsWith('"')) {
                            $name = $inner.Substring(1, $inner.Length - 2)
                        } else {
                            $name = $inner
                        }
                    }
                    $lines += "| ``$cid`` | $name | ``$rel`` |"
                    $cid = ''
                    $found++
                }
            }
        }
    }

    if ($found -eq 0) {
        $lines += '| （尚未登记测试用例） | — | — |'
    }

    $lines += ''
    $lines += "共 $found 条用例。"
    Write-Text $out $lines
    Write-Host "  ✓ test-map.md（$found 条）" -ForegroundColor Green
}

# ─────────────────────────── 4. coverage-map.md ───────────────────────────
function Gen-Coverage {
    $out = Join-Path $IndexDir 'coverage-map.md'
    $jacoco = Join-Path $ServerDir 'build/reports/jacoco/test/jacocoTestReport.xml'

    # ★ 与 deps.json 同理：覆盖率报告是测试运行的副产品，没有就跳过不生成，
    #   否则"有报告的机器"与"没报告的机器"会产出不同内容，制造假警报。
    if (-not (Test-Path $jacoco)) {
        Write-Host '  ⊘ coverage-map.md（跳过：无 JaCoCo 报告；先跑 jacocoTestReport）' -ForegroundColor Yellow
        return
    }

    $lines = @(
        '# 覆盖率索引（自动生成，请勿手写）',
        '',
        '> 来源：JaCoCo 报告（由 CI 或本地 `./gradlew jacocoTestReport` 产出）',
        '',
        '| 指标 | 覆盖 / 总数 | 比例 |',
        '|---|---|---|'
    )

    # ★ 关键：JaCoCo 导出的 XML 整个文件是一行（无换行）。
    #   报告级总计在 <report> 收尾处，是该类型 counter 的最后一次出现。
    $xmlText = [System.IO.File]::ReadAllText($jacoco)
    foreach ($t in @('LINE', 'BRANCH')) {
        $matches_ = [regex]::Matches($xmlText, "<counter type=`"$t`"[^>]*/>")
        if ($matches_.Count -eq 0) { continue }
        $last = $matches_[$matches_.Count - 1].Value
        $missed = [int]([regex]::Match($last, 'missed="(\d+)"')).Groups[1].Value
        $covered = [int]([regex]::Match($last, 'covered="(\d+)"')).Groups[1].Value
        $total = $missed + $covered
        $label = if ($t -eq 'LINE') { '行覆盖' } else { '分支覆盖' }
        $pct = if ($total -gt 0) { '{0:N1}' -f (100.0 * $covered / $total) } else { '0.0' }
        $pct = $pct -replace ',', '.'
        $lines += "| $label | $covered / $total | ${pct}% |"
    }
    $lines += ''
    $lines += '原始报告：`apps/server/build/reports/jacoco/test/html/index.html`'
    $lines += ''
    Write-Text $out $lines
    Write-Host '  ✓ coverage-map.md' -ForegroundColor Green
}

# ─────────────────────────── 执行 ───────────────────────────
Gen-Modules
Gen-ApiMap
Gen-TestMap
Gen-Coverage

# deps.json 需要 JDK + gradle 输出，且本机依赖缓存位于工作区内（见 .gitignore）。
# 本脚本不重写它 —— 与 bash 版"缺前置条件则跳过"的策略一致，保留已有产物。
if (-not (Test-Path (Join-Path $IndexDir 'deps.json'))) {
    Write-Host '  ⊘ deps.json（跳过：需 gradle dependencies，请用 gen-index.sh 生成）' -ForegroundColor Yellow
}

# ─────────────────────────── -Check 模式 ───────────────────────────
if ($Check) {
    Write-Host ''
    Write-Host '--- 校验索引与已提交内容是否一致 ---'
    $checkedFiles = @('index/modules.md', 'index/api-map.md', 'index/test-map.md')
    $drift = 0
    foreach ($rel in $checkedFiles) {
        $diff = & git -C $RootDir diff --quiet -- $rel 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "✗ 已过期: $rel" -ForegroundColor Red
            $drift = 1
        }
    }
    if ($drift -ne 0) {
        Write-Host ''
        Write-Host '请运行 ./scripts/gen-index.sh（或 gen-index.ps1）并提交结果。' -ForegroundColor Red
        & git -C $RootDir --no-pager diff --stat -- @checkedFiles
        exit 1
    }
    Write-Host "✓ 索引与已提交内容一致（校验 $($checkedFiles.Count) 份确定性产物）" -ForegroundColor Green
}

Write-Host ''
Write-Host '完成。产物目录：index/'
