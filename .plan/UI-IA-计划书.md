# UI-IA 信息架构与驾驶舱 · 计划书

> 日期：2026-10-24
> 分支：`feat/ui-ia`（worktree `../erp-ui-ia`，基线 main `653b553`）
> 端口：Web dev **3025** → 代理用户后端 **8080**（只读）；构建走 `D:\erp-build-ui`
> 写入范围：`frontend/web/src/layouts/MainLayout.vue`、`frontend/web/src/views/DashboardView.vue`、`.plan/UI-IA-*`

## 1. 目标与非目标

### 目标

1. 把 21 项扁平菜单收成 8 组，入口与权限一个不少，折叠/移动端不退化。
2. 工作台保留原有 KPI / 自然语言框 / 库存一致性 / 权限展示，增加可行动区块：
   待我审批、待发货/拣货、库存预警（零可用）、应收逾期、今日/近 7 日单据。
3. 页头显示“分组 / 当前页”，让用户知道自己在系统哪一块。
4. 每个新增区块都有 loading / empty / error 三态，错误可重试；不造假数字。
5. 控制首屏请求数，全部使用现有端点，不新增后端、不改契约。

### 非目标

- 不改 `router/index.ts`、`views/sys/**`、`ReportView.vue`、后端、契约。
- 不添加新依赖、不做 BPMN/图表库、不改 E1 的未提交页面。
- 不 push / merge / `reset --hard`；不占用 8080、3001、3022、3024。

## 2. 菜单分组设计

