# frontend/miniapp · 移动端（uni-app）

> M4 第一批：**登录 → 工作台（简版概览）→ 库存查询（余额 + 流水分页）**。
> M4 第二批：**出货单（列表/详情/出货）→ 进货单（列表/详情/入库）→ 商品/SKU 只读** + **移动端 E2E**。
> 一套 Vue3 + TypeScript + Vite 代码，可构建 **H5 / 微信小程序 / Android App 资源包**。
> 决策与取舍见 [ADR-0004](../../docs/07-决策记录ADR/ADR-0004-移动端用uni-app.md)；
> 执行计划、页面优先级与验收记录见 [`.plan/M4-计划书.md`](../../.plan/M4-计划书.md)。

---

## 1. 当前范围（两批已交付）

| 页面 | 能力 | 状态 |
|---|---|---|
| 登录 | `/auth/login`；错误密码内联提示；token/权限持久化 | ✅ |
| 工作台 | 库存行数、有库存 SKU、实存/预占/可用合计、账实一致性、最近 5 条流水、单据/商品快捷入口 | ✅ |
| 库存查询 | 余额（仓库/批次/仅可用筛选）+ 流水（分页加载、单据号反查、单位成本分→元） | ✅ |
| 出货单 | 列表（状态筛选 + 分页）/ 详情（明细、折扣、已出/未出）/ **确认出货**（单动作 ship） | ✅ |
| 进货单 | 列表（状态筛选 + 分页）/ 详情（明细、已入/未入）/ **确认入库**（单动作 receive，可带统一批次/效期） | ✅ |
| 商品 / SKU | 商品 keyword 搜索 + 分页；商品详情带 SKU 列表；SKU 本地过滤（条码/款号/颜色/尺码）+ SKU 详情 | ✅ 只读 |

**明确不做**（移动端刻意裁剪，复杂操作留在 Web）：
建单、多行改量、折扣录入、提交/审核/驳回/作废、退货、结算、主数据 CRUD、SKU 矩阵生成、
报表下钻与导出、扫码、离线缓存。

