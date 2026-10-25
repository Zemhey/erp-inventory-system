# B1 · 租户 UI 与租户管理 · 计划书

> 泳道：`feat/tnt-ui`（worktree `../erp-tnt-ui`，ASCII 联接 `D:\erp-build-tnt-ui`）
> 端口：后端 8106 · web 3026 · PG 15466（`erp-b1-pg`）· E2E 8086/3001
> 基线：main `1c8639c`（V2 收口：1204 绿 / 87.55% / 契约 172）
> 范围：FR-TNT-07（多租户成员与切换）、FR-TNT-08（平台租户管理）

---

## 1. 一句话设计

把"一个人属于多个租户"落成**库层事实**（`app_user_tenants`），把"当前用哪个租户"落成
**JWT 的 `tid` 声明**，把"平台能管租户"落成**一个只种给平台租户 ADMIN 的独立权限码**。

三句话对应三次拒绝：

| 诱惑 | 拒绝的理由 |
| --- | --- |
| 前端存一个 `tenantId`，请求时带上 | 服务端不认，等于没有隔离；改浏览器存储就能越界 |
| 切换租户只改顶栏文案 | 界面写租户 B、读写租户 A —— 比没有切换器更危险 |
| 给 ADMIN 开个后门就能管租户 | 每个租户都有自己的 ADMIN，后门就是"普通租户管理员也能管平台" |

---

## 2. 数据模型：为什么是 `app_user_tenants`，不是 `app_users.tenant_ids` 数组

### 2.1 三个候选

| 方案 | 优点 | 为什么没选 / 选了 |
| --- | --- | --- |
| `app_users.tenant_ids bigint[]` | 不加表 | 无法加外键（租户删不掉也查不清）、无法唯一约束、无法被 `TenantSql` 的谓词改写覆盖；数组里"哪个是主租户"要靠约定 |
| 新建 `app_memberships` 无 `tenant_id` | 语义干净 | 与项目"除 `tenants` 外每张表都带 `tenant_id`"的约定冲突，会成为 `TenantSchemaIT` 的清单漂移点（V2-7 的 135 格矩阵按表扫） |
| **`app_user_tenants(user_id, tenant_id)` 带 `tenant_id`** | 唯一约束在库层、外键在库层、纳入 `TenantTables` 自动受过滤 | **采用** |

### 2.2 关键取舍

**① 主租户也落一行。**
`app_users.tenant_id` 保留为"主租户"，语义不变（不迁徙、不改 V1~V16）。
但成员表里主租户**也有一行**，理由有三：

1. 登录/切换只需读**一张表**就能回答"这个人属于哪些租户"，不存在"两张表两套事实"；
2. 唯一约束 `PK(user_id, tenant_id)` 对**所有**成员关系一视同仁（含主租户）；
3. 由 `AFTER INSERT` 触发器补齐，应用层不需要记得写。

**② 为什么用触发器补主租户行，而不是要求在每条建号路径里写。**
建号路径至少三条：`UserAdminService`（HTTP）、测试造数、运维脚本。
漏一条的症状是"号建出来了但登录 403/成员为空"，而漏掉的那条**不会有编译错误**。
把不变量放进库层，新增路径自动成立。迁移里的自检（§⑤）会在回填/触发器失效时直接 `RAISE EXCEPTION`。

**③ 为什么成员表也要 `TenantSql` 改写。**
"按当前租户查成员"应自动带谓词；而"列出**某人**的全部成员关系"本质是跨租户读，
必须显式走 `TenantContext.callAsSystem`。让二者的差别体现在代码里而非注释里，
比"成员表豁免过滤"更难写错。`TenantTables.ALL` 从 55 张变为 56 张，`TenantSchemaIT` 自动覆盖。

**④ 回填与幂等。** `INSERT ... SELECT ... ON CONFLICT DO NOTHING`，与 V16 的"纯追加、可重复执行"一致；
已有额外成员关系一律不动。回滚脚本写在迁移头注释里（Flyway 不自动回滚）。

---

## 3. 平台租户管理员：`tenant:manage` + `adminBypass = false`

### 3.1 问题

`PermissionAspect` 一直把 `ROLE_ADMIN` 当全权（否则每加一个权限码都要改一次 ADMIN 的授权数据）。
但**每个租户都有自己的 ADMIN**。若 `/tenants` 用普通 `@RequirePermission("tenant:manage")`，
演示租户的管理员会因为 `ROLE_ADMIN` 短路而调得通 —— 这正是需求禁止的。

### 3.2 解法

给注解加 `adminBypass()`（默认 `true`，保持既有语义），`/tenants` 显式写 `adminBypass = false`。
关掉短路后，ADMIN 必须**真的持有**该权限码；V17 只把它种给 `tenant_id = 1` 的 ADMIN。

### 3.3 为什么"只种平台租户"要用迁移自检守住

V16 把默认租户的角色/权限复制到了演示租户。若 V17 按 `role.code` 全局插入，
演示租户 ADMIN 也会拿到平台权限。所以：

- 插入条件写死 `tenant_id = 1`；
- 迁移自检第 ③ 条断言"**非**平台租户的 ADMIN 不得持有 `tenant:manage`"，行数 > 0 即失败。

