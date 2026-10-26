# PC 端（Electron）· 壳，不是第二套业务

> 2026-10-26 开工。本端已完成可跑的骨架，**但只有 HTTP 层与主进程自测是被实测过的**，
> 其余按"已实现 / 未验"逐条标在下面，别把未验的当已交付。

## 一、硬约束（先守住这个，本端才有存在价值）

1. **不重写业务界面。** Electron 只加载 `frontend/web` 的构建产物，渲染进程复用 Web 端全部页面。
   判据：**一段代码若在描述"某个业务页面长什么样"，它就该在 `frontend/web`，不该在 `app/desktop`。**
   本端不复制前端源码、不加任何业务组件、不改一个像素。
2. **只做只有桌面能做的事**：托盘常驻、本地打印/存 PDF、深链、离线可开壳、诊断入口。
3. **API 层不另写一份。** 端点仍然只有 `packages/api-contract/openapi.yaml` 一个来源。
4. 产物目录 `app/desktop/dist/`、`node_modules/`、`.tmp/` 不入库（见本目录 `.gitignore`）。

★ 本端对 `frontend/web` 的改动量是 **0 行**（`git diff --stat main -- frontend/web` 为空，
见 `.plan/V5-2-PC端-报告.md`）。这不是巧合，是下面第二节那个架构决策换来的。

## 二、架构决策：为什么是"本地服务 + 反代"，而不是 `loadFile()`

`frontend/web` 是 Vue SPA：`createWebHistory()`（history 模式）+ 开发时靠 vite 的 `/api` proxy
转发到后端 8080（作者特意写了不用 CORS 的理由，见 `frontend/web/vite.config.ts` 顶部注释）。
打包成桌面后这两个前提都不成立：

| 方案 | 后果 |
|---|---|
| `loadFile('dist/index.html')` | origin 是 `file://`：history 路由的子路径与 F5 会去文件系统里找 `<dist>/orders/list` → 白屏；`fetch('/api/...')` 解析成 `file:///api/...` → 接口全挂 |
| 构建时注入绝对后端地址 | origin 变成 `file://`（null）跨到 8080 → **撞上 CORS**，而后端是刻意不配 CORS 的；要么改后端，要么改 web 端基址注入 —— 两个都要动业务侧代码 |
| **本端采用：主进程内起本地静态服务 + `/api` 反代，窗口加载 `http://127.0.0.1:<固定端口>/`** | 同源成立 → 不需要 CORS；history 回落 index.html → 刷新/深链都正常；`/api` 由本地端口转发到后端 → **web 端与后端都是零改动** |

实现只在 `src/server.js`，只用 Node 内置模块（无 express / http-proxy）。
它的等价性可以这样理解：**把 vite dev server 的 proxy 那一段，在打包产物上重做了一遍。**

### ⚠️ 端口必须固定（默认 8788），不许随机

理由不显然但后果很实际：token、权限快照、当前租户都存在 **localStorage**，而 localStorage
按 **origin** 隔离，origin = scheme + host + **port**。

- 端口随机 → 每次启动 origin 都变 → 浏览器认为是"另一个站点" → **每次都要重新登录、租户选择丢失**，
  现象是"登录状态不持久"的诡异 bug，极难归因。
- 所以端口被占用时 `src/server.js` 直接打印 EADDRINUSE 的处置办法并失败退出，**绝不悄悄换一个端口**。
  要换就自己定一个、然后一直用它（写进配置文件）。

```
[desktop] 端口 8788 已被占用（EADDRINUSE）。
  为什么不让它自动换端口：token/权限快照/当前租户存在 localStorage，而 localStorage 按 origin
  （含端口）隔离；端口一换等于换了站点，表现为"每次启动都要重新登录"的诡异 bug。
  1) 关掉占用 8788 的进程：netstat -ano | findstr :8788
  2) 固定改用别的端口并一直用它：DESKTOP_PORT=8789 npm start
```

多泳道并行时每人各用一个固定端口（`DESKTOP_PORT=8789/8790/...`），互不干扰且各自登录态稳定。

## 三、怎么装、怎么构建、怎么跑

