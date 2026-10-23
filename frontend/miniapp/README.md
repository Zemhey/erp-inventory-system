# frontend/miniapp · 移动端（uni-app）

> M4 第一批：**登录 → 工作台（简版概览）→ 库存查询（余额 + 流水分页）**。
> 一套 Vue3 + TypeScript + Vite 代码，可构建 **H5 / 微信小程序 / Android App 资源包**。
> 决策与取舍见 [ADR-0004](../../docs/07-决策记录ADR/ADR-0004-移动端用uni-app.md)；
> 执行计划、页面优先级与验收记录见 [`.plan/M4-计划书.md`](../../.plan/M4-计划书.md)。

---

## 1. 当前范围（本批已交付）

| 页面 | 能力 | 状态 |
|---|---|---|
| 登录 | `/auth/login`；错误密码内联提示；token/权限持久化 | ✅ |
| 工作台 | 库存行数、有库存 SKU、实存/预占/可用合计、账实一致性、最近 5 条流水 | ✅ |
| 库存查询 | 余额（仓库/批次/仅可用筛选）+ 流水（分页加载、单据号反查、单位成本分→元） | ✅ |

**明确不做**（移动端刻意裁剪，复杂操作留在 Web）：
建单/审核/入库/出货/退货/结算动作、主数据 CRUD、SKU 矩阵生成、报表下钻与导出、扫码、离线缓存。
下一批页面（出货/进货/商品只读）的优先级见计划书 §2。

**前端不实现业务规则**：库存够不够、能不能出货、金额怎么算全部由后端决定；
前端只做展示、筛选与触发。唯一允许的本地计算是显示换算（分→元、日期格式化）。

---

## 2. 技术栈与版本（已锁定）

| 包 | 版本 | 说明 |
|---|---|---|
| `@dcloudio/uni-app` / `uni-h5` / `uni-mp-weixin` / `uni-app-plus` | `3.0.0-5020620260917001` | 所有 `@dcloudio/*` 必须同一版本 |
| `vue` | `3.4.21` | |
| `vite` | `5.2.8`（精确） | uni-app 该版本 peer 要求，**不能**用 Web 端的 vite 6 |
| `typescript` / `vue-tsc` | `5.4.x` / `2.0.x` | `npm run type-check` |

> 本机 `registry.npmjs.org` 与 GitHub 不可达（实测超时），安装必须走镜像：
> ```bash
> npm install --registry=https://registry.npmmirror.com
> ```
> `package-lock.json` 已入库，避免下次解析到不同版本。

---

## 3. 目录

```
frontend/miniapp/
├── index.html                 # H5 入口
├── vite.config.ts             # uni() + 3002 + /api → 8082 代理
├── tsconfig.json
├── .env.example               # VITE_API_BASE_URL 示例（小程序/Android 必填绝对地址）
└── src/
    ├── main.ts / App.vue      # createSSRApp；启动时装配路由守卫 + 全局样式
    ├── pages.json             # 三页注册 + 文字 tabBar + 下拉刷新
    ├── manifest.json          # appid / mp-weixin / app-plus；uniStatistics 已关闭
    ├── config.ts              # API base、H5 判断、非 H5 相对地址的就绪校验
    ├── api/client.ts          # uni.request 封装 + 全部接口与类型
    ├── stores/session.ts      # token / 角色 / 权限持久化（uni storage）
    ├── composables/useDictionary.ts  # skuId/warehouseId → 名称（仅显示映射）
    ├── utils/                 # 路由守卫、分→元、日期/类型文案、错误收敛
    └── pages/
        ├── login/login.vue
        ├── dashboard/dashboard.vue
        └── inventory/inventory.vue
```

### API 客户端三条纪律（与 Web 对齐）

1. **token 注入**：每次请求从 storage 读 `erp.token`，带 `Authorization: Bearer <token>`；
2. **幂等键**：仅 POST/PUT/DELETE 自动生成 `X-Request-Id`（GET 不加）；
3. **错误优先用后端 message**；401 清登录态并回登录页，但**登录页的 401 原样展示**
   （M3-S8 E2E-02 踩过"点登录毫无反馈"）。

