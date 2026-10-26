# V5-2 · PC 端（Electron）泳道报告

分支 `v5-2-desktop`，基线 `a4356d9`（main HEAD）。工作树 `D:\erp-v5-2`。
交付范围：**只在 `app/desktop/` 新增**，业务侧零改动。

## 一、commit

| 哈希 | 内容 |
|---|---|
| `6771a19` | 骨架：package.json / 本目录 .gitignore / 配置模板 |
| `07a2dce` | 本地静态服务 + `/api` 反代（方案支点）+ smoke 12 项 |
| `e8c3a14` | 窗口 / 托盘 / 打印 / 最小 preload + 主进程自测钩子 |
| `9c417d6` | fix：没构建产物时回 `WEB_DIST_MISSING` + 下一步命令，而不是含糊 404 |
| `d397312` | 文档：README 重写 + 本报告 |

## 二、架构决策与理由

### 2.1 为什么不是 `loadFile()`，也不是"注入绝对后端地址"

`frontend/web` 是 Vue SPA：`createWebHistory()` + 开发期 vite `/api` proxy 转发 8080
（后端刻意不配 CORS，理由写在 `frontend/web/vite.config.ts` 顶部注释）。打包后两条前提都塌：

- `loadFile('…/dist/index.html')` → origin 是 `file://`：
  history 路由子路径与 F5 会去文件系统找 `<dist>/orders/list` → 白屏；
  `fetch('/api/...')` 解析成 `file:///api/...` → 接口全挂。
- 改成"构建时注入绝对后端地址" → `file://`（origin=null）跨到 `http://127.0.0.1:8080`
  → **撞上 CORS**。要嘛后端加 CORS，要嘛 web 端改基址注入 —— 两者都是动业务侧，正是用户担心的返工源。

**采用的方案**：Electron 主进程内起一个**本地静态服务 + `/api` 反向代理**，窗口加载
`http://127.0.0.1:<固定端口>/`。等价于"把 vite dev server 的 proxy 那一段在产物上重做一遍"：

- 同源成立 → 不需要 CORS，后端一行不改；
- history 回落 `index.html` → 刷新/子路径/深链都正常；
- `fetch('/api/...')` 打到本地端口 → 转发到后端；
- **`frontend/web` 与 `backend` 均 0 行改动**（证据见第四节）。

实现只在 `src/server.js`，只用 Node 内置模块，没引 express / http-proxy：桌面端连 electron
都可能是装不上的（本泳道就遇到过网络不通），壳不该再拖一串依赖进来。

### 2.2 ★ 端口必须固定（默认 8788），这是本端最容易做错的一处

token、权限快照、当前租户都在 **localStorage**，localStorage 按 **origin** 隔离，
而 origin = scheme + host + **port**。

端口随机 → 每次启动 origin 变化 → 浏览器认为是"另一个站点" → 用户每次都要重新登录、
租户选择丢失。现象是"登录状态不持久"的诡异 bug，且毫无业务线索可循，归因成本极高。

所以本端选择：**固定端口 + 被占用时响亮失败并告诉你怎么改**，绝不悄悄换端口。
错误文案（`src/server.js`）直接把因果讲清楚，并给两条出路（杀占用进程 / 固定改用别的端口并一直用它）。
多泳道并行时各人各取一个固定端口（`DESKTOP_PORT=8789…`），互不干扰且登录态各自稳定。

### 2.3 preload 只开"点名"的能力面

`src/preload.js` 用 contextBridge 逐个点名暴露 8 个成员，**不暴露 `ipcRenderer`、不开通用
send/invoke 包装**。开了任意 channel 等于给网页（含任何 XSS 注入脚本）开一条通往主进程的入口，
而主进程有文件系统与进程权限 —— 那是自伤式 RCE 面。渲染进程侧 `sandbox:true`、
`contextIsolation:true`、`nodeIntegration:false` 三件套都在。

主进程对渲染来的参数一律白名单化（`sanitizePrintOptions` 只留 silent/deviceName/copies，
PDF 文件名过字符集过滤），并把导航锁在本机同源入口内，新窗口一律拒绝。

## 三、实际验证到哪一层

环境事实（影响验证边界，不粉饰）：
- **Electron 装上了**：官方 registry 与直连均 ETIMEDOUT，改走 `registry.npmmirror.com` +
  `ELECTRON_MIRROR` 成功，`electron@33.4.11`（含 dist 二进制，可执行）。
- **后端 8080 当时没有在跑**（`netstat` 无监听、`curl` 连接失败）。按硬约束我没有去起或重启它。
  所以"壳 → 真实后端"的 200 转发**未验**，smoke 第 I 项显式打 `SKIP` 而不是打勾。
- **本机打印机队列为空**（`getPrintersAsync()` 返回 `[]`）→ 真机套打**未验**。
- GUI 交互（点托盘、点保存对话框）无法无人值守验证 → 用**主进程自测钩子 + HTTP 层证据**代替，
  属间接验证，下面逐条标注。

