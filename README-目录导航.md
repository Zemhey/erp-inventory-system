# 进销存系统 · 仓库目录导航

> **打开这个仓库不知道从哪看？先看这一页。**
> 本文件只回答一件事：**每个目录是什么、我该看哪个。**

---

## 30 秒版：我要找什么

| 我想…… | 打开这个 | 说明 |
|---|---|---|
| **看前端界面 / 改页面** | [`frontend/`](frontend/) | ✅ 所有客户端都在这（Web、小程序、Android） |
| **看后端业务逻辑 / 加接口** | [`backend/`](backend/) | ✅ 所有服务端代码都在这 |
| **想知道整个项目是什么、怎么继续** | [`项目总纲.md`](项目总纲.md) | ★ 一份文件看懂全局（大纲/规划/索引/交接） |
| 看项目计划、要做什么 | [`docs/00-索引与追踪矩阵.md`](docs/00-索引与追踪矩阵.md) | 唯一入口：进度看板 + 需求↔测试矩阵 |
| 看某个里程碑怎么做 | [`.plan/`](.plan/) | M1 已完成 / M3 进行中 |
| 看接口有哪些、怎么调 | [`packages/api-contract/openapi.yaml`](packages/api-contract/openapi.yaml) | 接口单一事实源 |
| 看代码结构、有哪些模块 | [`index/`](index/) | 自动生成的代码索引 |
| 起本地环境 / 跑构建 | [`scripts/`](scripts/) | 一键起环境、建构建联接、生成索引 |
| 看数据库怎么起的 | [`infra/`](infra/) | Docker Compose（PG + Redis + RabbitMQ） |
| 看架构决策为什么这么定 | [`docs/07-决策记录ADR/`](docs/07-决策记录ADR/) | 11 条 ADR，含放弃方案与理由 |

---

## 目录总览（自上而下）

```
自选/                          ← 仓库根
├── backend/                   ★ 服务端（Java 17 + Spring Boot 3）
│   └── server/
│       ├── src/main/java/com/erp/     业务代码（按域分模块）
│       ├── src/main/resources/        配置与 Flyway 迁移脚本
│       ├── src/test/java/com/erp/     152 条测试
│       └── build.gradle.kts           构建配置与覆盖率门禁
│
├── frontend/                  ★ 所有客户端
│   └── web/                   Vue3 + TypeScript + Element Plus（M3）
│       ├── src/api/           接口封装（含幂等键、token、错误处理）
│       ├── src/components/    跨页复用组件（PageTable 列表骨架 / StatusTag 状态标签）
│       ├── src/utils/         跨页复用逻辑（documentStatus.ts 状态机→动作映射）
│       ├── src/views/         页面（views/mdm 主数据、views/pur 进货…）
│       ├── src/layouts/       布局（侧边栏 + 顶栏）
│       ├── src/router/        路由与权限守卫
│       ├── src/stores/        全局状态（登录会话）
│       └── scripts/           构建期校验（check-status-parity.mjs 状态机镜像比对）
│
├── docs/                      📖 规划与设计文档
│   ├── 00-索引与追踪矩阵.md    ★ 项目唯一入口
│   ├── 01~06                  范围、领域模型、架构、数据模型、契约、测试
│   └── 07-决策记录ADR/        架构决策记录（11 条）
│
├── index/                     🔍 代码索引（脚本生成，禁止手写）
│   ├── modules.md             模块清单与规模
│   ├── api-map.md             接口清单
│   ├── test-map.md            测试用例索引（95 条）
│   └── coverage-map.md        覆盖率
│
├── packages/                  📦 跨端共享
│   └── api-contract/          OpenAPI 单一事实源
│
├── infra/                     🐳 本地环境编排
│   └── compose/               PostgreSQL + Redis + RabbitMQ
│
├── scripts/                   ⚙️ 运维脚本
│   ├── dev-up.ps1/.sh         一键起依赖环境
│   ├── build-env.ps1          建 ASCII 构建联接（Windows 中文路径必需）
│   ├── gen-index.ps1/.sh      重新生成代码索引
│   ├── seed-dev-user.ps1      创建开发用登录账号
│   └── bcrypt-hash.ps1        生成 BCrypt 密码哈希
│
├── .plan/                     📋 里程碑计划书
│   ├── M1-计划书.md           已完成（后端核心域）
│   └── M3-计划书.md           🚧 执行中（Web 前端）
│
├── .github/workflows/         🔁 CI 流水线
│
├── 项目总纲.md                 ★ 全局：大纲/规划/索引/交接（单文件版）
├── 任务要求.md                 课程原始要求
├── 方向建议与落地路线.md        顶层大纲（M0→M9 里程碑与测试体系）
└── README.md                  项目首屏
```