```bash
cd app/desktop

# 1) 装 Electron（devDependency，约 200MB；本目录 node_modules 是**本目录自己的真实目录**，
#    所以这里是全仓库唯一允许 npm install 的地方）
npm install
#    官方源不通就用镜像（本机实测只有镜像能通，Electron 二进制也走镜像）：
#    ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ \
#      npm install --registry=https://registry.npmmirror.com
#    注意：这样生成的 package-lock.json 里 resolved 指向 npmmirror。
#    换到官方源环境请删掉 lock 重装，否则 npm ci 会去连镜像。

# 2) 构建 Web 端产物（本端不另建一套前端，构建的就是 frontend/web 本身）
npm run build:web              # = vue-tsc --noEmit && vite build，与 web 端 CI 同口径
npm run build:web -- --fast    # 只 vite build，跳过类型检查（快很多）
#    前置：frontend/web/node_modules 必须存在——它是主工作区共享的 **junction**，
#    绝对不要在 frontend/web 里 npm install（会破坏别的端）。
#    缺依赖时本脚本会直接停下并告诉你去哪装，不会自作主张装。

# 3) 跑起来
npm start                      # 静态模式：托管 frontend/web/dist，/api 转发到后端
npm run dev                    # 开发模式：透传正在跑的 vite dev server（HMR 可用）
                               # dev server 没跑则自动退回静态模式；两者都没有就响亮报错
npm run serve                  # 不起窗口，只起本地服务（CI/无 GUI 时用，也可直接用浏览器开）
npm run smoke                  # 12 项 HTTP 层自测，见第五节
```

只想验壳不想起 Electron：`npm run serve` 后用浏览器开 `http://127.0.0.1:8788/`，
行为与窗口里一致（同一份代码路径）。

### 验证前必看的本机坑

这台机器的 shell 环境里带着 `ELECTRON_RUN_AS_NODE=1`，此时 `electron.exe` 退化成普通 Node，
`require('electron')` 返回二进制路径字符串而不是主进程 API。`src/main.js` 开头有这道闸并会
明确提示。手动跑二进制时去掉它：

```bash
env -u ELECTRON_RUN_AS_NODE npm start
```

## 四、后端地址怎么配

优先级：**环境变量 > `config/desktop.config.json` > 内置默认值**。
默认后端 `http://127.0.0.1:8080`（与 `frontend/web/vite.config.ts` 的 proxy target 同口径）。

```bash
DESKTOP_API_TARGET=http://192.168.1.20:8080 npm start   # 换后端
DESKTOP_PORT=8789 npm start                             # 换固定端口（此后一直用它）
DESKTOP_WEB_DIST=../../frontend/web/dist npm start      # 产物目录不在默认位置
DESKTOP_DEV_SERVER_URL=http://127.0.0.1:3000 npm start  # 透传 vite dev server
DESKTOP_API_PREFIXES=/api,/files                        # 追加要反代的前缀
DESKTOP_CLOSE_TO_TRAY=0 npm start                       # 关掉"关闭即隐藏"
```

长期偏好写文件：`cp config/desktop.config.example.json config/desktop.config.json` 后改
（该文件已被 gitignore，因人而异不入库）。形状非法（端口越界、`apiPrefixes` 不是数组、
`apiTarget` 不是绝对地址）会在起窗口之前响亮失败。

## 五、桌面增量能力清单与**实测到哪一层**

| 能力 | 代码 | 实测状态 |
|---|---|---|
| 本地静态服务托管 SPA | `src/server.js` | ✅ `npm run smoke` A/B/C/J 通过；Electron 内实测 `loadedURL=http://127.0.0.1:8788/` |
| history 路由子路径回落 | 同上 | ✅ 壳内 `curl /orders/list` → 200 且回 index.html（`loadFile` 方案在此必白屏） |
| `/api` 反代到后端 | 同上 | ✅ 假后端实测 POST body 原样透传、响应头带回；**真实 8080 转发未验**（跑这条泳道时后端没在跑，smoke 第 I 项显式 SKIP） |
| 后端不在时的可归因错误 | 同上 | ✅ 结构化 502 `DESKTOP_UPSTREAM_UNREACHABLE`，壳与页面仍可打开 |
| 固定端口 + 占用响亮失败 | 同上 | ✅ smoke 第 G 项实测第二个壳 EADDRINUSE，且不换端口 |
| 窗口加载 web 产物 | `src/main.js` | ✅ selftest `windowVisible=true`、`title` 取自页面 |
| 托盘常驻 / 关闭窗口不退出 | 同上 | ⚠️ 部分验：selftest `trayAlive=true`（托盘对象确实建成）。**"点 X 后隐藏、点托盘恢复"是交互行为，无人值守环境没点过** |
| 本地打印当前单据 | 同上（菜单/托盘 Ctrl+P） | ⚠️ 链路可验部分已验：`printToPDF` 产出 7928 字节合法 `%PDF-1.4`；`getPrintersAsync()` 返回 `[]` → **本机无打印机，真机套打未验** |
| 另存为 PDF | 同上（Ctrl+Shift+S） | ⚠️ 生成 PDF 已验（同上）；保存对话框需人点，未验 |
| 深链 `erp-desktop://` | 同上 | ❌ 未验。`setAsDefaultProtocolClient` 需要写 HKCU，协议注册要安装包/管理员；`handleDeepLink` 的路径映射逻辑未跑过 |
| 最小 preload 面 | `src/preload.js` | ✅ selftest：`window.desktop` 只有 8 个点名方法；`window.require`/`window.process`/`window.ipcRenderer` 全 false；`getStatus()` 走 IPC 往返成功 |
| 离线可开壳 | 静态托管本身 | ⚠️ 断网时页面能打开（产物在磁盘上，实测过 502 分支即"后端不在仍出页面"）。**完整离线（数据缓存/断网可查）未做**，见下 |
| 打包成安装包（electron-builder/NSIS） | — | ❌ 未做，配置未加 |

