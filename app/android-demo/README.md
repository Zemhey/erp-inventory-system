# Android 演示壳（WebView）

这是一个**本地演示用安装包**，不是 HBuilderX 云打包的正式 uni-app APK。

## 它是什么

- 一个最小 Android WebView 壳，加载 `app/uniapp` 的 H5 产物（由电脑上的
  `scripts/mobile-preview.mjs` 在 3008 端口托管，并把 `/api` 反代到后端）。
- 包名 `com.yunshang.erp.demo`，刻意与将来正式包 `com.yunshang.erp` 区分，避免互相覆盖。
- 本机没有 DCloud/HBuilderX 账号与签名证书，所以**无法在这里产出正式云打包 APK**；
  这个壳解决的是“现在就要在安卓手机上点界面”的问题。

## 构建

前置：

- Android SDK：`D:\android-sdk`（platforms;android-34、build-tools;34.0.0、platform-tools）
- JDK 17：`C:\Program Files\Java\jdk-17`
- Gradle wrapper 已随本目录提交，不需要全局 Gradle

```powershell
# Windows 中文路径建议走 ASCII 联接（与后端 ADR-0010 同一套做法）
cmd /c mklink /J D:\erp-android "<仓库路径>\app\android-demo"
cd D:\erp-android
.\gradlew.bat assembleDebug --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（经联接路径构建时即仓库内的
`app\android-demo\app\build\outputs\apk\debug\app-debug.apk`）

仓库里的成品在 `app\yunshang-erp-demo.apk`，SHA-256 见同目录 `.sha256` 文件。

## 使用

1. 在电脑上运行仓库根目录的 `启动系统.cmd`，它会启动：
   - Web：`http://127.0.0.1:3030/`
   - 手机预览：`http://<电脑局域网IP>:3008/`
2. 手机与电脑连同一个 Wi-Fi。
3. 安装 APK，打开后填服务器地址（默认已填本机当前局域网地址
   `http://172.17.41.78:3008/`；换了网络就改成新的电脑 IP）。
4. 登录 `admin / Admin@12345`。

APK 内可通过右上角菜单「设置服务器」或长按页面修改地址。

## 限制（如实标注）

- 页面来自电脑上的 H5 预览服务：电脑关机/预览服务停止后，APK 打不开。
- 扫码页在 H5 下走手动输入降级路径，不调用手机摄像头；
  正式 uni-app APK 才有 `uni.scanCode` 原生扫码。
- 没有离线缓存、没有安装包签名/上架资料。

## 正式 APK 还差什么

正式包仍然需要外部资源，拿齐后按 `app/uniapp/README.md` §6 执行：

1. DCloud 账号 + manifest 顶层 appid 归属确认
2. Android 签名证书 `.jks`（放仓库外）
3. 一张 ≥1024×1024 的图标主图（`node scripts/make-icons.mjs --write-manifest`）
4. HBuilderX → 发行 → 原生 App-云打包 → Android
5. 真机验证登录 + 一个写操作 + 一次扫码