**前端不实现业务规则**：库存够不够、能不能出货、金额怎么算全部由后端决定；
前端只做展示、筛选与触发。唯一允许的本地计算是显示换算（分→元、日期格式化）。
单据按钮的显隐是**后端状态机的镜像**（`src/utils/documentStatus.ts`，与 Web 同名文件同源），
不按数量、日期或角色自行推断。

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
app/uniapp/                      （原 frontend/miniapp，2026-10-26 迁入）
├── index.html                 # H5 入口
├── vite.config.ts             # uni() + 3002 + /api → 8082（端口与目标可用环境变量覆盖）
├── playwright.config.ts       # 移动端 E2E：3005 + 8085 + 自有 PG 15441
├── tsconfig.json
├── .env.example               # VITE_API_BASE_URL 示例（小程序/Android 必填绝对地址）
├── .env.production.local.example  # 真机联调（内网 http）/ 发布（备案 https）两种填法的模板
├── scripts/                   # 出包工具（只用 Node 内置模块，见 §4.6）
│   ├── preflight.mjs          # 出包前置检查：✖ 会拦住 build:mp-weixin / build:app
│   ├── set-appid.mjs          # 写 appid / DCloud appid / 包名，保留 JSONC 注释与键序
│   ├── make-icons.mjs         # 主图 → 各尺寸图标（没主图直接报错，不凑数）
│   └── jsonc.mjs              # 上面三个共用的 JSONC 定点读写
├── e2e/                       # 移动端 E2E（Playwright 打 H5）
│   ├── start-db.mjs           # 起/复用自有 PostgreSQL 容器（pree2e 钩子）
│   ├── start-backend.mjs      # 经 ASCII 联接构建并启动后端 8085
│   ├── global-setup.ts        # 建 admin、清理上一轮残留、记录业务表基线
│   ├── seed.setup.ts          # 真实接口造数 + 写 storageState
│   ├── global-teardown.ts     # 按前缀清理 + 两条硬校验（残留 0 / 回到基线）
│   ├── 01-login … 06-page-states.spec.ts
│   └── helpers/               # api / db / seed / paths / shot
└── src/
    ├── main.ts / App.vue      # createSSRApp；启动时装配路由守卫 + 全局样式
    ├── pages.json             # 页面注册 + 5 个文字 tabBar + 下拉刷新
    ├── manifest.json          # JSONC：appid / 包名 / 权限 / 版本 —— 出包三道门都在这（带注释说明占位值）
    ├── uni-app-icons/         # 应用图标：source/master.png（主图，待提供）+ 产出的各尺寸
    ├── config.ts              # API base、H5 判断、非 H5 相对地址的就绪校验
    ├── api/client.ts          # uni.request 封装 + 全部接口与类型
    ├── stores/session.ts      # token / 角色 / 权限持久化（uni storage）
    ├── composables/useDictionary.ts  # sku/warehouse/supplier/customer/product → 名称
    ├── utils/                 # 路由守卫、分→元、日期/类型文案、错误收敛、单据状态镜像
    └── pages/                 # 21 页（含 PDA 三页：shipments/detail 扫码拣货、purchase/scan-receive、stocktakes/scan）
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
- 后端跑在 **8082**（第一批泳道约定；8080/3000 是用户进程，8081/3001 是 Web E2E 保留）；
- PostgreSQL 跑在 **15434**（自有容器 `erp-m4-pg`；共享开发库是 15432，本泳道不动）。
- 第二批泳道（M4b）端口整体挪到 **8085 / 3005 / 15441**（容器 `erp-m4b-pg`、联接 `D:\erp-build-m4b`），
  与第一批并行时互不占用；H5 的端口与代理目标已改成环境变量：
  `MINIAPP_DEV_PORT`、`MINIAPP_API_TARGET`（默认仍是 3002 → 8082）。

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
npm run build:h5          # → dist/build/h5（第二批实测 427K）
npm run build:mp-weixin   # → dist/build/mp-weixin（第二批实测 309K）
npm run build:app         # → dist/build/app（App 资源包，第二批实测 1005K；不是 APK）
```

### 4.5 移动端 E2E（第二批新增）

一条命令跑完「起库 → 起后端 8085 → 起 H5 3005 → 造数 → 9 条用例 → 按前缀清理」：

```bash
cd D:\erp-build-m4b\frontend\miniapp
npm install --registry=https://registry.npmmirror.com
npm run e2e                 # pree2e 钩子会先起 erp-m4b-pg 容器
npm run e2e:db:stop         # 收尾：删掉自有容器与数据卷
```

| 环境变量 | 作用 |
|---|---|
| `E2E_SKIP_BUILD=1` | 跳过 `gradlew bootJar`（已构建过时加速第二轮） |
| `E2E_REUSE_SERVERS=1` | 复用已在跑的 8085/3005（人工联调时用；先确认后端是本 worktree 的新代码） |
| `E2E_KEEP_DATA=1` | 跑完不清理，便于人工点；下一轮开始时 globalSetup 会先清掉上一轮 |
| `E2E_CHANNEL=chromium` | 不用系统 Edge，改 Playwright 自带浏览器 |

纪律（与 Web E2E 同源）：
- 只用自有 PG 15441 与 `E2B-<runId>` 唯一前缀造数，**不碰共享库 15432 与 `e2e-reserve-001`**；
- teardown 两条硬校验：前缀残留必须为 0、业务表计数必须回到基线，否则整次运行判红；
- 造数全部走真实接口（含 `X-Request-Id`），不手改库；admin 账号的哈希由
  `scripts/bcrypt-hash.ps1`（后端同一个 `BCryptPasswordEncoder(10)`）现生成；
- 断言写不变量：流水分页 25 个 `data-entry-id` 不重复、余额三量只做展示断言、
  出货/入库后状态必须变成后端返回的 `COMPLETED`。

---

### 4.6 出包前置检查与三个出包脚本（2026-10-26 新增）

`build:mp-weixin` / `build:app` 早就通过了，**卡的是出包，不是构建**。所以把"到设备上一定坏"的
配置项前移成一条命令的检查：

| 脚本 | 干什么 | 常用命令 |
|---|---|---|
| `scripts/preflight.mjs` | 读完 manifest + `.env*` 后，按平台列出 ✖阻塞 / ⚠提醒 / ℹ信息，每条给"缺什么 + 在哪改 + 示例" | `npm run preflight`（三端全查）、`node scripts/preflight.mjs app --verbose` |
| `scripts/set-appid.mjs` | 一条命令写正式 appid，**保留 manifest 里的注释与键顺序**（JSONC 定点替换，不整文件重写） | `node scripts/set-appid.mjs wx1234567890abcdef`、`--show`、`--target dcloud\|packagename` |
| `scripts/make-icons.mjs` | 有主图 → 产出安卓/iOS 各尺寸并可写回 `distribute.icons`；**没主图直接报错**，不生成纯色方块凑数 | `node scripts/make-icons.mjs --master 路径.png --write-manifest` |

**preflight 默认是拦构建的**（`prebuild:mp-weixin` / `prebuild:app` 会先跑它，有 ✖ 就 exit 1，
`npm run build:*` 直接失败）。要强行出包（内网真机联调 / CI 演示）用逃生口，检查照样全跑、照样打印：

```bash
npm run build:mp-weixin:force       # = preflight --warn-only --allow-http && uni build
PREFLIGHT_MODE=warn npm run build:app   # POSIX shell 等价写法
ERP_SKIP_PREFLIGHT=1 npm run build:app  # 完全跳过（不建议）
```

退出码：`0` 无阻塞（`node scripts/preflight.mjs h5` 现在就是 0）｜`1` 有 ✖｜`2` 参数或 manifest 读不了。
只用 Node 内置模块（`node_modules` 是指向主构建区的 junction，装不了 jsonc-parser/commander）。

---

## 5. 微信开发者工具导入步骤（S8）

### 5.1 先配置接口地址

微信小程序**没有 Vite 代理**，相对路径 `/api/v1` 不可用。构建前在项目根建
`.env.production.local`（该文件不入库；模板已备好）：

```bash
cp .env.production.local.example .env.production.local   # 然后按下面的三种场景改一行
```

```dotenv
# 开发者工具模拟器：可以用 127.0.0.1（与后端在同一台电脑）
# VITE_API_BASE_URL=http://127.0.0.1:8082/api/v1

