# 企业进销存系统 · 服装/鞋帽网店

> 多端 B/S 进销存 SaaS 平台 + 配套自动化测试体系
> 技术栈：Java 17 · Spring Boot 3 · PostgreSQL 16 · Vue 3 · uni-app

**课程要求**：[任务要求.md](任务要求.md)
**方向与路线**：[方向建议与落地路线.md](方向建议与落地路线.md)

---

## 从这里开始

👉 **[docs/00-索引与追踪矩阵.md](docs/00-索引与追踪矩阵.md)** —— 项目唯一入口。
想知道"现在做什么、做到哪了、还差什么"，只看这一页。

## 文档地图

| 编号 | 文档 | 作用 |
|---|---|---|
| 00 | [索引与追踪矩阵](docs/00-索引与追踪矩阵.md) | ★ 总入口 + 进度看板 + 需求↔测试追踪矩阵 |
| 01 | [范围与功能清单](docs/01-范围与功能清单.md) | 做什么/不做什么；FR 编号 |
| 02 | [领域模型与术语表](docs/02-领域模型与术语表.md) | 统一语言、状态机、业务规则、10 条不变量 |
| 03 | [架构与整体结构](docs/03-架构与整体结构.md) | 分层、模块、目录、部署、索引机制 |
| 04 | [数据模型](docs/04-数据模型.md) | 表结构、约束、迁移（草案） |
| 05 | [接口契约规范](docs/05-接口契约规范.md) | OpenAPI 工作流、REST 约定、错误码表（草案） |
| 06 | [测试策略](docs/06-测试策略.md) | 金字塔、时机、可测试性钩子、DoD（草案） |
| 07 | [决策记录 ADR](docs/07-决策记录ADR/README.md) | 10 条架构决策及其理由（含 Windows 路径陷阱） |
| — | [代码索引](index/README.md) | ★ 生成物，由脚本维护，禁止手写 |

## 项目状态

**当前阶段：M0 已完成 ✅（骨架 · 契约 · 测试地基 · CI 全部跑通）**

- ✅ 方向与路线确定（行业、技术栈、测试体系）
- ✅ 规划文档（00~07 + 10 条 ADR）
- ✅ **M0 交付**：OpenAPI 六资源契约 · Spring Boot 3 骨架 · Flyway schema · 库存纵向切片 · Testcontainers 12 条集成测试全绿 · compose 一键起环境 · 代码索引生成 · CI 三门槛
- ⬜ M1：主数据 + 进货 + 出货（库存切片已完成）

## ⚠ Windows 构建路径（本机必读）

本项目路径含中文（`自选`），而 **Gradle 8.9 在 Windows 上无法从含中文的路径运行测试**
（所有测试类报 `ClassNotFoundException`，看似"没编译"，实为 ANSI 编码不一致）。
详见 [ADR-0010](docs/07-决策记录ADR/ADR-0010-Windows中文路径用ASCII联接构建.md)。

**首次使用先建构建联接，之后所有构建命令都在 ASCII 路径执行：**

```powershell
.\scripts\build-env.ps1          # 创建 D:\erp-build 联接（非破坏、可回滚）
cd D:\erp-build\apps\server
.\gradlew.bat test               # 运行测试（12 条，全绿）
```

删除联接：`.\scripts\build-env.ps1 -Remove`

## 快速开始

```powershell
# 1. 起依赖环境（PostgreSQL 16 + Redis + RabbitMQ，等健康检查通过才返回）
.\scripts\dev-up.ps1

# 2. 建构建联接（仅首次，或路径含中文时）
.\scripts\build-env.ps1

# 3. 跑测试 + 覆盖率门禁
cd D:\erp-build\apps\server
.\gradlew.bat check

# 4. 启动服务（API 文档 http://localhost:8080/swagger-ui.html）
.\gradlew.bat bootRun

# 5. 重新生成代码索引
cd D:\MajorCacheFolder\Company system\自选
bash scripts/gen-index.sh
```

## 三条硬规则

1. **契约先行** —— 先改 [openapi.yaml](packages/api-contract/)，再改两侧实现
2. **主干永远绿** —— 红灯立刻修，来不及就标记隔离区，绝不带着红灯继续开发
3. **状态以 [追踪矩阵](docs/00-索引与追踪矩阵.md#3-追踪矩阵--本文件的核心) 为准** —— 代码写了但矩阵没更新 = 没做

## 目录结构

```
docs/           规划文档（00~07 + ADR）
index/          代码索引（生成物）
scripts/        一键脚本（起环境、生成索引）
apps/           server / web / miniapp / android / desktop
packages/       api-contract（契约源）/ test-kit（测试公共库）
tests/          e2e-web / e2e-miniapp / e2e-android / cases / perf / security
infra/          compose / nginx / k8s
.github/        CI/CD workflows
```