金额一律整数分（ADR-0005）；`utils/money.ts` 的 `fenToYuan()` 只在展示层使用。

---

## 4. 本地运行（H5）

### 4.1 前置

- Node.js 20+（本机 v24）；
- 后端跑在 **8082**（本泳道约定；8080/3000 是用户进程，8081/3001 是 E2E 保留）；
- PostgreSQL 跑在 **15434**（自有容器 `erp-m4-pg`；共享开发库是 15432，本泳道不动）。

```bash
# 1) 自有 PostgreSQL（代码库已存在 postgres:16-alpine 镜像）
docker run -d --name erp-m4-pg \
  -e POSTGRES_DB=erp -e POSTGRES_USER=erp -e POSTGRES_PASSWORD=erp \
  -p 15434:5432 postgres:16-alpine

# 2) 后端（首次启动自动跑 Flyway V1~V7；构建必须走 ASCII 联接 D:\erp-build-m4）
cmd //c "cd /d D:\erp-build-m4\backend\server && set DB_URL=jdbc:postgresql://127.0.0.1:15434/erp&& set SERVER_PORT=8082&& gradlew.bat bootRun --no-daemon"

# 3) 移动端依赖与 H5 dev（3002）
cd D:\erp-build-m4\frontend\miniapp
npm install --registry=https://registry.npmmirror.com
npm run dev:h5
```

打开 `http://127.0.0.1:3002`。Vite 把 `/api` 代理到 `http://127.0.0.1:8082`，
因此 H5 不需要后端加 CORS。

### 4.2 开发账号（V3 迁移刻意不预置账号）

如果用的是 **共享 compose 库**（容器 `erp-postgres`，宿主 15432）：

```powershell
.\scripts\seed-dev-user.ps1        # 默认 admin / Admin@12345
```

如果用的是**本泳道自有容器**（`erp-m4-pg`），用同一套 BCrypt 方法与 SQL，只换容器：

```powershell
# 1) 用后端同一个 BCryptPasswordEncoder(10) 生成哈希（脚本只读，不改仓库）
$hash = & 'D:\MajorCacheFolder\Company system\自选\scripts\bcrypt-hash.ps1' -Password 'Admin@12345'

# 2) 把 INSERT 语句写到 seed.sql（把 $hash 替换为实际值；display_name 用中文）
#    INSERT INTO app_users (username,password_hash,display_name,enabled)
#    VALUES ('admin','<hash>','管理员',TRUE) ...;
#    INSERT INTO app_user_roles ... SELECT ... WHERE r.code='ADMIN' ...;

# 3) 用文件方式送进容器（★ 不要让 PowerShell 5.1 管道喂 psql，中文会被重编码成 ???）
docker cp seed.sql erp-m4-pg:/tmp/seed.sql
docker exec erp-m4-pg psql -U erp -d erp -f /tmp/seed.sql
```

> 账号只存在于**本泳道自有库**；迁移脚本里不建任何账号这条规则不变。

### 4.3 造最小业务数据（真实接口，不手改库）

登录拿 token 后按顺序调：

1. `POST /warehouses` × 2（主仓 / 备仓）
2. `POST /products` → `POST /skus/matrix`（本批实测 5 色 × 5 码 = **25 SKU**）
3. `POST /suppliers`、`POST /customers`
4. `POST /purchase-orders` → `/submit` → `/approve` → `/receive`（**25 行**，主仓）
5. 再来一张 5 行的进货单进备仓（用于验证仓库筛选）
6. `POST /sales-orders` → `/submit` → `/approve`（**不发货**，用于制造预占）

本批实测结果：余额 **30 行**、流水 **30 条**、实存 **575**、预占 **6**、可用 **569**、
`consistency-check` 返回 `consistent=true`。

### 4.4 验收命令

```bash
cd D:\erp-build-m4\frontend\miniapp

npm run type-check        # vue-tsc，0 错误
npm run build:h5          # → dist/build/h5（本批实测 312K）
npm run build:mp-weixin   # → dist/build/mp-weixin（本批实测 178K）
npm run build:app         # → dist/build/app（App 资源包，本批实测 921K；不是 APK）
```

---

## 5. 微信开发者工具导入步骤（S8）

### 5.1 先配置接口地址

