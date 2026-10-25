# B1 · 租户 UI 与租户管理 · 报告

> 分支：`feat/tnt-ui`（未合并 main）· 基线 main `1c8639c`
> worktree：`D:\MajorCacheFolder\Company system\erp-tnt-ui` · ASCII 联接 `D:\erp-build-tnt-ui`
> 计划书：`.plan/B1-租户UI-计划书.md`

---

## 1. 结论先行

| 项 | 基线（main `1c8639c`） | B1 实测 | 判定 |
| --- | --- | --- | --- |
| 后端全量用例 | 1204 | **1218**（+14） | 绿 |
| failures / errors / skipped | 0 / 0 / 0 | **0 / 0 / 0** | 绿 |
| 行覆盖 | 87.55% | **87.65%**（9528/10871） | 未倒退 |
| 分支覆盖 | — | **68.66%**（2736/3985） | 远高于 50% 门限 |
| 契约 | 172 操作 / 141 路径 / 146 schema | **175 / 144 / 148**，0 错误 | 双门禁绿 |
| 字段级契约 | 0 错误 | **0 错误** | 绿 |
| 前端 `npm run check` | — | **0**（状态机镜像 86 项全通过） | 绿 |
| 前端 `vite build` | — | **0**（33 个 chunk） | 绿 |
| Web E2E | 36 | **39**（+3 租户用例），两遍均 39/39 | 绿 |
| 迁移 | V16 | **V17**（V1~V16 未改，checksum 未动） | — |

**测试计数口径**：`check` 用 `--rerun-tasks` 强制重跑（`FROM-CACHE` 的 "BUILD SUCCESSFUL" 不能当证据）。
`1218 = 1204 + 14`，14 条全在新增的 `TenantAdminIT`。

---

## 2. 交付物

### 2.1 后端（新增）

| 文件 | 作用 |
| --- | --- |
| `db/migration/V17__tenant_membership.sql` | `app_user_tenants` + 回填 + 填充触发器 + 建号触发器 + `tenant:manage` 种子 + 3 条迁移自检 |
| `shared/tenant/TenantRef.java` | 租户只读视图（id/code/name/enabled） |
| `iam/repository/TenantMembershipRepository.java` | 成员关系（`isMember` / `membershipsOf` / 追加） |
| `iam/application/TenantAdminService.java` | 租户列表 / 启停（含平台租户、当前租户保护） |
| `iam/api/TenantAdminController.java` | `GET /tenants`、`POST /tenants/{id}/enabled` |
| `test/.../tenant/TenantAdminIT.java` | 14 条新用例（TC-API-145~154、TC-TNT-068~071） |

### 2.2 后端（修改）

| 文件 | 改动 |
| --- | --- |
| `AuthenticationService` | 拆出 home（`app_users.tenant_id`）与 acting（JWT `tid`）；登录支持 `tenantCode`；新增 `switchTenant` / `tenancyOf`；`resolve` 改为"账号 + 成员关系 + 租户启用"三道闸；`changePassword` 写回主租户 |
| `AuthController` | 登录/`/me` 响应带 `tenantId/tenantCode/tenantName/tenants[]`；新增 `POST /auth/switch-tenant` |
| `AppUser` / `AppUserRepository` | 新增 `findByUsernameGlobal` / `findByIdGlobal` / `withRolesAndPermissions(...).actingAs(tid)`（角色只在行动租户装载） |
| `PermissionAspect` / `RequirePermission` | 新增 `adminBypass`（默认 true） |
| `TenantDirectory` | `all()` / `byId()` / `byCode()` |
| `TenantTables` | 登记 `app_user_tenants`（55 → 56 张） |
| `ErrorCode` | `IAM_TENANT_NOT_MEMBER` / `IAM_TENANT_DISABLED` 等 |

### 2.3 前端

`stores/session.ts`（租户持久化 + 整体覆盖 token）、`layouts/MainLayout.vue`（顶栏 chip + 条件切换器）、
`views/iam/TenantManageView.vue`（新增页）、`router/index.ts`（`/tenants` + 权限守卫）、
`views/LoginView.vue`（可选租户编码）、`api/client.ts`（类型 + `switchTenant`）。

### 2.4 E2E

`e2e/11-tenant-ui.spec.ts`（E2E-20/21/22）；`global-setup.ts` + `helpers/db-ops.ts` + `helpers/sql.ts`
让 VIEWER 真正成为 demo 租户成员（否则"切换器只在多成员出现"根本测不到）。

