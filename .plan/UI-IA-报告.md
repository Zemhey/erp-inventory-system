# UI-IA 信息架构与驾驶舱 · 报告

> 分支：`feat/ui-ia`（基线 main `653b553`）
> 日期：2026-10-24
> 结论：**完成并通过硬验收**（构建/检查通过、21 项入口全可达、驾驶舱 0 控制台错误、11 个首屏请求、权限过滤仍生效）。
> 未合并 main、未 push；后端、契约、router、E1/ReportView 文件均未改。

## 1. 交付物

| 文件 | 说明 |
|---|---|
| `frontend/web/src/layouts/MainLayout.vue` | 21 项扁平菜单 → 8 组；页头“分组 / 标题”；折叠与移动端保持 |
| `frontend/web/src/views/DashboardView.vue` | 保留原四块，新增 5 个可行动区块 + 三态 + 权限门控 |
| `.plan/UI-IA-计划书.md` | 设计与验收计划 |
| `.plan/UI-IA-报告.md` | 本报告 |

`git diff --stat`：2 个源码文件，+1052 / −110 行（不计本报告与计划书）。

## 2. 菜单改造

### 2.1 改造前（21 项扁平，按数组追加顺序）

```
工作台
商品与 SKU
供应商
客户 / 会员
仓库
库存查询
进货单
出货单
退货单
结算
报表
审批工作台
用户与角色
请购与质检
应付与付款
调拨作业
盘点与调整
毛利分析
报价单
拣货发货
应收 / 收款
```

### 2.2 改造后（8 组 / 21 项，配置在 [MainLayout.vue:96](file:///D:/MajorCacheFolder/Company%20system/erp-ui-ia/frontend/web/src/layouts/MainLayout.vue#L96)）

```
工作台                        ← 一级菜单项（单入口组，不套二级菜单）
主数据 ▾
  商品与 SKU
  供应商
  客户 / 会员
  仓库
库存 ▾
  库存查询
  调拨作业
  盘点与调整
采购 ▾
  请购与质检
  进货单
  应付与付款
销售 ▾
  报价单
  出货单
  拣货发货
  退货单
  应收 / 收款
财务 ▾
  结算
  毛利分析
报表 ▾
  报表
系统 ▾
  审批工作台
  用户与角色
```

