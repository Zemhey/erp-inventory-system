# 安卓壳（WebView · 云端优先 · release 签名）

这是一个**本地构建、release 签名**的安卓安装包，不是 HBuilderX 云打包的正式 uni-app APK。

## 它是什么

- 最小 Android WebView 壳，**默认加载公网移动端 H5 站点** `https://m.zemworld.cn/`；
  该站点尚未解析/上线时，主文档加载失败（DNS 不通、连不上、10 秒超时、HTTP 4xx/5xx）
  会自动改试兜底地址 `https://xuliu.zemworld.cn/`（已上线的线上网页版，同源带 `/api` 反代）。
- 左下角常驻地址徽标显示当前用的是哪个站点（点它=「设置服务器」），一眼能看出是云端还是回落。
- 自动回落**不写回偏好**：每次冷启动仍先试 `m.zemworld.cn`，等阶段 A 上线后无需重装或改设置就自动接上；
  只有「设置服务器」里填的地址（含内网 3008）会被记住并优先使用，且不做自动回落。
- 包名 `com.yunshang.erp.demo`，刻意与将来正式包 `com.yunshang.erp` 区分，避免互相覆盖。
- 版本：`versionName 0.2.0` / `versionCode 2`（同包名安装时 versionCode 必须递增）。
- 签名：仓库外的自签证书 `D:\MajorCacheFolder\erp-keystore\yunshang-erp.jks`（别名 `yunshang-erp`，
  有效期到 2054-03-16），口令只在本机 `keystore.properties` 里，**不入库**。

## 构建

前置：

- Android SDK：`D:\android-sdk`（platforms;android-34、build-tools;34.0.0、platform-tools）
- JDK 17：`C:\Program Files\Java\jdk-17`
- Gradle wrapper 已随本目录提交，不需要全局 Gradle
- 签名材料：把 `keystore.properties.example` 复制成 `keystore.properties` 并填 `storeFile` /
  `storePassword` / `keyAlias` / `keyPassword`（口令取自仓库外私有文件
  `C:\Users\Administrator\.qoder-secrets\erp-android-keystore.txt`，永远不要提交）。
  缺该文件时 release 会**退回 debug 签名并在配置阶段告警**，方便干净克隆仍能构建，
  但那种产物只能自用，不能当分发包。

```powershell
cd "D:\MajorCacheFolder\Company system\erp-inventory-system\app\android-demo"
.\gradlew.bat assembleRelease --no-daemon
```

产物：`app\build\outputs\apk\release\app-release.apk`
→ 仓库成品 `app\yunshang-erp-0.2.0.apk`，SHA-256 见同目录 `.sha256`。

调试包仍可用 `.\gradlew.bat assembleDebug --no-daemon`（debug 签名，与 release 签名互不覆盖安装）。

## 使用

1. 安装 APK，直接打开就是云端站点，登录 `admin / Admin@12345`（演示数据）。
2. 内网调试（想让界面来自开发电脑）：右上角菜单 →「设置服务器」，或长按页面，
   填 `http://<电脑局域网IP>:3008/`（`启动系统.cmd` 的手机预览服务）。
   填过之后这个地址会被记住并优先使用，不再自动回落；菜单里的「改用云端地址」可切回默认。
3. 明文（http）只对非 `zemworld.cn` 的主机放行，见 `app/src/main/res/xml/network_security_config.xml`
   —— 云端两个站点强制 https，内网调试仍可用。

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

`make-mipmaps.ps1` 是一次性辅助脚本，放在 `.tmp/`（已 gitignore），不入库。

## 校验

```bash
"D:/android-sdk/build-tools/34.0.0/apksigner.bat" verify --print-certs app/yunshang-erp-0.2.0.apk
"D:/android-sdk/build-tools/34.0.0/aapt.exe" dump badging app/yunshang-erp-0.2.0.apk
```

期望：签名证书 DN 是 `CN=Yunshang ERP`（自签，不是 `CN=Android Debug`）；
包名 `com.yunshang.erp.demo`、versionName `0.2.0`、versionCode `2`；
权限含 `INTERNET` 与 `CAMERA`；`application-label` 为「云裳进销存」；图标 `application-icon-480` 存在。

## 限制（如实标注）

- 页面仍来自服务器：`m.zemworld.cn` / `xuliu.zemworld.cn` 全部下线时，App 只剩错误提示页
  （断网可用的内置界面是计划书阶段 C，未做）。
- 扫码：H5 端 `uni.scanCode` 走手动输入降级；壳层已声明并处理 `CAMERA` 运行时权限
  （页面通过 WebView 申请摄像头时才弹窗），但**未在真机上实测过扫码链路**。
- 自签证书 = 个人分发（直接把 APK 发给别人装），没有应用商店上架资料，也没有 Google Play App Signing。
- 证书丢了就无法覆盖升级已装的 App：`yunshang-erp.jks` 与口令文件要在仓库外另做备份。

## 已验 / 未验

| 项 | 状态 |
|---|---|
| release 构建产出可安装 APK | 已验（本机 Gradle 8.9 + SDK 34） |
| 签名是 yunshang-erp 自签证书（非 debug key） | 已验（`apksigner verify --print-certs`） |
| 包名 / 版本 / 权限 / 标签 / 图标 | 已验（`aapt dump badging`） |
| 真机安装 → 打开云端站点 → 登录 | **未验**（无真机，需手机安装后确认） |
| m.zemworld.cn 未解析时自动回落 xuliu | **未验**（回落逻辑已实现，需真机或模拟器实测） |
| WebView 调用摄像头扫码 | **未验** |

## 正式 APK 还差什么

正式包仍需外部资源，拿齐后按 `app/uniapp/README.md` §6 执行：

1. DCloud 账号 + manifest 顶层 appid 归属确认
2. 一张 ≥1024×1024 的图标主图（已有 `design/icon-master.svg`，`node scripts/make-icons.mjs --write-manifest`）
3. HBuilderX → 发行 → 原生 App-云打包 → Android（签名证书可复用上面那把 `.jks`）
4. 真机验证登录 + 一个写操作 + 一次扫码