# 真机预览：必须用本机局域网 IP（手机连不到电脑的 127.0.0.1）
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
   拿到真实 AppID 后**别手改文件**，一条命令写进去（保留注释与键序）：
   `node scripts/set-appid.mjs <后台复制的 AppID>`（校验格式，`--show` 看当前三个标识）；
4. 详情 → 本地设置 → 勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」
   （**仅本地联调**；发布前必须取消并配置正式域名）；
5. 编译后应看到：登录页 → 登录 → 工作台 → 库存余额 → 库存流水（触底/按钮加载第 2 页）。

### 5.3 发布前检查

能机器查的都交给 preflight（**✖ 清零**才算过）：`node scripts/preflight.mjs mp-weixin`
—— appid 是否还是 `touristappid`、格式是否合法、后端地址是否绝对/是否 HTTPS、
`urlCheck` 是否还关着，都会逐条报出来并给改法。

preflight 查不了、只能在**微信平台侧**做的（本地永远查不出真值）：

- 小程序后台「开发管理 → 服务器域名」配 **request 合法域名**（HTTPS、已备案、不能带端口/路径）；
- 服务器域名完成 **ICP 备案**（M6）—— 这是本项目唯一"做得再快也绕不过去"的行政周期；
- 《用户隐私保护指引》里申报扫码相关能力：3 个页面用 `uni.scanCode`（发货拣货 / 扫码收货 / 库存盘点），
  未申报时线上会提示接口未授权；
- 小程序本身也要备案（2023 起微信平台要求）；
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

配置项现状（2026-10-26 补齐，`node scripts/preflight.mjs app --verbose` 可逐条核对）：

