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
   250 操作）里的端点。任何一端都不许私自约定字段 —— 本项目已经因为"前端传了枚举里没有的
   值"和"实现收参数、契约没声明"各炸过一次。
4. 由此推论：**核心业务没稳定之前不开新端**。这条已经遵守了（V3/V4 八条泳道先做完）。

## 二、按 任务.md 的必做/可选清单，实测现状

| 端 | 必做? | 分值 | 实测状态 |
|---|---|---|---|
| 服务端 | 必做 | 25% 内 | ✅ 44+ 控制器，Flyway V23，契约 250 操作 |
| Web 端 | 必做 | 25% 内 | ✅ 9 组 / 62 条路由，演示数据已灌 |
| 微信小程序 | **必做** | 25% 内 | ⚠️ **配置齐了，卡在外部资源**：21 页，`build:mp-weixin` 通过；`manifest.json` 里 `appid` 仍是 `touristappid`（游客号）→ 传不上去、真机预览开不了。`npm run preflight` 会把这条连同"后端地址没配 HTTPS"一起报出来 |
| 安卓 APP | **必做** | 25% 内 | ⚠️ **资源包能出、APK 差账号与证书**：`build:app` 通过；`packagename`（占位值，待按主体定稿）、`versionCode`、CAMERA 权限已补齐；图标缺主图（缺了会用 HBuilderX 默认图标，不阻塞）；云打包要 DCloud 账号 + `.jks` 证书 |
| PC 端 | 可选 +5% | +5% | ✅ **壳能跑，业务界面零重复建设**：`app/desktop/` Electron 33 本地静态服务 + `/api` 反代，`npm run smoke` 12/12 通过，其中"壳→真实 8080 后端"一项实测拿到后端自己的 401 `IAM_BAD_CREDENTIALS`（说明转发生效）；窗口/托盘/`printToPDF` 实机自测通过。**未验**：物理打印机套打、深链系统注册、electron-builder 安装包、断网数据缓存 |
| 自然语言操作 | 可选 +5% | +5% | ⚠️ 后端 `POST /api/v1/nl/command` 实测可跑，工作台已挂上入口卡片（V4-1 合并）；**只支持查询与建草稿两类意图**，不要当"什么都能干"讲 |
| MCP 让 Agent 操作 | 加分 | 加分 | ✅ `packages/mcp-server`（`erp-inventory-mcp`，6 工具） |
| 单机版 | 必做 | 10% | ⚠️ `infra/standalone/` 15 文件齐全，**从未真实离线环境验证过** |
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
- [ ] 注册/取得正式小程序 `appid`，替换 `touristappid` → 拿到就跑 `node scripts/set-appid.mjs <AppID>`
      （脚本已用格式合法的假号验过：写入 → `build:mp-weixin` 通过 → 产物 `project.config.json` 带上新号）
- [ ] HBuilderX 云打包出 APK（要 DCloud 账号 + `.jks` 签名证书，都是外部资源）

### P2 · 联调与验收（依赖 P1，不依赖公网）

- [ ] 起 `build:h5` 产物对**合并后的主库**跑一遍 18 页，确认没有"接口 400/500"类回归
      （合并后一直没验过小程序侧；web 侧普查过 62 条路由，小程序一条都没有）
- [ ] 微信开发者工具导入 `dist/build/mp-weixin`，勾掉域名校验跑通主链路：
      登录 → 工作台 → 库存查询 → 收货/发货 → 审批
- [ ] 安卓用 HBuilderX 云打包出 APK，真机装一次，验登录 + 一个写操作

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
