# 多端 · 总纲与推进计划

> 写在 2026-10-26。这份是 `app/` 目录的总入口，回答三个问题：哪些端算"必做"、
> 每个端现在真是什么状态、要出包还差哪几道门。
> 所有"已完成"都必须带实测依据，没验过的写"未验"。

## 一、先定原则：端只做壳，业务只有一份

之前担心的是对的 —— **每多一个端，后续每次改进都要 ×N**。所以这个目录的取舍标准是：

1. **业务页面只写一次。** 小程序、安卓、移动 H5 三端共用 `app/uniapp/`（uni-app 一份 Vue
   代码出三个目标）。不为任何一端单独重写业务页。
2. **PC 端不重写 UI。** `app/desktop/` 走 Electron **包装 `frontend/web` 的构建产物**，
   只加窗口、托盘、离线与本地打印这些"只有桌面能做的事"。业务界面改动自动带到 PC 端。
3. **接口只有主库一个来源。** 所有端调的都是 `packages/api-contract/openapi.yaml`（当前
   260 操作）里的端点。任何一端都不许私自约定字段 —— 本项目已经因为"前端传了枚举里没有的
   值"和"实现收参数、契约没声明"各炸过一次。
4. 由此推论：**核心业务没稳定之前不开新端**。这条已经遵守了（V3/V4 八条泳道先做完）。

## 二、按 任务.md 的必做/可选清单，实测现状

> 2026-10-28 复核。上一轮系统改进（自然语言全链路、深色主题、工作台/登录重做、库存服务端
> 分页、员工档案）只落在后端与 Web 端；三端随后按新口径补齐，下表是补齐后重测的结果。

| 端 | 必做? | 分值 | 实测状态 |
|---|---|---|---|
| 服务端 | 必做 | 25% 内 | ✅ 44+ 控制器，Flyway V29，契约 260 操作 |
| Web 端 | 必做 | 25% 内 | ✅ 10 组路由（新增「自然语言」组），浅色/深色双主题，演示数据已灌 |
| 微信小程序 | **必做** | 25% 内 | ✅ **AppID 已换成正式号，构建通过**（24 页）。2026-10-29 修掉一个只在微信里才暴露的缺陷：主题变量原本写成 `page,:root` 合并选择器，WXSS 不认识 `:root`、整条规则被丢弃 → **小程序整页白屏**；改成条件编译（H5 出 `:root`、小程序出 `page`）。`preflight mp-weixin` 阻塞项 0。剩余都是后台动作：上传头像、配 request 合法域名 `https://xuliu.zemworld.cn`、上传并选为体验版（正式发布才需要小程序备案） |
| 安卓 APP | **必做** | 25% 内 | ✅ **界面已打进安装包（0.3.0）**：`app/yunshang-erp-0.3.0.apk`（333 KB，`apksigner verify` 确认自签 release 证书；包名 `com.yunshang.erp.demo`、`versionCode 3`、INTERNET+CAMERA、5 个密度图标）。移动端界面 68 个文件内置在 `assets/www`，冷启动不查 DNS、不依赖服务器；`/api/v1` 仍打真实后端，文档与接口同源所以不需要 CORS，也不需要给后端加任何头。**0.2.0 的问题已定位并修掉**：默认地址 `m.zemworld.cn` 还没解析 → 回落加载了桌面网页版，加上系统 ActionBar 那条深蓝横带，看起来就是"白屏 + 一块蓝的挡着"；现在没有 ActionBar，状态栏取 `theme.json` 的 navBg 与界面顶栏同色。**仍未真机验证**（本机既无设备也无模拟器），但拦截规则已用 Chromium 按同一套判定跑通：离线渲染登录页 + 真后端登录进工作台 |
| PC 端 | 可选 +5% | +5% | ✅ **便携包 0.2.2，已作为公开下载物**：`app/desktop/` Electron 33 本地静态服务 + `/api`·`/actuator`·`/readyz` 反代，`npm run smoke` 12/12。**0.2.2 是安装包级实测**：直接跑 `win-unpacked` 里的 exe，壳内 `/readyz` 返回 `{"status":"UP"}`、`/api/v1/auth/login` 透传云端 401 业务码、`/favicon-32.png` 1325 字节（0.2.1 里那三个图标是 16:31 的坏产物，重出一版才对）。下载：`https://xuliu.zemworld.cn/dl/YunshangERP-0.2.2-portable.exe`（80,722,177 字节，未做代码签名，SmartScreen 会拦一道）。**未验**：物理打印机套打、深链系统注册、断网数据缓存 |
| 自然语言操作 | 可选 +5% | +5% | ✅ **不再只是"查库存 + 建草稿"**：`/nl/plan`（计划 + 复核 + 确认凭据）→ `/nl/pending`（人签字）→ `/nl/command`（执行）→ `/nl/audit`（审计），另有 SQL 沙箱与模型设置；Web 三个页面，移动端有入口，MCP 走同一条链路 |
| MCP 让 Agent 操作 | 加分 | 加分 | ✅ `packages/mcp-server`（`erp-inventory-mcp`，9 工具，stdio + http 双传输） |
| 单机版 | 必做 | 10% | ⚠️ `infra/standalone/` 15 文件齐全，根目录 `启动系统.cmd` 一键起后端与 Web，**仍未在真实离线环境验证过** |
| 云托管 | 必做 | 15% | ⚠️ CI/CD 有 `.github/workflows/{ci,deploy}.yml`；**域名与备案一项未动** |