| 项 | 现状 | 还差什么 |
|---|---|---|
| `app-plus.distribute.android.packagename` | ✅ 已填 `com.yunshang.erp`（**占位值**，注释里写明了） | 按实际主体定稿：`node scripts/set-appid.mjs com.<主体>.erp --target packagename --force`；上架后改不了 |
| `versionName` / `versionCode` | ✅ `0.1.0` / `100`（整数递增） | 每次发布递增 versionCode |
| `app-plus.distribute.icons` | ⚠ 未配置 —— **能出包**，HBuilderX 会拿它的默认图标顶上，只是图标不是我们的 | 一张 ≥1024×1024 方形主图 → `node scripts/make-icons.mjs --write-manifest` |
| 启动图 splashscreen | ⚠ 只有默认配置（`alwaysShowBeforeRender` 等），没有自定义启动图 | 设计给图后手工填 `app-plus.splashscreen`（脚本暂不代劳） |
| DCloud AppID（顶层 `appid`） | ⚠ `__UNI__M4ERP0` 格式合法，但**归属哪个 DCloud 账号本地查不了** | HBuilderX 登录拥有该 appid 的账号，或在 HBuilderX 里重新生成 |
| 签名证书 `.jks` | ❌ 外部资源 | 自建 keystore（口令另存，**禁止入库**，`.gitignore` 已排除 `*.jks`/`*.keystore`） |

步骤：`npm run build:app:force` 出资源包 → HBuilderX 导入项目 → 发行 → 原生 App-云打包 →
选 Android → 填包名/证书/别名 → 云端产出 APK → 真机装一次验登录 + 一个写操作 + 一次扫码。

### 6.3 路线 B：离线 SDK + Android Studio（可控，工作量大）

前置：JDK 17、Android SDK（API 33+）、Gradle、签名 keystore。
将 `dist/build/app` 资源放入离线 SDK 工程，配置 `local.properties`、`applicationId`、
签名与 `INTERNET` 权限，用 Android Studio 出 APK/AAB。

### 6.4 网络与合规

- 开发期允许明文 HTTP（仅调试）；**正式包必须 HTTPS**（微信小程序的合法域名更是硬性要求）；
- `manifest.json → app-plus.distribute.android.permissions` 现有三条：`INTERNET`、
  `ACCESS_NETWORK_STATE`、**`CAMERA`**。CAMERA 是读代码定的，不是照页面名字猜的：
  `pages/shipments/detail.vue`、`pages/purchase/scan-receive.vue`、`pages/stocktakes/scan.vue`
  三处都走 `uni.scanCode({ scanType: ['barCode','qrCode'] })`，页面上的条码输入框只是
  **扫码调不起来时的降级手录路径**（H5 与开发者工具里必走降级）；真机要摄像头权限才有点「扫码」的意义。
  preflight 会自己 grep 页面源码，把这条权限与 `uni.scanCode` 的用量对账；
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

## 8. 第一批实测证据（2026-10-23）

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

### 8.5 M4 第二批实测证据（2026-10-23，泳道 8085 / 3005 / 15441）

| 项 | 命令 / 结果 |
|---|---|
| 类型检查 | `npm run type-check` → 0 错误（`tsconfig.include` 已扩到 `e2e/**/*.ts` + `playwright.config.ts`；Playwright 自身不做类型检查，不收进来等于裸奔） |
| 三端构建 | `build:h5` 427K、`build:mp-weixin` 309K、`build:app` 1005K（5 个 tabBar 合法） |
| E2E 连跑 | 修掉清理门禁空转（坑 24）之后连跑 4 遍 `npm run e2e`（含 pree2e 起库 + Gradle bootJar）：**9 passed (1.6m) → 9 passed (1.5m) → 9 passed (1.3m，`E2E_KEEP_DATA=1`) → 9 passed (1.5m)** |
| 造数规模 | 1 商品 / 25 SKU / 2 仓 / 3 进货单（25+2+2 行）/ 1 出货单 → 余额 29 行、流水 31 条、预占 2 段 |
| 出货动作 | 详情「已审核」→ 确认出货 → 后端返回「已完成」，按钮消失、提示改为不可出货 |
| 入库动作 | 详情「已审核」→ 确认入库（10 件）→ 「已完成」，已入 10、未入 0 |
| 幂等键 | `ship` / `receive` 的 `X-Request-Id` 由 `api/client.ts` 注入，页面零手写 |
| 三态 | 404 单据显示后端消息「出货单不存在」+ 重试；商品搜索与批次筛选无结果给下一步提示 |
| 清理 | 每轮 teardown 与下一轮 setup 都要求 8 项前缀残留计数为 0、业务表计数回到基线（`cleanup.json` 留档删除行数） |
| 门禁自检 | `E2E_KEEP_DATA=1` 留一轮数据后跑同一条残留查询 → `skus=25 / ledger=31 / balances=29`（8 项全非零）：这条红是能亮的，不是摆设 |