这样"为了图省事全员下发"会在**迁移期**炸掉，而不是等验收时由人肉发现。

### 3.4 平台管理员不是"跨租户业务数据超级管理员"

`tenant:manage` 只允许读 `tenants`（唯一不带 `tenant_id` 的表，走系统旁路是"跨租户元数据"，不是绕过业务隔离）。
平台管理员的请求**仍然**绑定在某个 `tid` 上，业务表仍由 `TenantSql` 过滤。
`TC-API-153` 用平台管理员身份读另一个租户的商品，断言 **404**，并用本租户同对象做正对照 200。

---

## 4. 认证：主租户（home）vs 行动租户（acting）

这是 B1 最容易写错的一处，单独列出。

| 概念 | 存在哪里 | 由什么决定 | 用来做什么 |
| --- | --- | --- | --- |
| 主租户 home | `app_users.tenant_id` | 建号时定，不随会话变化 | 账号级读写：失败计数、密码过期、改密写回 |
| 行动租户 acting | JWT `tid` | 登录（可选 `tenantCode`）/ 切换 | 业务读写、角色/权限装载 |

**为什么必须分开**：改密是把 `UPDATE app_users ...` 发到**行动租户**的上下文的。
切换租户后，若仍按行动租户写，`app_users` 行（`tenant_id` = 主租户）匹配 0 行 ——
症状是"改密提示成功但新密码登不上"，且不会有任何报错。因此 `changePassword` 显式
`TenantContext.runIn(user.tenantId(), ...)`。

### 4.1 登录流程

```
按用户名全局查账号（callAsSystem 只读这一次）
  └─ 不存在 → 仍跑一次密码编码拉平耗时 → IAM_BAD_CREDENTIALS
在【主租户】下：
  启用检查 → 密码校验 → 失败计数/锁定 → 记录成功
  选目标租户（tenantCode 缺省=主租户）
     └─ 编码未知 / 非成员 → IAM_TENANT_NOT_MEMBER（同一错误码）
     └─ 已停用           → IAM_TENANT_DISABLED
  在【目标租户】下装载角色/权限 → 签发 tid=目标 的 JWT
```

**租户编码的校验刻意放在密码校验之后**：否则"用户名存在 + 租户编码错"与"用户名不存在"
响应不同，枚举面会从"用户名"扩大到"用户名 × 租户"。

**未知编码与非成员返回同一个错误码**：已登录用户能知道"这个编码不是我的"，
但不该能借它遍历系统里有哪些租户。

### 4.2 切换租户

`POST /auth/switch-tenant { tenantCode }` → 校验成员 + 启用 → **重新签发 JWT**（`tid` 变化）→ 返回完整会话快照。

- 旧 token 在有效期内**仍然有效**并指向旧租户（JWT 无状态，与既有"登出即丢弃"取舍一致）；
  前端必须用响应里的新 token 整体覆盖本地存储。
- 幂等：状态迁移型，**不消费 `X-Request-Id`**；重复切换只产生多个彼此独立、tid 相同的 token。

### 4.3 每请求校验（`resolve`）三道闸

1. 账号存在且启用；
2. `tid` 是该账号的**成员租户**（替代 V2-7 的 `tid == app_users.tenant_id`，因为多成员时 tid 可以不是主租户）；
3. 该租户**仍然启用** —— 停用租户的既有 token 在**下一个请求**就 401，不必等 token 过期。

角色只在 `tid` 对应的租户里装载，不会出现跨租户权限并集（旧实现从系统旁路取账号，多成员时会把各租户角色并起来）。

---

## 5. 契约（本阶段唯一 owner）

只改 `packages/api-contract/openapi.yaml` 中 `/auth/login`、`/auth/me`、`/auth/switch-tenant`、`/tenants` 相关内容，
不动 MDM / 报表。新端点**直接写进契约**（ADR-0008），不登记 `UNDOCUMENTED_DEBT` / `CONTRACT_ONLY`。

| 变更 | 内容 |
| --- | --- |
| `/auth/login` 请求 | `+ tenantCode`（可选，≤32，缺省=主租户） |
| `/auth/login` · `/auth/me` · `/auth/switch-tenant` 响应 data | `+ tenantId / tenantCode / tenantName / tenants[]` |
| `/auth/switch-tenant` | **新增** POST；200 返回完整会话；403 非成员；409 租户停用；400 空编码 |
| `/tenants` | **新增** GET 列表 |
| `/tenants/{id}/enabled` | **新增** POST 启停；409 平台租户或当前所在租户；404 未知 id |

`tenants[]` 的元素在 controller 里**逐字段内联**写（而不是 `map(::toMap)` 之类的私有 helper）——
`schema-field-check.py` 靠静态解析字段，藏在 helper 后面的字段它看不见，会变成"假绿"。

---

## 6. 前端

