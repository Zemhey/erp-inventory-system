# 企业进销存系统 · 服装/鞋帽网店

![CI](https://img.shields.io/badge/CI-never%20run%20(no%20push)-yellow) <!-- 本仓库自 M1 起未 push，.github/workflows/ci.yml 的三道门禁从未在真实 runner 执行；本地等价门禁见 scripts/contract-check.py，其余靠 gradlew check + Playwright。首次 push 前，CI 状态一律视为未知。 -->
![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Tests](https://img.shields.io/badge/tests-1204%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/line%20coverage-87.55%25-brightgreen)
![Branch](https://img.shields.io/badge/branch%20coverage-68.57%25-yellowgreen)

> **多端 B/S 进销存 SaaS 平台 + 配套自动化测试体系**
> Java 17 · Spring Boot 3 · PostgreSQL 16 · Vue 3 · uni-app

**课程要求**：[任务要求](docs/planning/任务要求.md) ｜ **方向与路线**：[方向建议与落地路线](docs/planning/方向建议与落地路线.md) ｜ **全局交接**：[项目总纲](docs/planning/项目总纲.md) ｜ **★ 下一步做什么**：[V2 商业化扩展蓝图](docs/planning/V2-商业化扩展蓝图.md)

> **第一次打开这个仓库？** 先看下面的「30 秒目录导航」；想知道全局（大纲/规划/索引/交接），读 [项目总纲](docs/planning/项目总纲.md)。

---

## 30 秒目录导航

| 我想…… | 打开这个 | 说明 |
|---|---|---|
| **看前端界面 / 改页面** | [`frontend/`](frontend/) | 所有客户端（Web，未来小程序 / Android） |
| **看后端业务逻辑 / 加接口** | [`backend/`](backend/) | 所有服务端代码 |
| **想知道整个项目是什么、怎么继续** | [`docs/planning/项目总纲.md`](docs/planning/项目总纲.md) | 一份文件看懂全局 |
| **想知道"往哪走、怎么变成商业系统"** | [`docs/planning/V2-商业化扩展蓝图.md`](docs/planning/V2-商业化扩展蓝图.md) | ★ 对标参考系统 + 7 个主流 ERP · 三期九步 · +61 条 FR · **四道防堆功能闸门** |
| **想知道"别人怎么做的、我们差在哪"** | [`docs/planning/竞品对标与业务核实.md`](docs/planning/竞品对标与业务核实.md) | 7 个主流 ERP 的功能共识矩阵 + 联网核实清单 |
| **要开工 V2（下发任务）** | [`.plan/V2-施工指挥台.md`](.plan/V2-施工指挥台.md) | ★ 阶段0清场 · 九条泳道 · 可直接复制的提示词模板 · 合并纪律 |
| **看做到哪了、还差什么** | [`docs/00-索引与追踪矩阵.md`](docs/00-索引与追踪矩阵.md) | 进度看板 + 需求↔测试矩阵 |
| **看某个里程碑怎么做** | [`.plan/`](.plan/) | M1 / M2 / M3 已完成；M4 两批 / M6 制品 / M7 / M2.5 红冲 / M8 单机版均已合并；V2 批次 2 六条泳道编排见 [`V2-施工指挥台.md`](.plan/V2-施工指挥台.md) §1.5.1 与 §8 |
| **看接口契约** | [`packages/api-contract/openapi.yaml`](packages/api-contract/openapi.yaml) | 单一事实源 |
| **看代码结构 / 接口清单** | [`index/`](index/) | 脚本生成，禁止手写 |
| **起环境 / 跑构建 / 生成索引** | [`scripts/`](scripts/) | 一键脚本 |
| **看数据库与依赖环境** | [`infra/`](infra/) | Docker Compose |
| **看架构决策** | [`docs/07-决策记录ADR/`](docs/07-决策记录ADR/) | 11 条 ADR |

> `docs/` 根只放编号设计与过程文档（00~07）；项目级规划与历史输入在
> [`docs/planning/`](docs/planning/)；`.plan/` 放各里程碑执行计划。

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
| 07 | [决策记录 ADR](docs/07-决策记录ADR/README.md) | 11 条架构决策及其理由（含 Windows 路径陷阱） |
| — | [代码索引](index/README.md) | ★ 生成物，由脚本维护，禁止手写 |
| — | [项目总纲](docs/planning/项目总纲.md) / [任务要求](docs/planning/任务要求.md) / [方向建议](docs/planning/方向建议与落地路线.md) | 全局交接、课程原始要求、早期路线（`docs/planning/`） |

## 项目状态

**当前阶段：M0/M1/M2/M3/M7/M8 完成 ✅，M4 两批、M2.5 红冲与 M6 制品已合并；V2 波 A + 波 B 全部合并（V2-1 库存作业、V2-2 采购闭环、V2-3 销售闭环、V2-4 成本毛利、V2-5 审计可观测、V2-6 权限审批、**V2-7 租户隔离**），V2-8 报表、C3 字段门禁、E1 审计/字典页、UI-IA 信息架构均已进 main（**1204 条后端用例全绿 · 行 87.55% / 分支 68.57% · 契约 141 路径 / 172 操作 / 146 schema · 字段级门禁 0 错误 · Web 22 个业务页 + miniapp 18 页 · Web E2E 36 · MCP 6 工具 · 单机版一键包**）· 下一步：**Batch B**（B1 租户 UI → B2 主数据字段 → B3 小程序 PDA → B4 演示包装）**

- ✅ 方向与路线确定（行业、技术栈、测试体系）
- ✅ 规划文档（00~07 + 11 条 ADR）
- ✅ **M0 交付**：OpenAPI 六资源契约 · Spring Boot 3 骨架 · Flyway schema · 库存纵向切片 · compose 一键起环境 · CI 三道门禁
- ✅ **M1 交付**：主数据（商品/SKU 矩阵/供应商/客户/仓库）· 进货闭环 · 出货闭环（FEFO）· IAM（JWT + RBAC）· 统一状态机 · 原子取号 · M1 基线 156 条测试（**当前累计 1204 条全绿**）
- ✅ **M3 交付**：登录 / 布局 / 工作台 + 商品与 SKU + 供应商 / 客户 / 仓库 + 库存查询（实存·预占·可用 + 流水追溯）+ 进货单（状态机 + 分批入库）+ 出货单（建单即预占 + 折扣 + 出货），**7 个业务页面全齐、无占位页**；S7 全局打磨（空状态、错误态、窄屏适配）；**S8 Playwright E2E 14 条关键路径，真实后端 + 真实 PostgreSQL 连续两遍 15/15 全绿**，测试数据清理后开发库回到基线
- ✅ **M2 交付**：退货 `FR-RMA-01`（9 个接口，含"可退出货单查询"）· 日清 / 月结锁账 / 对账 / 账期查询 `FR-SET-01~03` · 三类报表 `FR-RPT-01~03` · `BR-INV-05` 预占超时自动释放 —— 16 个新接口全部有实现有测试，`CONTRACT_ONLY` 清零；前端 10 个业务页面（退货/结算/报表三页 + 退货建单接入可退查询 + 结算页账期列表）；M2 验收时后端 **221 条用例全绿**（M2 基线行 **82.6%** / 分支 **60.9%**）；E2E **19 条 + 登录 setup** 连跑两遍全绿；红冲（FR-SAL-04 / FR-PUR-03）与 G5~G13 体验债转 M2.5（计划：[`.plan/M2-计划书.md`](.plan/M2-计划书.md)）
- 🚧 **M4 两批均已合并**：第一批 `feat/m4-miniapp`（merge `104674e`）—— `frontend/miniapp` uni-app 工程 + 登录 / 工作台 / 库存查询 3 页，`build:h5` / `build:mp-weixin` / `build:app` 通过；第二批 `feat/m4b-pages`（merge `fedca69`）—— 出货 / 进货 / 商品只读共 **7 个新页面**（`pages.json` 实测 **10 页**）+ H5 E2E 工程 **6 spec / 8 test**（唯一前缀造数 + 硬清理）。**未实测**：微信开发者工具联调、Android 打包与真机（需 HBuilderX 与开发者工具）
- ✅ **M2.5 红冲完成**：`FR-SAL-04` 出货红冲 + `FR-PUR-03` 进货红冲（merge `a4c4149`）—— 已出货/已入库单据可补偿性作废（`PARTIAL`/`COMPLETED` → `VOIDED`），按原流水逐条反向回补实存、写 `SALES_REVERSAL`/`PURCHASE_REVERSAL`；`V8__document_reversal.sql` 迁移；与退货互斥、锁账拒绝、同 `X-Request-Id` 重放幂等、并发安全；新增 17 条用例（TC-DOC-011/012、TC-PUR-016~023、TC-SAL-018~024），`openapi.yaml` 追加 void 语义（**复用既有 `/void` operation，未新增路径**）
- 🚧 **M6 制品已合并**：后端/前端 Dockerfile · 生产 compose · 部署 / 回滚 / 备份脚本 · Runbook · 部署流水线草案；本地 18080 全栈与备份恢复实测通过，**真实上线待服务器凭据与备案**
- ✅ **M7 完成（stub 验证）**：`POST /nl/command` 自然语言入口（8 意图、意图级权限、建单幂等）+ `packages/mcp-server` 6 个 stdio 工具 + 工作台 NL 输入框；默认 `stub` 解析器离线可复现，**真实 LLM 待用户提供 key**
- ✅ **M8 单机版完成**：`infra/standalone/**` 15 文件（merge `2a48b12`）—— Docker compose 单机编排、`start` / `stop` / `status` / `backup` 的 `.ps1` 与 `.sh` 双份脚本、公共函数库、种子管理员 SQL、`.env.example` 与运行手册（Windows/Linux、离线、备份、升级、排障）。**未实测**：Linux/macOS 真机与真实离线环境

### V2 商业化扩展（波 A + 波 B 全部合并）

| 期 | 内容 | merge | 关键交付 |
|---|---|---|---|
| V2-1 | 库存作业 | `39a7c64` | `V9` 6 表：调拨（两段式在途）· 盘点差异过账 · 调整（原因必填）· 冻结/解冻（`available = on_hand − reserved − frozen`）· 五维结存 · 预警 · 补货建议 · 调拨/库存作业 2 页 |
| V2-2 | 采购闭环 | `7be943d` | `V10` 5 表：请购转单 · 到货质检（不合格不入库）· 采购退货 · 应付 · 付款核销（超额拒绝）· 2 页 |
| V2-4 | 成本毛利 | `a4c80db` + `508fef9` | `V12` 3 表：移动加权成本 · 月结转（幂等）· 毛利分析；`INV-11` 成本守恒属性测试（含跨仓调拨配对补丁）· 毛利页 |
| V2-5 | 审计可观测 | `5149199` | `V13`：`audit_logs`（AOP 留痕、`REQUIRES_NEW`、DB append-only 触发器）· 审计查询 · traceId 贯穿 · Prometheus；`FR-SYS-06~09` |
| V2-6 | 权限与审批 | `d0979cd` | `V14` 4 表：数据权限（按仓/按本人，越权一律 **404** 而非 403）· 在线授权即时生效 · 登录策略 · 金额阈值多级审批与会签/或签 · 审批页 + 权限页 |
| V2-3 | 销售闭环 | `3e472ef` | `V11` 6 表：报价转单 · 拣货扫码发货 · 应收 · 收款核销 · 信用控制（超额拒单且不预占）· **组合商品拆解（INV-16）** · **发货选仓拆单** · 季节维度 · 3 页 |
| V2-C1 | MDM 幂等键（契约债清偿） | `fb87ba4` | `V15` 8 列 + 8 条**局部唯一索引**（与既有 `request_id` 同构）；8 个 MDM 写端点**真实消费** `X-Request-Id`（同键重放返回同一资源、零重复写入、不推进单号序列）；`shared/idem` 新包；`contract-check` 8 条错误 → **0**；+25 用例（`TC-MDM-027~041`、`TC-API-085~094`） |
| V2-D1 | 移动端第三批 | `93fa6b9` | miniapp **10 页 → 18 页**：报价 2 / 发货扫码 2 / 应收收款 2 / 待我审批 2 + 两套独立状态机镜像；H5 E2E **9 → 23 passed**（14 条 `TC-MOB`，含超额核销被拒、只读身份看不到收款表单、转办需 `aps:approve`）；后端零改动；顺带修 `JAVA_TOOL_OPTIONS` 串味导致 BCrypt 脚本误判失败，并让 miniapp E2E 可各用自己的 PG |
| 契约清偿 1 | 43 条债务 | `0a1e227` | 契约 **90 → 133 操作 / 107 路径**；确立「`UNDOCUMENTED_DEBT` 保持空集是新的 DoD」；V2-3 的 28 条当时作为「待清偿 2」保留 |
| 契约清偿 2 | V2-3 的 28 条 | `78bbb27` | 契约 **133 → 161 操作 / 130 路径 / 133 schema**，豁免集**归零**；语义差集自证纯增量（唯一改动的既有 schema 是 `SalesOrderLine`，只加可选字段）；顺带登记 **4 条字段级缺陷** → V2-C3 |
| V2-C3 | 字段级契约债 | `653b553` | 修 `Warehouse.priority` / `Sku.seasonCode/bandCode/launchDate` / `discountRate.minimum` / 悬空 `$ref`；交付 `scripts/schema-field-check.py`（修复前当场报、修复后 0 错误） |
| E1 | 审计/字典界面 | `7867dca` | `/audits`（6 维筛选 + before/after diff + traceId 时间线）、`/dicts`（只读字典浏览）；`TC-SYS-091~096` + `TC-API-115~124`；Web E2E 20 → **36** |
| UI-IA | 信息架构与驾驶舱 | `dbe87c9` | 菜单 21 项 → **23 项 / 8 组**；工作台扩成待办驾驶舱；esbuild 固定 **0.25.11**（`921d1a3`） |
| V2-8 | 报表体系 | `cbe33e9` | 报表 **8 页签**（进货/出货/进销存/经营/周转/贡献/AR/AP）+ **7 个 CSV 导出** + 打印；`FR-RPT-05~09` / `FR-SET-06`；契约 **172 操作 / 141 路径 / 146 schema** |
| V2-7 | 租户隔离 | `ec425e2` | `V16` 全表 `tenant_id` + RLS/触发器 + `TenantSql` 强制改写 + JWT `tid` + `TenantContext`；**135 格跨租户矩阵**（异租户 404/空 + 同租户正对照）；分支 1155 绿，合并后 1204 绿 |

★ 当前基线（含 V2-C3/E1/UI-IA/V2-8/V2-7）：**1204 条用例 / 0 失败 / 0 错误 / 0 跳过 / 行 87.55%（9344/10673）/ 分支 68.57%（2686/3917）**（`f6c1986` 在全新 ASCII 工作树 `clean check --no-daemon --offline`，11m02s；门限从未降低）；`scripts/contract-check.py` **0 错误**（172 操作 / 141 路径 / 146 schema），`scripts/schema-field-check.py` **0 错误**，`UNDOCUMENTED_DEBT` **空集**。
★ 合并后 Web E2E 在独立 PG 上复跑 **36/36**；过程中修复了 V2-7 造成的临时账号造数回归（`app_users.tenant_id NOT NULL`）。进度真相见 [docs/00](docs/00-索引与追踪矩阵.md) 与 [.plan/批次-2026-10-25-Batch-B.md](.plan/批次-2026-10-25-Batch-B.md)。

### 工程实践亮点

| 实践 | 说明 |
|---|---|
| **契约先行** | OpenAPI 是接口单一事实源，驱动三端 SDK / 契约测试 / 属性测试 / 代码索引（[ADR-0008](docs/07-决策记录ADR/ADR-0008-OpenAPI为单一事实源.md)） |
| **不变量驱动测试** | 断言写"不变量"（如 实存 == 流水净和），而非硬编码期望值 —— 用例不会因数据变动而脆弱 |
| **可测试性设计** | 注入 `ClockProvider`，让跨月结算/效期/预占超时成为可测命题，而非等到真实时间发生 |
| **索引自动化** | 代码索引由脚本从源码与 OpenAPI 生成并纳入 CI 校验，杜绝文档腐化（[ADR-0009](docs/07-决策记录ADR/ADR-0009-代码索引脚本生成.md)） |
| **需求↔测试可追踪** | 每个用例带 `TC-xxx` 编号，[追踪矩阵](docs/00-索引与追踪矩阵.md) 自动汇总，扫空行即得测试待办 |
| **真实 E2E** | Playwright 驱动真实后端 + 真实 PostgreSQL（不 mock），**36 passed（含登录 setup）**覆盖登录、主数据、进货、出货、退货、结算锁账、报表、审计/字典与 VIEWER/FINANCE 权限；测试数据用唯一前缀、按依赖顺序清理并硬校验基线（`.plan/M2-计划书.md` §5.5） |

## Windows 构建路径

仓库根目录已改为纯英文 `erp-inventory-system`，Gradle 可直接在仓库内构建，
不再需要旧中文路径时期的 `D:\erp-build` ASCII 联接（历史背景见
[ADR-0010](docs/07-决策记录ADR/ADR-0010-Windows中文路径用ASCII联接构建.md)）。

```powershell
cd backend\server
.\gradlew.bat check              # 运行测试 + 覆盖率门禁
```

## 快速开始

```powershell
# 0. 一键启动（Windows，需 Docker Desktop；已在跑的服务会复用，不会杀进程）
#    只起两件事：后端 :8080 + Web :3030。不启动 app/（安卓）、小程序 / H5、桌面端。
#    Web: http://127.0.0.1:3030/    账号: admin / Admin@12345
.\启动系统.cmd

#    AI Agent 接入（可选）：见 docs/MCP-接入指南.md
#    MCP 不常驻、也不随上个脚本启动 —— Agent 用的时候才连它。

# 1. 起依赖环境（PostgreSQL 16 + Redis + RabbitMQ，等健康检查通过才返回）
#    注：宿主 PostgreSQL 端口是 15432（本机 5432 落在 Windows 保留段内，见 compose 注释）
.\scripts\dev-up.ps1

# 2. 跑测试 + 覆盖率门禁（仓库路径已是纯英文，直接构建）
cd backend\server
.\gradlew.bat check

# 3. 创建开发用登录账号（V3 迁移刻意不预置账号）
cd "D:\MajorCacheFolder\Company system\erp-inventory-system"
.\scripts\seed-dev-user.ps1       # 默认 admin / Admin@12345

# 4. 启动后端（API 文档 http://127.0.0.1:8080/swagger-ui.html）
cd backend\server
.\gradlew.bat bootRun

# 5. 启动前端（另开一个终端 → http://127.0.0.1:3000）
cd "D:\MajorCacheFolder\Company system\erp-inventory-system\frontend\web"
npm install
npm run dev

# 6. 重新生成代码索引
cd "D:\MajorCacheFolder\Company system\erp-inventory-system"
.\scripts\gen-index.ps1           # Windows；Linux/macOS 用 ./scripts/gen-index.sh
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
| 2 | [InventoryApplicationService](backend/server/src/main/java/com/erp/inv/application/InventoryApplicationService.java) | 防超卖的原子 UPDATE、幂等、FEFO 扣减，以及 2 个真实缺陷的修复注释 |
| 3 | [InventoryReserveIT](backend/server/src/test/java/com/erp/inv/InventoryReserveIT.java) | 测试怎么断言"不变量"而非硬编码数字 |
| 4 | [docs/07-决策记录ADR](docs/07-决策记录ADR/README.md) | 11 条决策的**放弃方案与理由** —— 工程判断力的直接证据 |

## 目录结构

```
backend/server/  服务端（Java 17 + Spring Boot 3）—— 全部后端代码
frontend/web/    前端（Vue3 + TypeScript + Element Plus）—— Web 客户端
frontend/miniapp/ 移动端（uni-app：H5 / 微信小程序 / Android 资源包）
docs/            设计与过程文档（00~07 + 11 条 ADR）
  planning/      项目级规划：项目总纲、任务要求、方向建议
index/           代码索引（生成物，由脚本维护，禁止手写）
packages/        api-contract（OpenAPI 单一事实源）、mcp-server（MCP stdio 适配器）
scripts/         dev-up / gen-index / build-env / seed-dev-user / bcrypt-hash / 部署与备份
infra/           compose 本地环境 + deploy 生产编排
.plan/           各里程碑执行计划（M1 / M2 / M3 + M4 / M6 / M7 + 批次编排）
.github/         CI 门禁流水线 + 部署流水线草案
README.md        唯一根入口（目录导航 + 快速开始）
```

> **约定**：给用户看的界面进 `frontend/`，跑在服务器上的进 `backend/`
> （[ADR-0011](docs/07-决策记录ADR/ADR-0011-目录调整backend与frontend分区.md)）。
> 根目录只保留 `README.md`；规划与历史输入统一放在 `docs/planning/`。
>
> `frontend/android/` 尚未创建（M5 出包仍复用 `frontend/miniapp` 的 `build:app` 资源包）；
> Web E2E 已落在 `frontend/web/e2e/`。

### 哪些目录不用看（构建产物 / 缓存）

| 目录 | 是什么 | 要不要看 |
|---|---|---|
| `backend/server/build/` | Gradle 构建产物（class、测试报告） | 不看，可随时删 |
| `backend/server/.gradle/` | Gradle 本地状态 | 不看，可随时删 |
| `frontend/web/node_modules/` | npm 依赖（几万个文件） | 不看，`npm install` 会重建 |
| `frontend/web/dist/` | 前端构建产物 | 不看，`npm run build` 会重建 |
| `frontend/web/.tmp/` | 本机构建临时目录 | 不看，可随时删 |

> 这些都在 `.gitignore` 里，不会进版本库。觉得“文件夹太多”时，多半是被它们干扰了。
