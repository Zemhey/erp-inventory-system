# ════════════════════════════════════════════════════════════════════════════
# bcrypt-hash.ps1：用 Java + Spring Security 的 BCryptPasswordEncoder 生成哈希
#
# 为什么不用 PowerShell 自己算：
#   BCrypt 的盐、迭代轮数、前缀格式都有讲究。手算极易生成"看起来像哈希、
#   但后端 matches() 永远返回 false"的字符串，而这个错误只会在登录时暴露，
#   排查时又完全看不出哪不对。直接调用与后端同一个类，是最稳的做法。
#
# 输出：仅标准输出的哈希字符串（便于被其他脚本捕获）
# ════════════════════════════════════════════════════════════════════════════
param(
    [Parameter(Mandatory = $true)]
    [string]$Password
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $repoRoot 'backend\server'
$libDir = Join-Path $serverDir 'build\libs'

# Spring Security 的 jar 从 Gradle 缓存里找（不必等待完整构建）
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { "$env:USERPROFILE\.gradle" }
$securityJar = Get-ChildItem -Path (Join-Path $gradleHome 'caches\modules-2\files-2.1\org.springframework.security') `
    -Recurse -Filter 'spring-security-crypto-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1

if (-not $securityJar) {
    Write-Error '找不到 spring-security-crypto jar，请先跑一次 .\gradlew.bat dependencies'
    exit 1
}

# BCryptPasswordEncoder 构造时会用 commons-logging 打日志，缺了会抛 NoClassDefFoundError。
# 从缓存里一并把 spring-jcl（Spring 自带的 commons-logging 兼容实现）加上。
$jclJar = Get-ChildItem -Path (Join-Path $gradleHome 'caches\modules-2\files-2.1\org.springframework') `
    -Recurse -Filter 'spring-jcl-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1

$classPath = $securityJar.FullName
if ($jclJar) {
    $classPath = "$($securityJar.FullName);$($jclJar.FullName)"
} else {
    Write-Host '  ⚠ 未找到 spring-jcl，若出现 NoClassDefFoundError(LogFactory) 请先执行 gradlew dependencies' -ForegroundColor Yellow
}

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-17' }
$javaExe = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    $javaExe = (Get-Command java).Source
}

$tmpDir = Join-Path $env:TEMP ("bcrypt-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null

# ★ 这段临时 Java 源码**只能是纯 ASCII**，下面的写出编码必须与之匹配。
#   坑（计划书 §6 坑 17，本机 PS 5.1 实测）：`Set-Content -Encoding UTF8` 在
#   Windows PowerShell 5.1 下会写入 **BOM**（EF BB BF），而 `javac` 不认 BOM，
#   直接报「非法字符: '\ufeff'」→ 建账号这一步整个挂掉。
#   这里刻意用 `-Encoding ASCII`（5.1 的 ASCII 写出**不带 BOM**，UTF8 带），
#   所以源码里不能有中文注释 —— 一旦有人加回中文，ASCII 写出会把它们变成 '?'，
#   javac 反而照常通过（注释里的 '?' 无害），坏得静默。宁可写英文。
$javaSrc = @'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class GenHash {
    public static void main(String[] args) {
        // Strength 10 must match PasswordEncoderConfig; a different cost factor
        // still verifies on login but makes every login cost something else,
        // so later tuning would be measured against the wrong baseline.
        var encoder = new BCryptPasswordEncoder(10);
        System.out.println(encoder.encode(args[0]));
    }
}
'@
$srcFile = Join-Path $tmpDir 'GenHash.java'
Set-Content -Path $srcFile -Value $javaSrc -Encoding ASCII

try {
    & (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 `
        -cp $classPath -d $tmpDir $srcFile 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error 'javac 编译失败'
        exit 1
    }
    # ★ 用 2>&1 捕获会把 stderr 混进管道，PowerShell 可能把整行包成 ErrorRecord。
    #   这里显式分开：stdout 才是哈希，stderr 只用于诊断。
    $stdoutFile = Join-Path $tmpDir 'stdout.txt'
    $stderrFile = Join-Path $tmpDir 'stderr.txt'
    $proc = Start-Process -FilePath $javaExe `
        -ArgumentList @('-cp', "$tmpDir;$classPath", 'GenHash', $Password) `
        -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile `
        -NoNewWindow -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
        Write-Host ('  java 错误: ' + (Get-Content $stderrFile -Raw)) -ForegroundColor Red
        Write-Error '生成 BCrypt 哈希失败'
        exit 1
    }
    $hash = (Get-Content $stdoutFile -Raw).Trim()
    if ($hash -notmatch '^\$2[aby]\$') {
        Write-Host "  输出不是合法 BCrypt 哈希: $hash" -ForegroundColor Red
        Write-Error 'BCrypt 哈希格式异常'
        exit 1
    }
    Write-Output $hash
} finally {
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