微信小程序**没有 Vite 代理**，相对路径 `/api/v1` 不可用。构建前在项目根建
`.env.production.local`（该文件不入库）：

```dotenv
# 模拟器联调：用本机局域网 IP，不要用 127.0.0.1（真机连不到电脑回环）
VITE_API_BASE_URL=http://192.168.1.23:8082/api/v1

# 正式环境：必须是 HTTPS + 已备案域名
# VITE_API_BASE_URL=https://erp.example.com/api/v1
```

> 后端要允许局域网访问：Spring Boot 默认绑定 `0.0.0.0`；若用防火墙/路由器，请放行 8082。
> 未配置绝对地址时，`src/config.ts` 会在首次请求前弹出可读提示，不会静默 404。

### 5.2 构建与导入

```bash
npm run build:mp-weixin
# 产物：frontend/miniapp/dist/build/mp-weixin
```

1. 打开**微信开发者工具** → 「+」→ 导入项目；
2. 目录选 `dist/build/mp-weixin`（**不是** `frontend/miniapp` 根目录）；
3. AppID：本地预览可用 `touristappid`（`manifest.json → mp-weixin.appid` 当前值）；
   上传/真机完整能力需换成已注册小程序的真实 AppID；
4. 详情 → 本地设置 → 勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」
   （**仅本地联调**；发布前必须取消并配置正式域名）；
5. 编译后应看到：登录页 → 登录 → 工作台 → 库存余额 → 库存流水（触底/按钮加载第 2 页）。

### 5.3 发布前检查

- `manifest.json → mp-weixin.appid` 换真实 AppID；
- 小程序后台配置 **request 合法域名**（HTTPS、已备案、不能带端口/路径）；
- 服务器域名需完成 **ICP 备案**（M6）；
- 隐私协议/用户信息相关声明按微信平台要求补齐；
- tabBar 目前是纯文字（无图标），如需图标补 81×81 PNG 后放 `src/static/` 并改 `pages.json`。

---

## 6. Android 打包路径与前置条件（S9）

### 6.1 先区分「资源包」与「APK」

```bash
npm run build:app
# 产物：frontend/miniapp/dist/build/app —— 这是 uni-app 的 App 资源包，
# 需要 HBuilderX / 离线 SDK 再打包成 .apk；它不是可直接安装的安装包。
```

### 6.2 路线 A：HBuilderX 云打包（最省事）

前置：
- HBuilderX（本机未安装，真实出包留 S9）；
- DCloud 账号，并在 `manifest.json` 配置 DCloud AppID（当前 `__UNI__M4ERP0` 仅为占位，需在 DCloud 后台申请）；
- Android 包名（如 `com.yunshang.erp`）、版本号 `versionName/versionCode`；
- **签名证书**：`.jks` / `.keystore` 放仓库外，口令单独保管；
  `.gitignore` 已排除 `*.jks`、`*.keystore`，**禁止提交**；
- 应用图标（1024×1024 源图）与启动图。

步骤：HBuilderX 导入项目 → 发行 → 原生 App-云打包 → 选择 Android → 填包名/证书/别名 →
等待云端产出 APK → 安装到模拟器/真机验证三页。

### 6.3 路线 B：离线 SDK + Android Studio（可控，工作量大）

前置：JDK 17、Android SDK（API 33+）、Gradle、签名 keystore。
将 `dist/build/app` 资源放入离线 SDK 工程，配置 `local.properties`、`applicationId`、
签名与 `INTERNET` 权限，用 Android Studio 出 APK/AAB。

### 6.4 网络与合规

- 开发期允许明文 HTTP（仅调试）；**正式包必须 HTTPS**；
- `manifest.json → app-plus.distribute.android.permissions` 已声明 `INTERNET` 与网络状态权限；
- 上架应用商店还需要：隐私政策、ICP 备案、软著/资质（按商店要求）、隐私权限清单；
- 本地调试若用 HTTP，需在打包配置中允许 cleartext（不同 HBuilderX 版本入口不同，按实际版本设置）。

---

## 7. 三端配置矩阵

