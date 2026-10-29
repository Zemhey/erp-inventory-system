# 安卓端（WebView · 界面内置 · release 签名）

本机 Gradle 出包的安卓安装包：**移动端界面打进 APK，接口打真实后端**。
不是 HBuilderX 云打包产物，也不需要 DCloud 账号 —— 那套只在要推送/原生扫码/上架商店时才需要。

## 它是什么

- `app/src/main/assets/www/`（68 个文件、约 600 KB）= `app/uniapp` 的 H5 构建产物。
  启动时主文档与静态资源直接由安装包回答，**不查 DNS、不看服务器脸色，断网也能开到登录页**。
- 文档地址用的是站点真实域名（默认移动端站点 `https://m.zemworld.cn`），页面里的接口是相对路径
  `/api/v1/...` → **同源**，浏览器不触发 CORS，后端一个响应头都不用加。
- `/api`、`/readyz`、`/actuator`、`/swagger-ui`、`/v3/api-docs` 一律**不拦截**，交给 WebView 真实发出。
  原因：`shouldInterceptRequest` 拿不到 POST 请求体，登录这类带体的请求拦不得。
- 内置包里没有的静态文件（比如线上改版换了带 hash 的文件名）自动回源，不会白屏 —— 但这是兜底，
  正常路径应该 100% 命中 `assets/www`，所以每次改完 uni-app 都要重新同步（见下）。
- 顶栏：`Theme.Material(.Light).NoActionBar`。0.2.0 用的是带 ActionBar 的主题，系统那条
  `colorPrimary` 深蓝标题栏压在 H5 自己的导航条上面，用户看到的"上面一块蓝的挡着"就是它；
  现在整条去掉，状态栏取 `app/uniapp/src/theme.json` 的 `navBg`（深色系统取 dark 段），与界面顶栏连成一片。
- 壳层不再有进度条和左下角地址徽标（那两样是"套壳"观感的来源）。调试入口只剩**长按页面 → 后端地址**。
- 包名 `com.yunshang.erp.demo`，刻意与将来正式包 `com.yunshang.erp` 区分，避免互相覆盖。
- 版本：`versionName 0.3.0` / `versionCode 3`（同包名安装时 versionCode 必须递增）。
- 签名：仓库外的自签证书 `D:\MajorCacheFolder\erp-keystore\yunshang-erp.jks`（别名 `yunshang-erp`，
  有效期到 2054-03-16），口令只在本机 `keystore.properties` 里，**不入库**。

## 构建

两步，顺序不能反：

```powershell
# 1) 出移动端界面并同步进 assets/www
#    接口地址必须是"相对路径"，界面才能与文档同源、免 CORS。
#    .env.production.local 平时是小程序用的绝对地址，出安卓包这一版要临时改回相对：
cd "D:\MajorCacheFolder\Company system\erp-inventory-system\app\uniapp"
Set-Content -NoNewline -Encoding ascii .env.production.local "VITE_API_BASE_URL=/api/v1`n"
npm run build:h5
cd "D:\MajorCacheFolder\Company system\erp-inventory-system\app\android-demo"
node scripts/sync-h5.mjs

# 出完包把小程序要用的绝对地址改回去（否则 build:mp-weixin 会拿到相对地址，preflight 会拦）
Set-Content -NoNewline -Encoding ascii "../uniapp/.env.production.local" "VITE_API_BASE_URL=https://xuliu.zemworld.cn/api/v1`n"