### 占位与未做（不当功能交付）

- **离线数据缓存**：本端只有"壳与页面离线可开 + 后端不可达时出可归因 502"。
  把单据/库存读缓存到本地、断网可查可写，需要与 `infra/standalone` 的单机版口径合流
  （那份东西本身"从未真实离线环境验证过"），本泳道不冒充它。**未做。**
- **电子秤/串口等本地硬件**：**未做**，只留了 `src/main.js` 的 IPC 注册位置。
- **开机自启**：**未做**。
- **多窗口**（如单据详情弹独立窗口）：**未做**。
- **安装包与代码签名**：**未做**，当前只能 `npm start` 形态运行。
- `webContents.print()` 的静默套打（`silent:true` + 指定 `deviceName`）已实现但**无打印机可验**。

### 已验部分的复现命令

```bash
cd app/desktop && npm run smoke     # 12/12（第 I 项在后端没跑时是 SKIP，不是通过）

# Electron 主进程自测（打印 JSON 到 stdout，无 GUI 环境也能拿证据；跑完自退）
DESKTOP_WEB_DIST="$PWD/.tmp/e2e-dist" DESKTOP_SELFTEST=1 DESKTOP_SELFTEST_PDF=1 \
  DESKTOP_SELFTEST_QUIT=1 env -u ELECTRON_RUN_AS_NODE npm start
```

selftest 实际输出（本机，产物为 `.tmp/e2e-dist` 夹具）：

```json
{"isDesktop":true,
 "methods":["isDesktop","meta","getStatus","listPrinters","printCurrentPage",
            "saveCurrentPageAsPdf","notify","onShellAction"],
 "meta":{"platform":"win32","arch":"x64","electron":"33.4.11","appVersion":"0.1.0"},
 "statusOk":true,"leakedRequire":false,"leakedProcess":false,"leakedIpcRenderer":false,
 "pdf":{"bytes":7928},"printers":[],"trayAlive":true,"windowVisible":true,
 "loadedURL":"http://127.0.0.1:8788/","title":"ERP"}
```

## 六、目录

```
app/desktop/
├── README.md                        本文
├── package.json                     electron 作 devDependency；build:web/dev/start/serve/smoke
├── .gitignore                       node_modules / dist / .tmp / 本机配置
├── config/desktop.config.example.json  后端地址与端口的配置模板（复制成 desktop.config.json）
├── src/
│   ├── main.js                      主进程：起本地服务 → 建窗口 → 托盘/菜单/打印/深链/IPC
│   ├── server.js                    ★ 本地静态服务 + /api 反代（本端方案的支点）
│   ├── config.js                    环境变量 > 配置文件 > 默认值；端口固定；形状校验
│   ├── preload.js                   只点名暴露桌面增量能力（不开 ipcRenderer）
│   ├── server-cli.js                不起窗口只起服务（无 GUI 验证 / 浏览器调试）
│   └── dev-launcher.js              开发模式：透传 vite dev server，退回静态，都没有就报错
├── scripts/
│   ├── build-web.js                 调 frontend/web 的 vite build（不复制前端源码）
│   └── smoke.js                     12 项 HTTP 层自测
└── .tmp/                            临时夹具与日志，不入库
```

## 七、下一步（要做得像正式交付还差什么）

1. 后端在跑时补验第 I 项：`/api/v1/auth/login` 经壳转发到真实 8080 的 200。
2. 有打印机时验静默套打：`silent:true` + `deviceName`，并把默认设备名进配置。
3. 深链：安装包阶段由 electron-builder 注册协议，本端只保留 `handleDeepLink` 的映射逻辑。
4. 离线：与 `infra/standalone` 单机版口径合并后一起做，别在这端单造一套缓存。
5. `frontend/web` 若将来想用桌面能力，按 `window.desktop?.isDesktop` 渐进增强——
   那是业务侧改动，需要单独排期，本泳道没动它，也刻意没动。