## 三、关键路径不在代码上，在备案上

任务.md 原文："应立即购买域名并备案。App、小程序都需要备案。"

- 微信小程序的**request 合法域名必须是 HTTPS 且已备案**的域名，在小程序后台白名单里配。
  没有它，`build:mp-weixin` 的产物只能在开发者工具里勾"不校验合法域名"跑，**真机上必然全接口失败**。
- 安卓 App 走同样的后端域名，也要求备案。
- `app/uniapp/` 有 `.env.example`（联调）与 `.env.production.local.example`（出包模板，入库的是模板；
  复制成 `.env.production.local` 才是真值，那个文件被 `.gitignore` 挡住）——
  **还没人填过真值**，也就是"后端地址填哪儿"这件事仍悬着。
  非 H5 端没有代理，构建时必须注入绝对地址（见 `src/config.ts` 与 `vite.config.ts` 的注释）。

备案是**以周计的行政周期，不能并行压缩**。所以它必须排在最前面启动，而不是等代码写完。
这是这份计划里唯一一件"我做得再快也绕不过去"的事。

## 四、推进顺序

### P0 · 先把已有资产捞回来（不做这步，后面全是白做）

`Company system\erp-v29-mobile\` 里有**一套比 main 更新的代码**：main 18 页，那里 **21 页**，
137 个文件不同，且 mtime 更新。那个 worktree 的 git 登记已经被 prune 掉了
（`.git` 指向不存在的 admin 目录，`git` 现在完全不认它，`git worktree remove` 也用不了）。

也就是说这 21 页**只存在于磁盘上，没有任何版本控制兜底**。工作区清理一定会带走它。

- [ ] 逐页 diff，判断那 3 个新页面和差异是要并进来、还是已被后续批次取代
- [ ] 决定后**立即提交到有版本控制的位置**，再谈清理
- [ ] 顺手盘一遍其它 23 个孤儿目录有没有类似的"只存在于磁盘"的东西

### P1 · 出包三道门（不依赖备案，可以并行推进）

2026-10-26 更新：**配置侧能补齐的都补齐了，剩下的全部是外部资源**（appid / 备案 / 证书 / HBuilderX 账号）。
新增 `app/uniapp/scripts/`：`preflight.mjs`（出包前置检查，默认拦构建）、`set-appid.mjs`（一条命令写
正式 appid，保留 JSONC 注释）、`make-icons.mjs`（主图 → 各尺寸图标，没主图直接报错）。详见
`app/uniapp/README.md` §4.6。

- [x] 安卓补 `packagename`（`com.yunshang.erp`，**占位值**，manifest 注释里写明要按实际主体改）
      + `versionName/versionCode` 合法性 + **CAMERA 权限**（读代码确认三页走 `uni.scanCode`，不是手输）
- [x] 建 `.env.production.local.example`：内网调试填局域网 http、发布填备案 https，两种写法都在模板里
- [x] 出包前置检查 `npm run preflight`：把"到设备上一定坏"的事（游客 appid、相对/明文后端地址、
      包名缺失、扫码缺权限）在**构建前**报出来；逃生口 `--warn-only` / `npm run build:*:force`
- [ ] 图标：脚本与目录已就绪，**缺一张 ≥1024×1024 的主图**（设计资产）。没图标不阻塞出包 ——
      HBuilderX 会用它的默认图标，只是图标不是我们的
- [x] 注册/取得正式小程序 `appid`，已用 `node scripts/set-appid.mjs wxea41f80cdf0f0971` 写入
      （只改 value 那一小段字节，注释与键序逐字节不变；产物 `project.config.json` 已带上新号）
- [x] **本地 WebView 演示包**：`app/android-demo/`（加载 3008 的 H5 预览；debug 签名，已被下面的正式包取代）
- [x] **本机 Gradle 出正式签名 APK**：原以为必须 DCloud 云打包，
      实测本机 `D:\AndroidSdk` + 工程自带 `gradlew` 就能出 release 包，签名证书用 keytool 自签即可；
      **备案与软著只影响"上架应用商店"，不影响生成与安装 APK**
- [x] **界面打进安装包（阶段 C，2026-10-29）**：`assets/www` 内置 uni-app 的 H5 产物，
      冷启动不依赖网络与 DNS；HBuilderX 云打包因此不再是必需项

### 安卓包：从在线壳到内置界面（2026-10-29 两轮）

| | 演示包（已删） | 在线壳 0.2.0 | 内置包 0.3.0 |
|---|---|---|---|
| 界面来自 | 电脑 3008 预览 | **服务器**：`m.zemworld.cn`，失败回落 `xuliu.zemworld.cn` | **安装包内** `assets/www`（68 文件） |
| 断网 | 打不开 | 打不开（只剩错误页） | 照样开到登录页，只有接口需要网络 |
| 顶栏 | 系统 ActionBar | 系统 ActionBar（那条深蓝横带）+ 进度条 + 地址徽标 | 无 ActionBar，状态栏=界面导航条同色 |
| 文件 | 18 KB | 119 KB | 333 KB |
| 签名 | Android Debug key | 自签 release 证书 | 同一把 release 证书（`apksigner` 已比对 digest） |
| 图标 | 矢量占位方块 | 与其余各端同一张织纹方块 | 同上 + 品牌色冷启动底图 |
| 构建 | `gradlew assembleDebug` | `gradlew assembleRelease` | `gradlew assembleRelease`（口令走 `keystore.properties`，已 gitignore） |

- 为什么"文件还是这么小"：移动端界面本质是一个约 600 KB 的 Vue 包，壳层只做装载与转发；
  体积从 119 KB 涨到 333 KB 就是"界面进了包"的证据，与原生渲染无关，也不会变成几十 MB。
- 为什么接口不拦截：`shouldInterceptRequest` 拿不到 POST 请求体，所以 `/api` 一律放行给 WebView
  真实发出；文档地址与接口同源（都是站点域名），浏览器不触发 CORS，后端一个响应头都不用加。
- 「后端地址」入口改为长按页面（没有菜单栏了），内网联调仍可填 `http://<电脑IP>:8082`。
- **未验**：真机安装后的顶栏颜色、登录主链路、摄像头扫码（本机既无设备也无模拟器）。
  已验的是拦截规则本身：用 Chromium 按同一条判定跑内置包，离线渲染出登录页，
  再用真账号打真后端，`POST /api/v1/auth/login` 之后工作台/库存/仓库/商品等接口全部返回，无控制台错误。