# 2) 出包
.\gradlew.bat assembleRelease --no-daemon
```

别用 `$env:VITE_API_BASE_URL=""` 这种写法：PowerShell 里赋空串等于**删掉**这个变量，
`.env.production.local` 的绝对地址照样生效，出的是跨域包。（`sync-h5.mjs` 正是为这一步设的闸：
产物里出现"真实主机 + /api/v1"的绝对地址就直接报错拒绝同步，不让坏包出得去。）

`sync-h5.mjs` 还会在两件事上直接失败：H5 产物不存在；`index.html` 里有绝对 `<base>`。

前置：

- Android SDK：`D:\android-sdk`（platforms;android-34、build-tools;34.0.0、platform-tools）
- JDK 17：`C:\Program Files\Java\jdk-17`；Gradle wrapper 已随本目录提交，不需要全局 Gradle
- 签名材料：把 `keystore.properties.example` 复制成 `keystore.properties` 并填四项
  （口令取自仓库外私有文件 `C:\Users\Administrator\.qoder-secrets\erp-android-keystore.txt`，永远不要提交）。
  缺该文件时 release **退回 debug 签名并在配置阶段告警**，方便干净克隆仍能构建，但那种产物只能自用。

产物：`app\build\outputs\apk\release\app-release.apk` → 仓库成品 `app\yunshang-erp-0.3.0.apk`（`*.apk` 已 gitignore，只提交 `.sha256`）。

## 使用

1. 安装后直接打开就是工作台/登录页，登录 `admin / Admin@12345`（演示数据）。
2. 想让数据来自别的后端：长按页面 →「后端地址」，例如内网联调填 `http://<电脑局域网IP>:8082`。
   界面仍来自安装包，改的只是数据源。
3. 明文（http）只对非 `zemworld.cn` 的主机放行，见 `app/src/main/res/xml/network_security_config.xml`
   —— 云端强制 https，内网调试仍可用。

## 图标

`app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`（48/72/96/144/192）
与同名 `ic_launcher_round.png`（圆形裁切版），母图是 `design/icon-master.svg`
（等价副本 `frontend/web/public/favicon.svg`）。

产出方式（注意：`scripts/render-brand-icon.mjs` 在非 1024 尺寸下会**裁到母图左上角**而不是缩放，
所以先出 1024 母图，再本地等比缩放）：

```bash
cd "D:/MajorCacheFolder/Company system/erp-inventory-system"
node scripts/render-brand-icon.mjs .tmp/android-icon/icon-master-1024.png=1024
powershell -NoProfile -ExecutionPolicy Bypass -File .tmp/android-icon/make-mipmaps.ps1
```

## 校验

```bash
"D:/android-sdk/build-tools/34.0.0/apksigner.bat" verify --print-certs app/yunshang-erp-0.3.0.apk
"D:/android-sdk/build-tools/34.0.0/aapt.exe" dump badging app/yunshang-erp-0.3.0.apk
unzip -l app/yunshang-erp-0.3.0.apk | grep -c "assets/www/"
```

期望：签名证书 DN 是 `CN=Yunshang ERP`（自签，不是 `CN=Android Debug`）；包名 `com.yunshang.erp.demo`、
versionName `0.3.0`、versionCode `3`；权限含 `INTERNET` 与 `CAMERA`；`application-label` 为「云裳进销存」；
`assets/www/` 有 68 个文件。

## 已验 / 未验

| 项 | 状态 |
|---|---|
| release 构建产出可安装 APK，且是 yunshang-erp 自签证书 | 已验（`apksigner verify --print-certs`） |
| 包名 / 版本 / 权限 / 标签 / 图标 / 界面已打进包 | 已验（`aapt dump badging` + `unzip -l` 68 个 assets/www 文件） |
| 拦截规则：静态走安装包、接口放行、SPA 兜底 | 已验（Chromium 按同一套判定跑 `assets/www`：离线渲染出登录页，无一条静态回源、无控制台错误） |
| 内置界面 + 真后端跑通登录主链 | 已验（`POST /api/v1/auth/login` 成功后进工作台，随后 dashboard/库存/仓库/商品等接口全部返回） |
| 真机安装后的顶栏颜色、返回键、冷启动观感 | **未验**（本机既无 adb 设备也无模拟器） |
| WebView 调用摄像头扫码 | **未验** |

## 限制（如实标注）

- **数据仍要联网**：界面离线可开，库存/单据这些必须打到后端；后端下线时应用停在登录页报错。
- 自签证书 = 个人分发（直接把 APK 发给别人装），没有应用商店上架资料，也没有 Google Play App Signing。
- 证书丢了就无法覆盖升级已装的 App：`yunshang-erp.jks` 与口令文件要在仓库外另做备份。
- 真正的原生能力（推送、扫码 SDK、后台服务）没有做 —— 需要时走 HBuilderX 云打包，见 `app/uniapp/README.md` §6。
- uni-app 的 H5 产物会向 `https://cdn.dcloud.net.cn/img/shadow-grey.png` 取一张装饰阴影图；
  断网时这张图加载失败，只影响一处很淡的投影，不影响功能。