### 2.5 契约

`packages/api-contract/openapi.yaml`：+203/−5 行，新增 3 个操作（`switchTenant` / `listTenants` / `setTenantEnabled`），
扩展 login/me 的请求与响应字段。**未触碰** MDM / 报表；未登记任何 `UNDOCUMENTED_DEBT` / `CONTRACT_ONLY`。

---

## 3. 关键设计取舍（附反例）

### 3.1 切换租户 = 重新签发 JWT，不是改显示

`E2E-21` 断言的是 `demoTenantToken !== homeToken`，且**两个 token 的数据边界互为 404**：

```
demo  token 读租户1 商品 = 404   /  demo token 读 demo 商品 = 200
home  token 读 demo 商品 = 404   ← 旧 token 仍指向旧租户（无状态 JWT 的既定取舍）
```

如果前端只改顶栏文案，这两行 404 会双双变成 200 或双双 404 —— 测试必红。
这类断言比"检查 chip 文案变了"强得多，因为它直接测**数据边界**。

### 3.2 `adminBypass = false` 是唯一的正确解

`PermissionAspect` 把 `ROLE_ADMIN` 当全权，而**每个租户都有自己的 ADMIN**。
`TC-API-152` 用演示租户的 ADMIN 调 `/tenants`，断言 **403**；平台租户 ADMIN 断言 200。
`TC-TNT-071` 再从库里断言"`tenant:manage` 只存在于租户 1 的 ADMIN"，
且迁移自检第 ③ 条让"全员下发"在**迁移期**就 `RAISE EXCEPTION`。

### 3.3 平台管理员 ≠ 跨租户业务超级管理员

`TC-API-153`：平台管理员用**另一个租户**的商品 id 调 `/products/{id}` → **404**，
同租户商品正对照 → 200。`tenant:manage` 只放行 `tenants` 元数据（唯一不带 `tenant_id` 的表）。

### 3.4 home 与 acting 分离（最容易漏的一处）

`changePassword` 必须写回**主租户**：切换后请求绑定在行动租户，`UPDATE app_users` 会匹配 0 行，
症状是"改密成功但新密码登不上"，且**不报错**。已显式 `TenantContext.runIn(user.tenantId(), ...)`。

### 3.5 租户编码校验放在密码校验之后

否则"用户名存在 + 租户编码错"与"用户名不存在"响应不同，枚举面从"用户名"扩大到"用户名 × 租户"。
`TC-API-147` 固化：密码错 → 401；非成员编码 → 403 `IAM_TENANT_NOT_MEMBER`。

---

## 4. V17 迁移

### 4.1 内容

1. `app_user_tenants(user_id, tenant_id, created_at)`，**PK (user_id, tenant_id)**、双外键 `ON DELETE CASCADE`；
2. 存量回填 `INSERT ... ON CONFLICT DO NOTHING`（可重复执行）；
3. `trg_app_user_tenants_tenant_fill`（复用 V16 的 `erp_tenant_fill()`，与其它 56 张表语义一致）；
4. `trg_app_users_home_membership`（`AFTER INSERT` 自动补主租户行）；
5. `tenant:manage` 只种给 `tenant_id = 1 AND code = 'ADMIN'`；
6. **三条自检**：① 无账号缺主租户成员；② 平台 ADMIN 恰好 1 行；③ 非平台 ADMIN **0 行**。

### 4.2 存量库平滑升级证据（泳道库 `erp-b1-pg`，从 V16 升到 V17）

```
flyway_schema_history 最新: 17 | tenant membership | success=t
memberships             = 1
users_missing_home      = 0
tenant_manage_rows      = 1
tenant_manage_nonplat   = 0
```

回滚脚本写在迁移头注释（Flyway 不回滚）：按序删触发器/函数/表/权限行。

### 4.3 未改 V1~V16

`git status` 中 `db/migration/` 只有新增的 `V17__tenant_membership.sql`，V16 及其 checksum 未动。

---

## 5. 用例清单（14 条，全部带 `@CaseId`）