### P2 · 联调与验收（依赖 P1，不依赖公网）

- [ ] 起 `build:h5` 产物对**合并后的主库**跑一遍 18 页，确认没有"接口 400/500"类回归
      （合并后一直没验过小程序侧；web 侧普查过 62 条路由，小程序一条都没有）
- [ ] 微信开发者工具导入 `dist/build/mp-weixin`，勾掉域名校验跑通主链路：
      登录 → 工作台 → 库存查询 → 收货/发货 → 审批
- [ ] 安装 `app/yunshang-erp-0.3.0.apk` 到安卓真机，验登录 + 一个写操作（本机无 adb 设备，尚未验证）
- [ ] 正式包：HBuilderX 云打包出 APK（要做推送、原生扫码、上架商店时才需要）

### P3 · 备案与 HTTPS（与 P1/P2 并行启动，谁先好谁先用）

- [ ] 域名 + 备案（用户侧动作）
- [ ] 备案下来后：配小程序业务域名白名单 → 去掉"不校验合法域名"再跑一遍 P2

### P4 · PC 端（可选加分，排在必做之后）

- [x] `app/desktop/` 用 Electron 包装 `frontend/web` 的构建产物（`npm run build:web` 出 dist，壳按固定端口托管，`/api` 反代到后端）
- [x] 桌面增量已做：窗口/托盘/打印（`printToPDF` 与 `getPrintersAsync`）、最小 preload 白名单（8 个方法，`window.require` 实测拿不到）
- [ ] 桌面增量未做：断网数据缓存（"离线可查"要和 `infra/standalone` 口径合流，不在这儿冒充）、深链 `erp-desktop://` 的系统注册、electron-builder 安装包
- [x] 明确不做：不重写业务界面、不单独维护一套 API 层（`git diff a4356d9..HEAD -- frontend/web` 为空）

⚠️ 本机装 Electron 走的是 `registry.npmmirror.com` + `ELECTRON_MIRROR`（官方源直连 ETIMEDOUT），
`app/desktop/package-lock.json` 里 70 处 `resolved` 指向镜像；换回官方源请删 lock 重装。
另有三个会咬人的环境坑（`固定端口`、`ELECTRON_RUN_AS_NODE`、`path.join` 归一）记在
`.plan/V5-2-PC端-报告.md` 第五节。

## 五、目录约定

```
app/
├── README.md              本文（端矩阵 + 计划）
├── uniapp/                一份代码出 微信小程序 / 安卓 / 移动H5（原 frontend/miniapp，2026-10-26 迁入）
│   ├── src/               业务页面（唯一一份）
│   ├── src/manifest.json  appid、包名、权限、图标 —— 出包三道门都在这（JSONC，带"这是占位值"的注释）
│   ├── scripts/           出包工具：preflight（前置检查）/ set-appid / make-icons
│   ├── src/uni-app-icons/ 应用图标主图与各尺寸产出
│   ├── e2e/               H5 端联调用例
│   └── dist/build/        构建产物（不入库）
└── desktop/               PC 端（Electron 包装 web 产物）：src/main·preload·server + scripts/{build-web,smoke}
```

`.env.*` 模板放 `uniapp/` 里，不在这个目录上再造一层；构建产物一律进 `dist/`，
不入库（根 `.gitignore` 已覆盖）。