---

## 两个最容易困惑的地方

### 1. `frontend/` 与 `backend/` 为什么这样分

**约定：凡是"给用户看的界面"→ `frontend/`；凡是"跑在服务器上的"→ `backend/`。**

- Web 端（Vue3）→ `frontend/web/`
- 小程序（uni-app）→ 将来 `frontend/miniapp/`
- Android App → 将来 `frontend/android/`
- Spring Boot 服务 → `backend/server/`

这样打开 `frontend/` 看到的就是**全部前端**，打开 `backend/` 看到的就是**全部后端**，
不需要逐个点进去猜。这条约定的来历见 [ADR-0011](docs/07-决策记录ADR/ADR-0011-目录调整backend与frontend分区.md)。

### 2. 哪些目录**不用看**（构建产物 / 缓存）

| 目录 | 是什么 | 要不要看 |
|---|---|---|
| `backend/server/build/` | Gradle 构建产物（class、测试报告） | ❌ 不看，可随时删 |
| `backend/server/.gradle/` | Gradle 本地状态 | ❌ 不看，可随时删 |
| `frontend/web/node_modules/` | npm 依赖（几万个文件） | ❌ 不看，`npm install` 会重建 |
| `frontend/web/dist/` | 前端构建产物 | ❌ 不看，`npm run build` 会重建 |
| `frontend/web/.tmp/` | 本机构建临时目录 | ❌ 不看，可随时删 |

> 这些都在 `.gitignore` 里，不会进版本库。
> **如果你觉得"文件夹太多看不懂"，多半是被这几个构建产物目录干扰了 —— 它们占了 90% 的文件数。**

---

## 启动与验证（三条命令）

```powershell
# 1. 起依赖环境（PostgreSQL + Redis + RabbitMQ）
.\scripts\dev-up.ps1

# 2. 起后端（http://127.0.0.1:8080）
cd backend\server; .\gradlew.bat bootRun

# 3. 起前端（http://127.0.0.1:3000）
cd frontend\web; npm install; npm run dev
```

首次使用需要先建 ASCII 构建联接（Windows 中文路径问题）：
`.\scripts\build-env.ps1` —— 详见 [ADR-0010](docs/07-决策记录ADR/ADR-0010-Windows中文路径用ASCII联接构建.md)。

首次登录需要创建账号（V3 迁移刻意不预置账号）：
`.\scripts\seed-dev-user.ps1` → 默认 `admin / Admin@12345`

---

## 相关文档

- [项目总纲.md](项目总纲.md) —— ★ 一份文件看懂全局（大纲/可执行计划/项目规划/索引/交接）
- [README.md](README.md) —— 项目首屏（想证明什么、工程亮点）
- [docs/00-索引与追踪矩阵.md](docs/00-索引与追踪矩阵.md) —— **做到哪了、还差什么**
- [方向建议与落地路线.md](方向建议与落地路线.md) —— 顶层大纲（M0→M9）
- [.plan/M1-计划书.md](.plan/M1-计划书.md) —— 已完成的 M1 怎么做的
- [.plan/M3-计划书.md](.plan/M3-计划书.md) —— 🚧 正在做的 M3（Web 前端）