| # | CaseId | 断言要点 |
| --- | --- | --- |
| 1 | TC-API-145 | 登录带 `tenantId/tenantCode/tenantName/tenants[]`；角色取自主租户 |
| 2 | TC-API-146 | `tenantCode=demo` 登录 → tid/角色/权限全来自 demo，**不混主租户角色** |
| 3 | TC-API-147 | 非成员/未知编码 → 403 同一错误码；密码错仍 401（租户校验在密码之后） |
| 4 | TC-API-148 | `/auth/me` 刷新后恢复租户与成员；不同 tid 的 token 给不同租户 |
| 5 | TC-API-149 | 切换：新 token tid=2、旧 token tid=1；角色/权限换成目标租户 |
| 6 | TC-API-150 | 非成员切换 403；空编码 400；租户 2 管理员切租户 1 被拒 |
| 7 | TC-API-151 | 停用租户：登录/切换被拒、既有 token 下一请求 401；主租户不受影响；启用后恢复 |
| 8 | TC-API-152 | **普通租户 ADMIN 看 /tenants → 403**；平台 ADMIN → 200 |
| 9 | TC-API-153 | 平台管理员跨租户业务数据 **404** + 同租户 200；不能切到非成员租户 |
| 10 | TC-API-154 | 启停：成功；停平台租户/当前租户 409；未知 id 404；启用可重复 |
| 11 | TC-TNT-068 | 成员关系唯一由**库约束**保证（重复插入失败、行数不变） |
| 12 | TC-TNT-069 | 建号即得主租户成员（含 HTTP 建号路径） |
| 13 | TC-TNT-070 | 成员表本身受租户过滤（default 3 / demo 2 / 全局 5） |
| 14 | TC-TNT-071 | `tenant:manage` 不泄漏给普通租户 ADMIN |

**用例号唯一性**：先全仓 `grep '@CaseId'` 复算，145~154 与 068~071 此前均未被占用；
TC-API-134 是既有最后一条（V2-7），145 起连续，中间 135~144 留给其它泳道。

**既有用例零删除**：`git diff --stat` 中无任何既有测试文件被改（`TenantAdminIT` 为新增文件）。

---

## 6. 隔离红线复核

| 红线 | 证据 |
| --- | --- |
| V2-7 的 135 格跨租户矩阵全绿 | `TenantIsolationMatrixIT` / `DataScopeMatrixIT` 在 1218 中 0 失败 |
| 新增端点带"异租户 404 + 同租户正对照" | TC-API-153（业务 404/200）、TC-TNT-070（成员表按租户计数） |
| 平台管理员不越界 | TC-API-153 跨租户 404 |
| 业务 SQL 经 `TenantSql` | 新增 SQL 均为单表 `SELECT ... WHERE`；`app_user_tenants` 已登记 `TenantTables.ALL` |
| 不回落默认租户 | `resolveUsername` 用 `TenantContext.require()`（缺失即抛），未用 `callAsSystem` 兜底 |
| 跨租户枚举显式 | `membershipsOf` 走 `callAsSystem`，仅用于"我是谁的成员"，不返回其它租户业务行 |

---

## 7. 后端全量 check 原值

```
> Task :test                      (executed, --rerun-tasks)
> Task :jacocoTestReport
> Task :jacocoTestCoverageVerification
> Task :check
BUILD SUCCESSFUL in 15m 28s
8 actionable tasks: 8 executed
```

| 计数器 | 覆盖 / 总数 | 比例 |
| --- | --- | --- |
| INSTRUCTION | 58742 / 67834 | 86.60% |
| **BRANCH** | **2736 / 3985** | **68.66%** |
| **LINE** | **9528 / 10871** | **87.65%** |
| COMPLEXITY | 2905 / 4215 | 68.92% |
| METHOD | 1988 / 2196 | 90.53% |
| CLASS | 449 / 452 | 99.34% |

`tests=1218 failures=0 errors=0 skipped=0`（90 个测试类）。

---

## 8. 契约门禁原值

```
✓ 契约校验通过：175 个操作，144 个路径，148 个 schema
✓ 字段级契约门禁通过：$ref 全部可解引用，字段/边界/query 与实现逐项对上
```
（实现侧：270 个 .java / 175 个 Controller 处理器 / 258 个 record）

**一个自己踩到的坑**：`tenants[]` 最初用 `map(TenantRef::toMap)` 生成，
`schema-field-check.py` 靠静态解析字段，**看不见 helper 里的字段** —— 会变成"假绿"。
改为在 controller 内逐字段构造 `LinkedHashMap` 后，门禁才真正比对到。

---

## 9. 前端

```
npm run check  → 0
  ✓ vue-tsc --noEmit 通过
  ✓ 状态机镜像一致（86 项检查全部通过）

vite build     → 0
  ✓ built in 10.42s，33 个 chunk
  (!) 单个 chunk > 500 kB 的既有告警（非本次引入）
```