截图（通过态正片）：`frontend/miniapp/.e2e-artifacts/screenshots/`（已 gitignore）：
`login-inline-error`、`dashboard`、`inventory-balance-filtered`、`ledger-page1/2`、
`sales-list-filtered`、`sales-detail-approved/completed`、`purchase-detail-approved/completed`、
`product-detail`、`sku-detail`、`sku-filtered`、`sales-detail-error`、`products-empty`。

---

## 9. 已知坑（两批实测）

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
10. **H5 的 placeholder 不是 input 属性**（第二批实测）：`<uni-input>` 把提示语渲染成**同级占位元素**，
    `page.getByPlaceholder(...)` 一律找不到 → E2E 用 `.field input` / `.filter-input input` / `.search-input input` 定位。
11. **「加载更多」按钮不能在用 `v-if` 换掉的节点上**（第二批实测）：请求飞行中把按钮换成
    "加载中…" 会让节点 detach，Playwright 的点击会反复重试并撞在重新挂载的节点上直到超时 →
    保持同一节点，只切文案 + `:disabled`（`inventory/sales/purchase/products` 四处一致）。
12. **`uni.showModal` 在 H5 是 DOM 弹层**：不是原生 dialog，`page.on('dialog')` 收不到；
    确认按钮是 `.uni-modal__btn_primary`，可以直接点。
13. **本包不是 ESM**：`package.json` 没有 `"type": "module"`（uni-app 构建按 CJS 解析），
    Playwright 会把 `e2e/**/*.ts` 与 `playwright.config.ts` 转成 CommonJS →
    只能用 `__dirname`，写 `import.meta.url` 会直接 `SyntaxError`（`.mjs` 脚本不受影响）。
14. **单据状态按钮是镜像不是判断**（第二批）：按钮显隐只看 `utils/documentStatus.ts` 的
    「状态 → 动作」表 + 权限码，**不看 openQty / 余额 / 日期**；镜像漂移的表现是用户点了收到 409，
    所以这张表要与 Web 的同名文件、后端 `DocumentStatus` 一起改。
15. **清理门禁会空转**（第二批自查）：`cleanupRun` 一度把残留计数的 **SQL 文本**当成 psql 输出去解析，
    `remaining` 恒为 `{}`，"前缀残留必须为 0"于是永远不会红 —— 测试全绿但库里一堆垃圾。
    现在既执行再解析，还要求 8 项计数**一项不缺**（缺项报错，而不是当作干净）；
    setup 清上一轮残留后同样校验归零，否则基线一开始就是脏的。

---

## 10. 与仓库其他部分的关系

- 后端与契约：**两批都零改动**；直接消费 `frontend/web/src/api/client.ts` 已在用的接口。
- Web 端：**只读参考**，未改一行；移动端裁剪原则见 ADR-0004，状态镜像参照
  `frontend/web/src/utils/documentStatus.ts`。
- 端口纪律：第一批泳道 8082 / 3002 / 15434；第二批泳道与 E2E 8085 / 3005 / 15441；
  都不占用户 8080/3000 与 Web E2E 的 8081/3001，也不碰共享库 15432。
- 下一批（S8 微信联调 / S9 Android 出包 / P2 退货·结算·报表）的开场提示词在
  [`.plan/M4-计划书.md`](../../.plan/M4-计划书.md) §8 与 §10。