| 端 | API base | 说明 |
|---|---|---|
| H5 dev（3002） | `/api/v1` | Vite 代理到 8082 |
| H5 生产 | `/api/v1` | 由部署方反代（M6） |
| 微信小程序 | `VITE_API_BASE_URL` 绝对地址 | HTTPS + 域名白名单 + ICP |
| Android | 同上 | 正式包 HTTPS；调试可明文 |

---

## 8. 本批实测证据（2026-10-23）

| 项 | 命令 / 结果 |
|---|---|
| 类型检查 | `npm run type-check` → 0 错误 |
| H5 构建 | `npm run build:h5` → `DONE Build complete`，`dist/build/h5` 312K |
| 小程序构建 | `npm run build:mp-weixin` → `dist/build/mp-weixin` 178K |
| 小程序 env 注入 | 临时 `.env.production.local` 后重新构建，产物 `config.js` 的 `API_BASE_URL` 为设置值；删除后恢复默认 |
| App 资源构建 | `npm run build:app` → `dist/build/app` 921K（资源包，非 APK） |
| H5 真实登录 | 登录页 → 工作台；错误密码内联显示「用户名或密码错误（剩余尝试 4 次）」；401 过期回登录页并清空 storage |
| 工作台 | 30 行 / 25 SKU / 575 实存 / 6 预占 / 569 可用 / 一致性「一致」/ 最近 5 条流水 |
| 库存余额 | 30 行；批次 `M4-B-WH2` 筛选 → 5 行、可用 25；重置 → 30 行 |
| 流水 | 共 30 条，首屏 20 条；「加载更多（20/30）」→ 30 条；两页 id 共 30 个且无重复；单据号 `PO20261023-0002` 反查 → 5 条 |
| 请求头 | 登录 POST 携带 `X-Request-Id`；`/inventory` 携带 `Authorization: Bearer …` |
| 未登录直达 | `#/pages/inventory/inventory` → 被守卫送回登录页 |

截图与结构化结果：`D:\erp-m4-evidence\`（`01-login.png` … `07-guard-redirect.png`、`evidence.json`），
为**仓库外**的本机证据，不随代码入库。

---

## 9. 已知坑（本批实测）

1. **npm 官方源 / GitHub 不可达** → 手工脚手架 + npmmirror；`degit` 拉官方模板会失败。
2. **vite 版本**：Web 端 vite 6，uni-app 该版本 peer 要求 5.2.8；独立 `package.json` 精确锁定。
3. **`<switch @change>` 的类型冲突**：Vue 的 intrinsic 类型把 `switch` 当 SVG 元素，
   事件参数不是 `{detail:{value}}` → 处理函数签名用 `unknown` 再收窄（`inventory.vue`）。
4. **按钮 hover 残留灰色**：uni-app `button` 默认 `hover-class` 会在桌面/模拟鼠标下保留灰底，
   看起来像禁用 → 自定义按钮统一加 `hover-class="none"`。
5. **401 在登录页也跳转** → 登录页只展示内联错误、不跳转；其它页才 `reLaunch`。
6. **小程序无代理** → 必须配 `VITE_API_BASE_URL` 绝对地址；`config.ts` 在非 H5 平台会主动报错。
7. **`switchTab` / `reLaunch` 不能混用**：登录成功去 tab 页用 `switchTab`；登出/过期回非 tab 的登录页用 `reLaunch`。
8. **`uniStatistics`**：模板默认会带 uni 统计；已在 `manifest.json` 设 `"uniStatistics": {"enable": false}`，
   本批实测无外部统计请求、无 `__DC_STAT_UUID`。
9. **名称字典截断**：`/skus`、`/products` 的 `size` 上限 200；超出时页面显示 ID 并在底部提示，
   不假装完整（与 Web 库存页同一条纪律）。

---

## 10. 与仓库其他部分的关系

- 后端与契约：**本批零改动**；直接消费 `frontend/web/src/api/client.ts` 已在用的接口。
- Web 端：**只读参考**，未改一行；移动端裁剪原则见 ADR-0004。
- 端口纪律：本泳道 8082 / 3002 / 15434；不占用户 8080/3000 与 E2E 的 8081/3001。
- 下一批（S8/S9/S10 与 P1 页面）的开场提示词在 [`.plan/M4-计划书.md`](../../.plan/M4-计划书.md) §8。