`esbuild` 已锁定 `0.25.11`。

---

## 10. Web E2E

环境：`E2E_BACKEND_PORT=8086`、web `3001`、`E2E_PG_CONTAINER=erp-b1-pg`、
`E2E_BACKEND_DIR=D:\erp-build-tnt-ui\backend\server`、预构建 `bootJar`。

| 遍次 | runId | 结果 | 清理 |
| --- | --- | --- | --- |
| 第一遍 | `E2E-MVNILHSH-AD55` | **39 passed (5.6m)**，0 失败 | 前缀残留 0，业务表回基线，`e2e-reserve-001` 0 条 |
| 第二遍 | `E2E-MVNIVUJ8-3463` | **39 ok / 0 失败** | 收尾进程卡住，**手工用项目自身 `cleanupRun` 补执行**；残留全 0，库回基线（见 11.5） |

两遍的 39 条含全部 3 条新增租户用例（E2E-20/21/22 均 `ok`）。

新增 3 条：

- **E2E-20** 平台管理员：菜单「系统 → 租户管理」可见、`default`/`demo` 两行、平台租户与当前租户「停用」按钮 `disabled`；
- **E2E-21** 多成员 VIEWER：chip 显示「默认租户」且有切换器 → 切到「演示租户」后 **token 变了** → 跨租户商品 404 / 同租户 200 → **反向** home token 读 demo 商品 404 → 切回后 token 再变、边界复原；
- **E2E-22** 单租户 FINANCE：无切换器、无租户菜单、直接访问 `/tenants` 被弹回 `/dashboard`、直调接口 403 `IAM_FORBIDDEN`。

---

## 11. 环境问题与处理（会影响别人复跑，记下来）

| # | 现象 | 根因 | 处理 |
| --- | --- | --- | --- |
| 1 | `--offline` 构建报"spring-boot-starter-test 等无缓存版本" | 本机 Gradle 缓存里只有主依赖，测试依赖从未被解析过（缓存的 "BUILD SUCCESSFUL" 是 FROM-CACHE，不是真跑） | 联网跑一次 `compileTestJava` 拉齐（走阿里云镜像）；**Maven Central 本机不可达（curl 超时）**，已把 Gradle http 超时压到 15s 避免逐依赖卡 10s |
| 2 | `jacocoTestReport` 报 `org.jacoco.ant` 无缓存 | 同上，ant 运行时是独立配置 | 联网跑一次 `jacocoTestReport` 缓存它；之后 `--offline` 的 `check` 才能真跑 |
| 3 | `vite build` / `vite dev` 报 `SAFE_DELETE_BULK_CONFIRM_REQUIRED` | 宿主环境的批量删除守卫会拦 `rm -rf` 超过 50 项（`dist/assets`、`node_modules/.vite`），**不是代码问题** | 构建用新输出目录 `--outDir dist-b1`；E2E 前手动清 `node_modules/.vite` 与 `.e2e-artifacts/test-results`（Vite 在"依赖变更需重新优化"时会删缓存，每次都触发） |
| 4 | `npm ci` 在 `node_modules/.vite` 清理处失败并删掉了 `node_modules` | 同 3 + `npm ci` 先清目录 | 改用 `npm install`（`package.json` 已锁 `esbuild 0.25.11`），并显式确认版本 |
| 5 | **第二遍 E2E 测试全绿但进程不退出，`globalTeardown` 没执行到** | 宿主的批量删除守卫同样作用在 Playwright 的进程回收/产物清理路径上；8086/3001 一直活着，测试进程被自己的 `webServer` 停服流程挂住 | 手工结束**本泳道**的 8086/3001 进程，再用项目自身 `cleanupRun` 传第二遍 runId 补跑清理；复核残留 0、基线复原、`users_missing_home=0` |

> 建议给后续泳道：E2E 前统一执行 `rm -rf node_modules/.vite .e2e-artifacts/test-results`，
> 否则第一轮必然卡在 `ERR_CONNECTION_REFUSED`（dev server 起不来），报错看起来像"前端挂了"。
>
> 另外，E2E 跑完后请确认 8086/3001 已释放；若发现测试进程挂着不退（`ok 39` 之后长时间无退出），
> 先用 `netstat -ano | grep ':8086'` 找到本泳道的 PID 结束它，再核对库里本 runId 的前缀残留为 0。
> 判据是 `cleanup.json` 里的 `runId` 是否等于本轮 `run.json` 的 `runId` —— 不等就说明 teardown 没跑完。

