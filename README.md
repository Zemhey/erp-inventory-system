# 企业进销存系统 · 服装/鞋帽网店

[![CI](https://github.com/Zemhey/erp-inventory-system/actions/workflows/ci.yml/badge.svg)](https://github.com/Zemhey/erp-inventory-system/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Tests](https://img.shields.io/badge/tests-11%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/line%20coverage-58%25-yellowgreen)

> **多端 B/S 进销存 SaaS 平台 + 配套自动化测试体系**
> Java 17 · Spring Boot 3 · PostgreSQL 16 · Vue 3 · uni-app

**课程要求**：[任务要求.md](任务要求.md) ｜ **方向与路线**：[方向建议与落地路线.md](方向建议与落地路线.md)

---

## 这个项目想证明什么

进销存的难点**不在增删改查**，而在于：**订单 / 库存 / 金额三者在并发下仍然一致、可对账、可追溯。**

因此本项目把工程重心压在三个地方，并且每一处都有对应的自动化测试守住：

| 关注点 | 做法 | 谁来证明 |
|---|---|---|
| **绝不超卖** | 带条件的**原子 UPDATE**（非"先查后写"）+ 数据库层 `CHECK (on_hand >= 0)` 兜底 | `TC-INV-006`：100 线程抢 50 件，成功数必须**恰为 50** |
| **不重复扣减** | 幂等键由**数据库唯一约束**保证，而非应用层判断 | `TC-INV-005` / `TC-INV-007`：同 requestId 并发只生效一次 |
| **账实相符** | 流水是**不可变事实源**，余额是派生结果；不一致时以流水重算 | `TC-INV-008`：实存 == 流水净和（不变量 INV-2） |

### 一个值得说的细节

这套测试在 M0 阶段**真实抓出了 2 个缺陷**，都不是练习题：

1. **已预占的货反而发不出去** —— 出货按"可用量"判断可扣减量，但预占恰恰把可用量扣成了 0，逻辑自己把自己锁死。
2. **合法拆批次被误判成重复提交** —— 流水唯一索引是 `(request_id, biz_type)`，而一笔出货按 FEFO 拆多批次时会合法产生多条流水。

> 第 2 条**只有真实 PostgreSQL 能暴露**。用 H2 测会一路绿灯到生产才炸 —— 这正是本项目坚持 Testcontainers 而非内存库的原因（[ADR-0003](docs/07-决策记录ADR/ADR-0003-数据库选PostgreSQL.md)）。

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

**当前阶段：M0 已完成 ✅（骨架 · 契约 · 测试地基 · CI 全跑通）**

- ✅ 方向与路线确定（行业、技术栈、测试体系）
- ✅ 规划文档（00~07 + 10 条 ADR）
- ✅ **M0 交付**：OpenAPI 六资源契约 · Spring Boot 3 骨架 · Flyway schema · 库存纵向切片 · **Testcontainers 11 条集成测试全绿** · compose 一键起环境 · 代码索引生成 · CI 三道门禁
- ⬜ M1：主数据 + 进货 + 出货（库存切片已完成）
- ⬜ M2：结算、日清月结、报表 → **此时转 public**

### 工程实践亮点

| 实践 | 说明 |
|---|---|
| **契约先行** | OpenAPI 是接口单一事实源，驱动三端 SDK / 契约测试 / 属性测试 / 代码索引（[ADR-0008](docs/07-决策记录ADR/ADR-0008-OpenAPI为单一事实源.md)） |
| **不变量驱动测试** | 断言写"不变量"（如 实存 == 流水净和），而非硬编码期望值 —— 用例不会因数据变动而脆弱 |
| **可测试性设计** | 注入 `ClockProvider`，让跨月结算/效期/预占超时成为可测命题，而非等到真实时间发生 |
| **索引自动化** | 代码索引由脚本从源码与 OpenAPI 生成并纳入 CI 校验，杜绝文档腐化（[ADR-0009](docs/07-决策记录ADR/ADR-0009-代码索引脚本生成.md)） |
| **需求↔测试可追踪** | 每个用例带 `TC-xxx` 编号，[追踪矩阵](docs/00-索引与追踪矩阵.md) 自动汇总，扫空行即得测试待办 |

## ⚠ Windows 构建路径（本机必读）

本项目路径含中文（`自选`），而 **Gradle 8.9 在 Windows 上无法从含中文的路径运行测试**
（所有测试类报 `ClassNotFoundException`，看似"没编译"，实为 ANSI 编码不一致）。
详见 [ADR-0010](docs/07-决策记录ADR/ADR-0010-Windows中文路径用ASCII联接构建.md)。

**首次使用先建构建联接，之后所有构建命令都在 ASCII 路径执行：**

```powershell
.\scripts\build-env.ps1          # 创建 D:\erp-build 联接（非破坏、可回滚）
cd D:\erp-build\apps\server
.\gradlew.bat test               # 运行测试（11 条，全绿）
```

删除联接：`.\scripts\build-env.ps1 -Remove`

> Linux / macOS / CI 上无需此步骤，直接 `./gradlew test` 即可。

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

## 想快速了解这个项目？按这个顺序看

只给你 3 分钟时，看这 4 个文件就够：

| # | 文件 | 能看出什么 |
|---|---|---|
| 1 | [docs/02-领域模型](docs/02-领域模型与术语表.md) §4 | **10 条核心不变量** —— 整个系统的正确性都围绕它们 |
| 2 | [InventoryApplicationService](apps/server/src/main/java/com/erp/inv/application/InventoryApplicationService.java) | 防超卖的原子 UPDATE、幂等、FEFO 扣减，以及 2 个真实缺陷的修复注释 |
| 3 | [InventoryReserveIT](apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java) | 测试怎么断言"不变量"而非硬编码数字 |
| 4 | [docs/07-决策记录ADR](docs/07-决策记录ADR/README.md) | 10 条决策的**放弃方案与理由** —— 工程判断力的直接证据 |

## 目录结构

```
docs/           规划文档（00~07 + 10 条 ADR）
index/          代码索引（生成物，由脚本维护）
scripts/        dev-up（一键起环境）/ gen-index（生成索引）/ build-env（Windows 路径）
apps/server/    Spring Boot 3 服务端（当前唯一已实现的应用）
packages/       api-contract（OpenAPI 单一事实源）
infra/          compose 本地环境编排
.github/        CI 三条门禁流水线
```

> `apps/web`、`apps/miniapp`、`apps/android`、`tests/e2e-*` 为后续里程碑（M3~M5）预留，
> 当前尚未创建 —— README 不写不存在的东西。