- 入口总数仍为 21，权限码逐项保留；`visibleGroups` 过滤链（[MainLayout.vue:176](file:///D:/MajorCacheFolder/Company%20system/erp-ui-ia/frontend/web/src/layouts/MainLayout.vue#L176)）未断。
- `unique-opened` + 路由变化时自动展开当前组；折叠态 64px，子菜单浮层可打开。
- 页头改为面包屑：`主数据 / 商品与 SKU`、`销售 / 退货单` 等；工作台不重复显示组名。
- **未改 router**，标题全部来自现有 `route.meta.title`。

### 2.3 E1 合并指引（必须）

E1 分支的两项为 `/audits`（审计查询）、`/dicts`（数据字典），权限 `sys:read`。合并时在 `system` 组 `items` 末尾追加（代码内已留注释）：

```ts
{ path: '/audits', title: '审计查询', icon: Search, permission: 'sys:read' },
{ path: '/dicts', title: '数据字典', icon: Notebook, permission: 'sys:read' },
```

同时在 `@element-plus/icons-vue` 导入 `Search`、`Notebook`。冲突解决原则：**保留本泳道的 `menuGroups` 结构 + E1 的两行和 router 改动**，不要退回扁平数组。E1 的两项不要加到其它组。

## 3. 驾驶舱区块与取数

保留原内容：问候/刷新、4 个 KPI、自然语言操作框、库存一致性、当前账号权限。

新增区块（模板从 [DashboardView.vue:440](file:///D:/MajorCacheFolder/Company%20system/erp-ui-ia/frontend/web/src/views/DashboardView.vue#L440) 起）：

| 区块 | 端点 | 权限门控 | 口径 |
|---|---|---|---|
| 待我审批 | `GET /approvals?status=PENDING&page=1&size=200`、`status=TRANSFERRED` | `aps:read`；仅 `aps:approve` 才标记“可处理” | 当前节点 `approverPermission` 匹配账号权限码；卡片注明“是否已同意以审批台为准” |
| 待发货 / 拣货 | `GET /shipments?page=1&size=200` | `sal:read` | 客户端筛 DRAFT / PICKING / PICKED，分别显示计数与前 5 条；`total > 200` 明确提示统计范围 |
| 库存预警 / 零可用 | **复用** `GET /inventory` 的余额数组 | `inv:read` | `availableQty <= 0`，按可用量升序；零额外请求 |
| 应收逾期 | `GET /receivables?overdue=true&page=1&size=5` | `sal:read` | `overdue=true` 由后端过滤；显示后端 `total` 与前 5 条 |
| 近 7 日单据 | `GET /reports/purchase`、`GET /reports/sales`（`from/to/groupBy=day`） | `rpt:read` | 今日 + 近 7 日张数/金额；只统计 PARTIAL、COMPLETED 且有实际入库/出货量 |

KPI 端点与权限：

| 卡片 | 端点 | 权限 | 无权限时 |
|---|---|---|---|
| 商品 | `/products?page=1&size=1` | `mdm:read` | 值 `-`，提示“无 mdm:read 权限” |
| 库存行 | `/inventory` | `inv:read` | 同上（inv） |
| 进货单 | `/purchase-orders?page=1&size=1` | `pur:read` | 同上（pur） |
| 出货单 | `/sales-orders?page=1&size=1` | `sal:read` | 同上（sal） |
| 一致性 | `/inventory/consistency-check` | `inv:read` | 卡片显示“无 inv:read 权限” |

**首屏请求数：admin 11 个 GET**（实测）：

```
/approvals?status=PENDING&page=1&size=200
/approvals?status=TRANSFERRED&page=1&size=200
/inventory
/inventory/consistency-check
/products?page=1&size=1
/purchase-orders?page=1&size=1
/receivables?overdue=true&page=1&size=5
/reports/purchase?from=2026-10-18&to=2026-10-24&groupBy=day
/reports/sales?from=2026-10-18&to=2026-10-24&groupBy=day
/sales-orders?page=1&size=1
/shipments?page=1&size=200
```

受限角色按权限跳过请求；无权限区块直接不渲染，避免首屏刷 403。

### 3.1 三态

- loading：区块内 `el-skeleton`；KPI 区域 `v-loading`。
- empty：明确文案，例如“没有待拣货 / 待发货的发货单”“没有可用量 ≤ 0 的库存行”。
- error：区块内 `el-alert` + 重试按钮；单块失败不影响其它块。KPI 失败保留顶部汇总错误，失败值显示 `-` 并说明不是真实值。

## 4. 验收结果

### 4.1 构建与检查（`D:\erp-build-ui\frontend\web`）

```
> erp-web@0.1.0 build
> vue-tsc --noEmit && vite build

vite v6.4.5 building for production...
✓ 1760 modules transformed.
...
dist/assets/index-DMgmhmez.js  1,080.68 kB │ gzip: 355.44 kB
(!) Some chunks are larger than 500 kB after minification.   ← 既有警告，未新增大依赖
✓ built in 11.61s
```

```
> erp-web@0.1.0 check
> vue-tsc --noEmit && node scripts/check-status-parity.mjs
...
✓ 状态机镜像一致（86 项检查全部通过）
```

**构建环境注意（不涉及仓库改动）**：本机 Node 24.11.1 + 锁文件里的 esbuild **0.25.12** 对约 1MB 以上的 transform 临时文件删除报
`[vite:esbuild-transpile] remove ... Access is denied`；用隔离目录复验，esbuild 0.25.11 正常、0.25.12 失败（Node 22.20.0 同样失败）。
本泳道执行 `npm ci` 后，用 `npm install esbuild@0.25.11 --no-save --package-lock=false` 完成构建；`package.json` / `package-lock.json` **未改动**。
这是本机工具链问题，其他前端泳道可能同样命中，建议指挥侧决定是否统一升级/固定 esbuild（见 §8 待办）。

### 4.2 Playwright 实测（Chrome，3025 → 8080）

登录 `admin / Admin@12345` 后：

| 项目 | 结果 |
|---|---|
| 菜单结构 | 1 个一级“工作台” + 7 个分组，共 **21** 个 `.el-menu-item` |
| 21 项入口逐个点击 | **21/21 可达，0 失败**；URL 全部正确 |
| 页头分组/标题 | 例：`主数据 / 商品与 SKU`、`库存 / 库存查询`、`系统 / 审批工作台`，21 项均正确 |
| 折叠 | 侧栏 64px；分组浮层 `.erp-menu-popup` 可见 |
| 移动端 390×844 | 侧栏 64px；用户名隐藏；内容不塌 |
| 驾驶舱首屏请求 | **11**（列表见 §3） |
| 驾驶舱控制台错误 | **0** |
| 驾驶舱失败响应（≥400） | **0** |
| 页面异常 | 0 |
| 权限过滤（localStorage 注入 `mdm:read + sal:read`） | 仅显示 工作台 + 主数据(4) + 销售(4) = **9 项**；退货单（rma:read）等被正确隐藏；过滤链未断 |

真实 8080 数据（未写业务数据）：商品 **4**、库存行 **1**、进货单 **0**、出货单 **0**；审批/发货/逾期/报表均无数据。驾驶舱五个区块均显示**明确空态**，没有伪造数字。

“有数据”渲染验证（Playwright `page.route` 拦截，仅用于 UI 验证，不写库）：

- 待我审批：显示 `PO-MOCK-001 / 进货单 / 主管审批 / 金额 ¥123.45`，计数 1 条可处理；
- 待发货：DRAFT/PICKING 两条，状态条显示 待拣货 1 / 拣货中 1 / 待确认发货 0；
- 零可用：SKU #1 批次 B1，实存 0 / 预占 0 / 冻结 0，标签“可用 0”；
- 应收逾期：客户 #3，到期 2026-09-30，余额 ¥80.00；
- 近 7 日：今日 3 张、近 7 日 5 张、金额 ¥500.00（假响应验证渲染，非真实业务数据）。

### 4.3 截图

截图保存在工作区外（不随仓库提交）：

| 截图 | 路径 |
|---|---|
| 菜单展开（销售组） | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\01-menu-expanded.png` |
| 驾驶舱桌面（真实空态） | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\02-dashboard-desktop.png` |
| 折叠态 | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\03-menu-collapsed.png` |
| 折叠态浮层 | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\04-menu-collapsed-popup.png` |
| 移动端 390px | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\05-mobile-dashboard.png` |
| 驾驶舱有数据（mock 响应） | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\06-dashboard-with-data.png` |
| 受限权限菜单（9 项） | `C:\Users\Administrator\qoder-tmp-ui-ia\shots\07-limited-permissions.png` |

原始断言数据：同目录 `result.json`、`mock-result.json`。

## 5. 权限过滤自证

- 代码层：`visibleGroups` 对每个 `items` 调 `session.hasPermission`；空组被 `.filter(items.length > 0)` 移除（[MainLayout.vue:176](file:///D:/MajorCacheFolder/Company%20system/erp-ui-ia/frontend/web/src/layouts/MainLayout.vue#L176)）。
- 运行层：注入 `['mdm:read','sal:read']` 后菜单只剩 9 项，且驾驶舱只渲染对应权限的区块（审批/库存/报表区块消失）。
- 驾驶舱取数也按权限跳过，避免无权限账号在控制台刷 403。

## 6. 改动边界

- **改**：`MainLayout.vue`、`DashboardView.vue`。
- **未改**：`router/index.ts`、`views/sys/**`、`views/rpt/ReportView.vue`、`backend/**`、`packages/api-contract/**`、`docs/**`、`README.md`、`index/`、其他 worktree。
- **未新增依赖**；package.json / package-lock 未变（构建时的 esbuild 降级仅在本 worktree 的 node_modules，`--no-save`）。

## 7. 已知合并冲突点

| 文件 | 与谁 | 处理 |
|---|---|---|
| `MainLayout.vue` | E1（在途） | 以本泳道 `menuGroups` 为准，在 `system.items` 追加 E1 两行（§2.3）；保留 E1 的 router 追加；不要保留旧的 21 项扁平数组 |
| `DashboardView.vue` | 无（本泳道独占） | 直接采用 |
| `router/index.ts` | E1 | 本泳道未改；合并时保留 E1 的 `/audits`、`/dicts` 路由 |

合并顺序按批次约定：E1 先合，UI-IA 后合；合后重跑 `npm run build` + `npm run check`，并按 §4.2 抽查 8 组 / 21 项（E1 合并后为 23 项 / 系统组 4 项）。

## 8. 待办（不属本泳道，未自行实现）

1. **后端库存洞察接口 500**（当前 8080，2026-10-24 22:57 实测）：
   - `GET /inventory/alerts?type=SAFETY` → 500，traceId `d4330ace04bb4902`
   - `GET /inventory/alerts` → 500，traceId `ceccd0b16cab4aa4`
   - `GET /inventory/replenishment` → 500，traceId `88b5bc10886d4288`
   既有的 `InventoryView` 仍会调用这两个接口，因此逐个点完 21 项时，控制台会出现 **2 条 500**（属于既有页面的后端问题，不是驾驶舱）。驾驶舱已改用余额口径的“零可用”，自身 0 错误。
   建议后端 owner 检查 `skus.min_level` 的库结构与洞察 SQL；修复后驾驶舱可再加“低于安全库存阈值”区块（当前已预留位置与三态）。
2. **favicon 404**：21 项走查中控制台唯一的非 500 报错是 `/favicon.ico` 404（项目原本未提供）。不在本泳道写入范围（`index.html` 未改）。
3. **esbuild 0.25.12 本机缺陷**：见 §4.1。建议统一决定固定/升级版本，否则其他前端泳道在 `npm ci` 后会遇到同样构建失败。
4. **审批“待我审批”精度**：前端无法从会话拿到 userId，无法判断当前用户是否已在该节点同意过；现按节点权限码匹配并在卡片注明，最终以审批台详情为准。
5. **列表 200 条上限**：审批/发货区块在 `total > 200` 时只统计最近 200 条并明确提示；若业务量增大，建议后端提供聚合/待办端点。

## 9. Git 状态

- 分支 `feat/ui-ia`，提交包含 2 个源码文件 + 2 个 `.plan` 文档。
- `git status --short` 提交后应为空（`node_modules/`、`dist/` 均被 gitignore）。
- 未 push、未 merge、未 `reset --hard`。
