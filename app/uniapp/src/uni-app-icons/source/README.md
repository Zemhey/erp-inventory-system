# 应用图标（安卓 / iOS 出包用）

这个目录放**设计资产与产出尺寸**，不是构建产物。

- `source/master.png` —— 主图（**待提供**）。要求：方形、≥1024×1024、PNG-24/32（非隔行）、主体居中、四边留透明安全区。
- 产出命令：`node scripts/make-icons.mjs --write-manifest`
  → `android/{ldpi36,mdpi48,hdpi72,xhdpi96,xxhdpi144,xxxhdpi192}.png` + `ios/{120,180,76,152,1024}.png`
- 没有主图时脚本**直接报错退出**，不会生成纯色方块凑数；缺图标也照样能出包（HBuilderX 用自己的默认图标），
  所以 `preflight` 里这条是 ⚠ 提醒而不是 ✖ 阻塞。
- 尺寸是 HBuilderX/Android 启动图标的严格值，改尺寸请连 `src/manifest.json` 的 `app-plus.distribute.icons` 一起改。
