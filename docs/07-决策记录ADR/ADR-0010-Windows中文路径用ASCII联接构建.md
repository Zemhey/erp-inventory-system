# ADR-0010: Windows 中文路径下用 ASCII 联接作为构建路径

- 状态：已采纳
- 日期：—
- 影响范围：本地开发、CI（Windows self-hosted runner）

## 背景

本项目位于 `D:\MajorCacheFolder\Company system\自选`，路径含中文「自选」。

在 M0 首次运行测试时，出现了一个**极具误导性**的故障：

```
org.gradle.api.internal.tasks.testing.TestSuiteExecutionException:
  Could not execute test class 'com.erp.inv.InventoryReserveIT'
Caused by: java.lang.ClassNotFoundException: com.erp.inv.InventoryReserveIT
```

现象与排查结论（实测，非推测）：

| 路径 | `./gradlew test` 结果 |
|---|---|
| `D:\erp_probe`（纯 ASCII 联接 → 同一份源码） | ✅ 通过 |
| `D:\erp probe`（含空格） | ✅ 通过 |
| `D:\自选测试`（含中文） | ❌ ClassNotFoundException |

**结论：空格无影响，只有非 ASCII（中文）字符会触发。**

根因：Gradle 在 Windows 上以系统 ANSI 代码页（本机 936/GBK）编码传给 test worker 的
classpath 相关内容；而 JVM 以 UTF-8 解析类文件与路径。两者不一致时，
worker 无法定位已编译的类文件 → 报"类找不到"（看起来像"没编译"，实际编译是成功的）。

**为什么常规绕过手段无效**（均已实测）：
- `Test.jvmArgs("-Dsun.jnu.encoding=UTF-8")` —— 无效。问题发生在**守护进程侧**（它才是构造 classpath 的一方）。
- `gradle.properties` 里 `org.gradle.jvmargs=...-Dsun.jnu.encoding=UTF-8` —— 无效。
  Gradle 会**过滤掉** `sun.jnu.encoding`，实测守护进程命令行里只剩 `-Dfile.encoding`。
- `JAVA_TOOL_OPTIONS` / `_JAVA_OPTIONS` —— 同样被过滤，实测无效。
- 改 `chcp 65001` —— 不改变 JVM 进程内的 ANSI 代码页判定。

这是 Gradle 8.9 在 Windows 上的已知类缺陷，非本项目代码问题。

## 决策

**保留中文项目目录不动，另建一个纯 ASCII 目录联接（junction）作为官方构建路径。**

```
源码实际位置：D:\MajorCacheFolder\Company system\自选   ← 你的目录，不动
构建入口    ：D:\erp-build  （Junction → 上者）         ← 所有 gradle 命令在这里执行
```

**为什么选联接而不是移动目录**：
1. **非破坏性** —— 不改变你的目录结构，随时可删联接回退（`rmdir` 不加 `/S`）；
2. **同一份字节** —— junction 不是拷贝，改一处即改两处，不存在"两份代码"的同步问题；
3. **成本极低** —— 项目源码仅约 1.4 MB；
4. **CI 不受影响** —— Linux runner 路径本来就没有中文，无需任何特殊处理。

**为什么不用"把项目移到纯 ASCII 路径"**：
可行且更彻底，但会改变你的工作目录组织方式。作为可选方案保留在下方，
若后续工具（Node/uni-app/Appium 等）也出现同类编码问题，再考虑彻底迁移。

## 实施

```powershell
# 创建构建联接（首次一次即可）
.\scripts\build-env.ps1

# 之后所有构建/测试都在 ASCII 路径执行
cd D:\erp-build\apps\server
.\gradlew.bat test
```

`scripts/build-env.ps1` 会：
1. 检测当前项目路径是否含非 ASCII 字符；
2. 含则创建/复用 `D:\erp-build` 联接；
3. 验证联接可读且 `gradlew` 可执行；
4. 打印后续命令。

## 后果

- ✅ 中文目录保留，构建与测试可正常工作
- ✅ 非破坏、可回滚（删联接即可）
- ✅ 该问题已写入文档，避免下次再花时间排查
- ⚠️ 构建产物（`build/`、`.gradle/`）实际写在原目录内，经联接访问 —— 注意 IDE 里
  打开**源码路径**或**联接路径**均可，但不要同时打开两处（IDE 会认为是两个项目）
- ⚠️ 若将来迁移到纯 ASCII 路径，删除 `D:\erp-build` 联接并更新本文即可
- ⚠️ 需要团队协作（`git clone` 到不同路径）时，每人执行一次 `build-env.ps1`

## 简历价值

这条记录本身就是"工程排障能力"的证据：一个看起来像"类没编译"的错误，
实际根因是 Windows ANSI 代码页与 JVM UTF-8 的编码不一致，且 Gradle 会过滤
`sun.jnu.encoding` 使常规绕行手段失效。面试可讲成"定位并规避平台级编码缺陷"。

> 提示：本项目尚未 `git init`。建议初始化后把 `build/`、`.gradle/`、`.tools/` 写入 `.gitignore`
> （避免把构建产物与诊断日志提交进仓库）。