### 3.1 `npm run smoke` —— 12/12（第 I 项为 SKIP）

```
PASS  A 静态托管 SPA 首页（http 同源入口）  — HTTP 200
PASS  B history 路由子路径回落 index.html  — HTTP 200
PASS  C1 hash 产物长缓存  — public, max-age=31536000, immutable
PASS  C2 index.html no-cache  — no-cache
PASS  D /api 反代到后端且 POST body 透传  — HTTP 200, 上游头 X-Stub=1
PASS  D2 只有 /api/ 前缀被代理（/apiv1 不误伤）  — HTTP 404
PASS  F 路径穿越被挡  — HTTP 404 / HTTP 403
PASS  E 后端不可达回结构化 502  — HTTP 502
PASS  G 端口被占用时 EADDRINUSE 报错而非换端口  — EADDRINUSE
PASS  H 桌面壳自检端点
PASS  I 壳 → 真实后端转发（默认端口 8788）  — SKIP：后端 http://127.0.0.1:8080 未在跑
PASS  J 正斜杠 webDist 下子路径/资源仍正常  — deep=200 asset=200
12/12 通过
```

D 项用的是**假后端**（`127.0.0.1:8791`，只回 `stub-token`），它证明的是代理链路本身
（POST body 原样透传、上游响应头带回、状态码不被改写），**不**等于真实业务接口可用。

### 3.2 Electron 实机启动 + 主进程自测（真实跑起来了，不是"代码写完没运行"）

`DESKTOP_SELFTEST=1` 启动，主进程用 `executeJavaScript` 把渲染进程与壳自身的事实打回 stdout：

```json
{"isDesktop":true,
 "methods":["isDesktop","meta","getStatus","listPrinters","printCurrentPage",
            "saveCurrentPageAsPdf","notify","onShellAction"],
 "meta":{"platform":"win32","arch":"x64","electron":"33.4.11","appVersion":"0.1.0"},
 "statusOk":true,"statusError":null,
 "leakedRequire":false,"leakedProcess":false,"leakedIpcRenderer":false,
 "pdf":{"bytes":7928},"printers":[],"trayAlive":true,"windowVisible":true,
 "loadedURL":"http://127.0.0.1:8788/","title":"ERP"}
```

同一时刻从外部 `curl` 这个由 **Electron 主进程起的** 服务（窗口在跑，端口在监听）：

```
$ curl -w "%{http_code}" http://127.0.0.1:8788/                  → 200
$ curl -w "%{http_code}" http://127.0.0.1:8788/orders/list       → 200   ← history 回落
$ curl -w "%{http_code}" http://127.0.0.1:8788/assets/index-*.js → 200
$ curl            http://127.0.0.1:8788/api/v1/auth/login        → 502 DESKTOP_UPSTREAM_UNREACHABLE
$ curl            http://127.0.0.1:8788/__desktop__/status       → {"desktop":true,"node":"20.18.3",
                                                    "port":8788,"mode":"static",...}
```

要点：
- `loadedURL=http://127.0.0.1:8788/`（**不是 `file://`**）—— 方案支点确实生效；
- 日志里出现 `INFO:CONSOLE(1)] "1", source: http://127.0.0.1:8788/assets/index-BvRaNd0m.js`
  —— 夹具脚本在**渲染进程里执行了**，这是"窗口确实加载了本地服务页面"的直接证据；
- `title":"ERP"` 取自页面 `<title>`；`trayAlive=true`、`windowVisible=true`；
- `/api` 那条 502 是**后端没跑**导致的预期分支，不是壳的缺陷（同时说明断后端时页面照出）。

### 3.3 未验清单（不当已交付）

| 项 | 状态 |
|---|---|
| 壳 → 真实 8080 转发并拿到 200（如 `/api/v1/auth/login`） | **未验**（后端没跑，且约束禁止我起它） |
| 真实业务页面（62 条路由）在桌面里跑通 | **未验** —— 本 worktree 的 `frontend/web/node_modules` 不存在（junction 在主工作区），按硬约束我没有在那里 `npm install`，因此**没有产出真实 SPA 产物**；上面所有静态证据用的是夹具 `​.tmp/e2e-dist` |
| 物理打印机出纸 / 单据套打 / 静默直打 | **未验**（本机无打印机；`printToPDF` 出合法 `%PDF-1.4` 7928 字节已验） |
| 保存对话框、托盘点击恢复窗口等人机交互 | **未验**（对象/回调都实测存在，但没有手去点） |
| 深链 `erp-desktop://` 系统注册与冷启动唤起 | **未验**（要写 HKCU / 安装包；`handleDeepLink` 的映射逻辑未跑过） |
| 完整离线（数据缓存、断网可查可写） | **未做**（见第五节遗留） |
| 安装包 / 代码签名 / electron-builder | **未做** |

## 四、`frontend/web` 是否真的零改动

`git diff --name-only a4356d9 HEAD` 全量输出 14 个文件 = **13 个 `app/desktop/` + 本报告的 `.plan/` 一份**：