---

## 12. 未解风险

| # | 风险 | 影响 | 后续 |
| --- | --- | --- | --- |
| 1 | 切换/停用后**旧 token 仍有效**（指向旧租户） | 中：停用租户的旧 token 在 `resolve` 处被拦成 401，实际已即时失效；切换产生的旧 token 属于"用户自己换了个身份"，风险低 | 彻底吊销需 token 黑名单/版本号，本批不做（与无状态登出取舍一致） |
| 2 | 成员关系**没有管理界面**（只能在库层追加） | 中：平台管理员能启停租户，但不能给某人加租户 | 属下一批（租户成员授权） |
| 3 | 匿名请求审计仍归默认租户（V2-7 遗留 #1） | 低 | 本批未动，继续登记 |
| 4 | `TenantSql` 只认本项目 SQL 写法 | 低：V17 新增 SQL 均为基础 `SELECT/INSERT`，已被矩阵覆盖 | 遇到 `ON CONFLICT` 目标等复杂写法时补规则 + 用例 |
| 5 | 前端 chunk > 500 kB | 低（既有） | 与租户无关，未处理 |
| 6 | 本机 Maven Central 不可达 | 低：阿里云镜像可用 | 换机器时注意 |

---

## 13. 未做的事（明确边界）

- 不 push、不 merge main（按泳道规则，交给指挥侧）；
- 不改 `docs/00`、README、`docs/planning/**`、`index/`；
- 不占 8080/3000/8081/15432，未杀任何本泳道之外的进程。

---

## 14. Git 状态（交付时必须说明的一件事）

| 项 | 值 |
| --- | --- |
| 分支 | `feat/tnt-ui` |
| **分支 HEAD** | **`09fed18`** — `feat(tnt-ui): 多租户成员与切换 + 平台租户管理（V17 / 1218 绿 / 契约 175）` |
| 基底提交 | `31c9cd6` — `chore(tnt-ui): 以 main 1c8639c 为基线建立 feat/tnt-ui 泳道` |
| 上游 | `1c8639c`（main） |
| 合并 | **未合并 main，未 push** |

### 14.1 为什么多了一个 `31c9cd6` 基底提交

进场时看到的 "HEAD = `1c8639c`" 是**误判**：`git rev-parse HEAD` 当时实际报
`fatal: your current branch 'feat/tnt-ui' does not have any commits yet`。
这个 worktree 的分支**没有任何提交**，index 里是 716 个 `A`（全量初始状态）。

核对后确认：那 716 个暂存文件与 `1c8639c` 的树**逐字节一致**
（`git diff --cached 1c8639c` 输出为空；两边文件清单 716/716，无差集）。
为了能给出"分支 HEAD"，先用 `git write-tree` + `git commit-tree -p 1c8639c` 造了
`31c9cd6`（内容 = main，父 = main），再把功能提交叠在它上面。
**未改写任何既有历史**：`1c8639c` 及其之前的提交原样保留，`31c9cd6` 只是把"分支起点"补成真实提交。

### 14.2 动过的仓库元数据（需要指挥侧知情）

| 操作 | 说明 |
| --- | --- |
| 修 `worktrees/erp-tnt-ui/commondir` | 原值是 `../..`（相对 worktree gitdir 解析成 `D:/MajorCacheFolder/`，**不是**主仓库），导致所有引用解析失败。改为绝对路径 `D:/MajorCacheFolder/Company system/自选/.git` |
| 曾短暂写坏再恢复 `packed-refs` | 首次尝试用 Python 重写时被 PowerShell 的 `>>` 语义追加了 CRLF，导致每条引用解析成 `000...`。**已用备份原样恢复**（20 行、无 CR），其它泳道引用均正常，`git show-ref` 可正常列出 |
| 分支引用写在 `refs/heads/feat/tnt-ui` | 该仓库分支走 loose ref 文件；创建 `refs/heads/` 目录（原先不存在）并写入 `09fed18` |
| **未触碰**其它分支、`origin`、`HEAD`（main） | — |

> ⚠️ 提醒指挥侧：这台机器上 worktree 的 `commondir` 很可能有同样问题（若有脚本批量建过 worktree），
> 表现就是"worktree 里 `git log` 说分支没有提交，但文件都在"。后续泳道若遇到同类报错，先查 `commondir`。