单一配置数组 `menuGroups`（[MainLayout.vue:96](file:///D:/MajorCacheFolder/Company%20system/erp-ui-ia/frontend/web/src/layouts/MainLayout.vue#L96)），每组 `items` 内逐项声明权限码；`visibleGroups` 复用原 `session.hasPermission` 过滤，空组自动隐藏。

| # | 组 | 入口（权限码） |
|---|---|---|
| 1 | 工作台 | 工作台（无权限码；`flat` 直接渲染为一级菜单项） |
| 2 | 主数据 | 商品与 SKU、供应商、客户/会员、仓库（均 `mdm:read`） |
| 3 | 库存 | 库存查询、调拨作业、盘点与调整（均 `inv:read`） |
| 4 | 采购 | 请购与质检（`pur:read`）、进货单（`pur:read`）、应付与付款（`pur:payable`） |
| 5 | 销售 | 报价单、出货单、拣货发货、退货单（`rma:read`）、应收/收款（其余 `sal:read`） |
| 6 | 财务 | 结算（`set:read`）、毛利分析（`cst:read`） |
| 7 | 报表 | 报表（`rpt:read`） |
| 8 | 系统 | 审批工作台（`aps:read`）、用户与角色（`iam:read`） |

合计 1 + 4 + 3 + 3 + 5 + 2 + 1 + 2 = **21 项 / 8 组**。

交互决定：

- 工作台是单入口组，直接作为一级 `el-menu-item`，不做一个只有一项的二级菜单；其余 7 组用 `el-sub-menu`。
- `unique-opened`：同时只展开一个组，避免“分组后还是 21 行全铺开”。
- `default-openeds` 首屏展开当前路由所在组；`watch(route.path)` 在前进/后退时调用 `menuRef.open(group.key)`（折叠态跳过）。
- 折叠态子菜单浮层被 teleport 到 body，样式用 `:global` + `popper-class="erp-menu-popup"`。
- 页头用 `el-breadcrumb` 显示“组 / 路由标题”；工作台组名与标题相同，不重复显示。
- 不改 router；标题来自现有 `route.meta.title`。

### E1 合并预留

`system` 组 `items` 末尾已留注释，合并 E1 时按下面两行追加（并补 `Search`、`Notebook` 图标导入）：

```ts
{ path: '/audits', title: '审计查询', icon: Search, permission: 'sys:read' },
{ path: '/dicts', title: '数据字典', icon: Notebook, permission: 'sys:read' },
```

## 3. 驾驶舱设计

保留：问候与刷新、4 个 KPI、`NlCommandBox`、库存一致性、当前账号权限。

新增区块与取数：

| 区块 | 端点 | 权限 | 口径 / 说明 |
|---|---|---|---|
| 待我审批 | `GET /approvals?status=PENDING&page=1&size=200` + `status=TRANSFERRED` | `aps:read`；可处理判定还需 `aps:approve` | 客户端按当前节点 `approverPermission` 匹配账号权限码；超过 200 条明确提示只统计最近 200 条 |
| 待发货/拣货 | `GET /shipments?page=1&size=200` | `sal:read` | 客户端筛 DRAFT / PICKING / PICKED；`total > 200` 时标注统计范围 |
| 库存预警/零可用 | 复用 `GET /inventory` 的余额数组 | `inv:read` | 余额口径：`availableQty <= 0`，按可用量升序；**不新增请求** |
| 应收逾期 | `GET /receivables?overdue=true&page=1&size=5` | `sal:read` | `total` 为后端过滤后的逾期笔数，卡片显示最近 5 笔 |
| 今日/近 7 日单据 | `GET /reports/purchase` + `/reports/sales`（`from/to/groupBy=day`） | `rpt:read` | 只统计有实际入库/出货量的 PARTIAL、COMPLETED；今日 + 近 7 日张数与金额 |
| KPI | `/products`、`/inventory`、`/purchase-orders`、`/sales-orders`、`/inventory/consistency-check` | 各自模块 read | 无权限不发请求，卡片注明“无 xxx 权限” |

首屏请求预算（admin，全权限）：**11 个 GET**，全部 `Promise.allSettled` 并发；受限角色按权限跳过。

三态：

- loading：卡片内 `el-skeleton`；KPI 区域 `v-loading`。
- empty：明确的业务空文案（如“没有待拣货 / 待发货的发货单”）。
- error：卡片内 `el-alert` + 重试按钮；KPI 失败保留顶部汇总错误，单块失败不拖垮整页。

### 库存预警口径调整

实测 8080 的 `/inventory/alerts?type=SAFETY` 与 `/inventory/replenishment` 均返回 500（见报告 §8）。本泳道不改后端，按任务允许的“余额查询”口径实现零可用列表；安全库存阈值预警作为后端待办登记，不用假数据填充。

## 4. 验收计划

1. `npm run build`（`vue-tsc --noEmit && vite build`）与 `npm run check` 通过。
2. Playwright（Chrome）登录 `admin / Admin@12345`，在 3025 上：
   - 校验 8 组结构、21 项菜单逐个点击可达；
   - 折叠 64px、浮层可见、移动端 390px 侧栏 64px、用户名隐藏；
   - 首屏请求计数；驾驶舱无控制台错误 / 无失败响应。
3. 受限权限用 localStorage 注入（不登录受限账号、不写业务数据）：只给 `mdm:read + sal:read`，确认菜单只剩 9 项。
4. 用 Playwright 路由拦截返回代表性数据，验证五个区块的“有数据”渲染；拦截仅用于 UI 验证，不写库。
5. 真实 8080 无业务单据时，所有区块必须显示明确空态而不是 0 假数据。

## 5. 风险与已知冲突

| 风险 | 处理 |
|---|---|
| `MainLayout.vue` 与 E1 冲突 | 保留本泳道 `menuGroups` 结构，按 §2 把 E1 两行加入 `system.items`；E1 的 router 改动原样保留 |
| 8080 库存洞察接口 500 | 驾驶舱改用余额口径；报告登记待办与 traceId；现有 InventoryView 仍会报 500（非本泳道文件） |
| esbuild 0.25.12 在本机删除 >1MB 临时文件报 Access denied | 本泳道本地安装 0.25.11（`--no-save`，package.json/lock 不动）完成构建；报告写明复现与影响 |
| 审批“待我审批”无法知道当前用户是否已同意 | 按节点权限码匹配并在卡片注明；最终以审批台详情为准 |
| 列表端点 200 条截断 | 明确显示“统计基于最近 200 条”，不把截断说成全量 |