```
app/desktop/.gitignore                         |  13 +
app/desktop/README.md                          | 203 +++++++++++++----  （原为占位说明，重写）
app/desktop/config/desktop.config.example.json |  10 +
app/desktop/package-lock.json                  | 871 +++++++++
app/desktop/package.json                       |  17 +
app/desktop/scripts/build-web.js               |  79 +
app/desktop/scripts/smoke.js                   | 190 +
app/desktop/src/config.js                      | 122 +
app/desktop/src/dev-launcher.js                |  65 +
app/desktop/src/main.js                        | 472 +
app/desktop/src/preload.js                     |  63 +
app/desktop/src/server-cli.js                  |  29 +
app/desktop/src/server.js                      | 270 +
.plan/V5-2-PC端-报告.md                        | 205 +   ← 只有这一份在 app/desktop 之外
14 files changed, 2593 insertions(+), 16 deletions(-)
```

对禁改区再点一次名（输出为空即 0 处改动）：

```bash
git diff --stat a4356d9 HEAD -- frontend/web packages backend app/uniapp
# （空）
```

`app/desktop/README.md` 的重写也在本目录内（原文件本就是占位说明）。构建产物、
`node_modules`、`.tmp` 均被 `.gitignore` 挡住，未入库。

## 五、踩到并已修的坑（都有实测输出）

1. **`ELECTRON_RUN_AS_NODE=1` 遗留在本机 shell 环境**：`electron.exe` 退化成普通 Node，
   `require('electron')` 返回路径字符串 → `app` undefined，报错是一句看不懂的
   `Cannot read properties of undefined`。已加启动闸 + 明确提示（`env -u ELECTRON_RUN_AS_NODE npm start`）。
2. **单实例锁没抢到仍继续往下跑**：`app.quit()` 是异步的，`whenReady` 回调照样执行，
   于是起了服务、占了端口、随即退出并拆掉监听 —— 现象是"日志说服务起来了，但端口没人监听"。
   已在回调首行 `if (!gotTheLock) return`。
3. **Windows 下 `path.join` 归一分隔符导致全站 403**：配置传进来的 `webDist` 常是
   `D:/xxx/dist`（正斜杠），`path.join` 把结果转成反斜杠，而穿越防线拿**原始串**比较 →
   除首页外所有子路径/资源误判为越界（403），首页正常 —— 极难猜。已改为两侧都过 `path.resolve`
   再比较，并补了 smoke 第 J 项把这条钉住。
4. **配置装配的形状 bug**：`apiPrefixes` 辅助函数参数错位，返回了字符串 `'apiPrefixes'` →
   `config.apiPrefixes.map is not a function`。已修，并加 `assertShape()`：端口越界、
   `apiPrefixes` 不是数组、`apiTarget` 不是绝对地址，都在起窗口之前响亮失败。
5. **沙箱化 preload 不能 `require` 本地模块**：版本号改由主进程 `additionalArguments` 注入。
6. **产物没构建时网页只回一个含糊的 404**：`index.html` 带扩展名，落进了"像文件就 404"的分支。
   已把"产物整体缺失"提到最前面判断，现在回 500 + `WEB_DIST_MISSING` 与一条可执行提示
   （`npm run build:web` 或 `DESKTOP_WEB_DIST=…`）。

## 六、遗留项（按优先级）

1. 后端在跑时补验 smoke 第 I 项（真 8080 转发 200）；并在有真实 `frontend/web` 产物的环境
   跑一遍 62 条路由 —— 这两件事是"PC 端真能用"的分水岭。
2. 用真实产物而不是夹具重跑：`npm run build:web` 已实现且会先检查 junction 是否存在、
   缺了就停下告诉你去哪装（**不会**擅自 `npm install`）。
3. 深链：交给打包阶段（electron-builder 的 protocols 注册），本端只保留路径映射逻辑。
4. 离线：与 `infra/standalone` 单机版口径合并推进。该目录本身"从未真实离线环境验证过"，
   在这端单造一套本地缓存只会多一处对不上账的地方。
5. 打印：有打印机后验 `silent:true` + `deviceName`，并把默认设备名进配置（针式套打是这端
   最实际的增量价值，值得单独验一次）。
6. `lockfile` 的 `resolved` 全部指向 `registry.npmmirror.com`（本机网络所迫，70 处）。
   官方源环境请删掉 `package-lock.json` 重装，否则 `npm ci` 会去连镜像。
7. `window.desktop` 目前**没有任何业务页面在用**（web 端零改动的必然结果）。将来 web 端若要
   渐进增强，按 `window.desktop?.isDesktop` 判断即可，能力已经在壳里备好了。
8. `app/README.md` 的端矩阵里 PC 端那行仍写着"❌ 尚无任何代码"、P4 三个勾选框未勾 ——
   那个文件在 `app/desktop/` 之外，本泳道的写入边界不含它，**请主控或文档泳道改口径**
   （可直接引用本报告与 `app/desktop/README.md` 的实测表）。