| 位置 | 改动 |
| --- | --- |
| `stores/session.ts` | 持久化 `tenantId/tenantCode/tenantName/tenants`（`erp.tenant` / `erp.tenants`）；`switchTenant()` **整体覆盖 token**；`refresh()` 补齐并顺带验证 token/租户仍有效 |
| `layouts/MainLayout.vue` | 顶栏租户 chip；**仅 `tenants.length > 1`** 时出现切换器（`title="切换租户"`）；`router-view` 以 `tenantId` 为 key 重挂载，避免切换后残留旧租户的列表数据 |
| `views/iam/TenantManageView.vue` | `/tenants` 列表 / 启用 / 停用；平台租户与当前租户的停用按钮禁用 |
| `router/index.ts` | `/tenants` 路由 + `requiresPermission: 'tenant:manage'` 守卫 |
| `views/LoginView.vue` | 可选"租户编码"输入 |
| `api/client.ts` | `LoginPayload` / `SessionSnapshot` / `TenantView` 类型；`switchTenant` |

前端守卫**不是安全边界**：单租户用户直接调 `/api/v1/tenants` 仍 403（E2E-22 断言）。

---

## 7. 用例与测试策略

新用例号（先 `grep '@CaseId'` 复算，确认唯一）：`TC-API-145~154`、`TC-TNT-068~071`。

| 用例 | 覆盖点 |
| --- | --- |
| TC-API-145 | 登录响应带租户 + 成员列表；角色取自主租户 |
| TC-API-146 | 带 `tenantCode` 登录 → tid/角色/权限全部来自目标租户，不混入主租户角色 |
| TC-API-147 | 非成员/未知编码 403（同一码）；密码错仍 401（租户校验在密码之后） |
| TC-API-148 | `/auth/me` 刷新后恢复租户；不同 tid 的 token 给出不同租户 |
| TC-API-149 | 切换重新签发：新 token tid=2、旧 token tid=1；角色/权限换成目标租户 |
| TC-API-150 | 非成员切换 403；空编码 400；租户 2 管理员切租户 1 被拒 |
| TC-API-151 | 停用租户：登录/切换 403、既有 token 下一请求 401；主租户不受影响；启用后恢复 |
| TC-API-152 | **普通租户 ADMIN 看 /tenants → 403**；平台 ADMIN → 200（`adminBypass=false` 的关键回归） |
| TC-API-153 | 平台管理员跨租户业务数据 **404** + 同租户正对照 200；不能切到非成员租户 |
| TC-API-154 | 租户启停：成功路径；停平台/当前租户 409；未知 id 404；启用可重复 |
| TC-TNT-068 | 成员关系唯一由**库约束**保证（重复插入失败、行数不变） |
| TC-TNT-069 | 建号即得主租户成员关系（含 HTTP 建号路径） |
| TC-TNT-070 | 成员表本身受租户过滤（default 3 行 / demo 2 行 / 全局 5 行） |
| TC-TNT-071 | `tenant:manage` 只种平台租户 ADMIN，普通租户 ADMIN 权限清单里没有 |

**隔离红线**：V2-7 的 135 格跨租户矩阵保持全绿；新增端点自带"异租户 404/空 + 同租户正对照"。
不删既有用例、不改门禁、不降覆盖率门限（行 60% / 分支 50%）。

**E2E**（`11-tenant-ui.spec.ts`）：E2E-20 平台管理员可见/按钮约束；E2E-21 多成员切换（token 必须变化 + 跨租户 404 + 同租户 200 + 反向 404 + 切回）；E2E-22 单租户用户无切换器、无菜单、直连 URL 被弹回、直调接口 403。

---

## 8. 风险与已知取舍

| # | 风险/取舍 | 处理 |
| --- | --- | --- |
| 1 | 旧 token 在切换后仍有效（指向旧租户） | 与无状态登出取舍一致；前端整体覆盖 token。若要即时吊销需引入黑名单，本批不做 |
| 2 | 停用租户会让该租户所有用户**下一请求** 401 | 这是"停用"应有的语义，已由 TC-API-151 固化；恢复启用后自动可用 |
| 3 | 成员关系目前没有管理界面（只在库层追加） | 本批范围是 UI 显示/切换 + 平台启停；成员授权留待后续批次 |
| 4 | `TenantSql` 只认本项目 SQL 写法 | 新增 SQL 均为基础 `SELECT ... WHERE`；若遇到 `ON CONFLICT` 目标改写不足，补规则 + 用例，不绕过 |
| 5 | 匿名审计归属（V2-7 遗留 #1） | 本批未改变匿名请求的审计归属，仍是默认租户；继续登记 |
| 6 | 本机 Maven Central 不可达 | 构建走阿里云镜像（`build.gradle.kts` 已配），依赖解析后 `--offline` 复跑可复现；见报告"环境"节 |

---

## 9. 执行顺序

1. V17 迁移 + `TenantTables` 登记 → `TenantSchemaIT` 自动覆盖
2. `TenantMembershipRepository` / `TenantDirectory` 扩展 / `TenantRef`
3. 认证重构（home vs acting，`resolve` 三道闸，`switchTenant`）
4. `/tenants` 控制器 + `adminBypass`
5. 契约（仅 auth/tenants）→ 双门禁
6. 前端 session/layout/login/tenant 页
7. 后端新用例 → 定向跑 → 全量 `check`
8. E2E（两遍）
9. 报告
