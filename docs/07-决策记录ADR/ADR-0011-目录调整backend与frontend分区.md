# ADR-0011：目录结构调整为 backend/ + frontend/ 两大分区

- 状态：已采纳
- 日期：M1 完成后、M3（Web 端）开始前
- 影响范围：仓库根目录结构、CI 路径、索引脚本、全部文档中的路径引用

---

## 背景

M0/M1 阶段采用 `apps/` 单仓多包结构：

```
apps/
  server/     Spring Boot 服务端
  web/        Vue3（规划中）
  miniapp/    uni-app（规划中）
  android/    uni-app（规划中）
```

这一结构的问题在 **M1 完成后、Web 端动工前**变得明显：

1. **`apps/` 这个词不传达信息**。对不熟悉项目的人（包括三个月后的自己、以及评审者），
   打开仓库看到 `apps/` 无法判断里面是前端还是后端，需要点进去一个个看。
2. **前端与后端混在同一层**。`apps/server`（后端）与 `apps/web`（前端）同级，
   当仓库里出现 `infra/`、`packages/`、`docs/` 后，"哪些目录是产品代码"的边界被稀释。
3. **四端（web/miniapp/android）都属客户端**，它们与"服务端"是两类东西，
   放在同一层掩盖了这个事实。

## 决策

改为**按"前后端"两大分区**：

```
backend/
  server/                   Spring Boot 服务端（原 apps/server）
frontend/                   所有客户端（原 apps/web、apps/miniapp、apps/android）
  web/                      Vue3 + Element Plus（M3）
  miniapp/                  uni-app 小程序端（M4）
  android/                  uni-app / 原生 Android（M5）
```

其余目录（`docs/`、`index/`、`packages/`、`scripts/`、`infra/`、`.plan/`）**保持不变**。

### 关键约束（迁移必须同时满足）

迁移不是"把目录拖一下"，以下四点缺一即失败：

1. **构建不能断**：`D:\erp-build` 联接指向仓库根，移动后自动跟随；
   但 `scripts/build-env.ps1` 的路径校验、`gradlew` 的工作目录必须同步改。
2. **CI 不能断**：`.github/workflows/ci.yml` 中 9 处 `apps/server` 必须改，
   否则 GitHub Actions 会因找不到工作目录而失败。
3. **索引脚本必须改**：`gen-index.sh` / `gen-index.ps1` 的 `SERVER_DIR` 变量。
4. **文档里的路径必须改**：README、docs/00 中的超链接指向真实文件，改了目录不改链接 = 死链。

### 验收标准

迁移后 **`./gradlew clean check` 必须全绿、152 条用例数不变、覆盖率不倒退**。

## 为什么是现在做（而不是更早或更晚）

| 时机 | 迁移成本 |
|---|---|
| M0 时 | 成本几乎为零，但那时还没有 Web 端概念，"前端归属"不是痛点 |
| **M1 后、M3 前（本次）** | **需改 8 个手写文件；前端尚未动工，无前端代码需搬迁** |
| M3 写了几十个 Vue 文件之后 | 需搬迁大量前端代码 + 改 import 路径 + 重建依赖，成本数倍 |

实测影响面：全仓 122 处引用中，**95 处是 `index/test-map.md` 生成产物**
（重新生成即可），真正需要手改的只有 **8 个文件**。
Gradle 配置（`settings.gradle.kts`）经确认**无任何硬编码路径**，整体移动无影响。

## 放弃的替代方案

| 方案 | 放弃原因 |
|---|---|
| 保持 `apps/`，只补充 `apps/README.md` 说明 | 治标不治本：README 只在"有人主动去读"时有效，而目录本身的歧义每次打开都存在 |
| 改成 `backend/` + `frontend/` 但把小程序/Android 单独放 `clients/` | 三个目录表达同一个概念（客户端），反而更乱。小程序与 Android 本质就是前端 |
| 用 npm workspace / Gradle 多项目做逻辑分区 | 那是"构建层面的分区"，解决不了"打开仓库看不清哪是前端"的认知问题 |

## 后果

**正面**
- 打开仓库一眼可见：`backend/` 是服务端，`frontend/` 是全部客户端
- 前端四端（web/miniapp/android）有明确归属，不再与后端同级
- 为 M3~M5 的多端开发准备了不会返工的目录地基

**负面 / 代价**
- 偏离了早期 `apps/` 单仓多包结构（该结构已由本 ADR 取代）
- 历史提交中仍存在 `apps/server` 路径，`git log` 跨该提交追溯时会看到路径变化

## 迁移记录（可复查）

| 步骤 | 操作 | 验证 |
|---|---|---|
| 1 | 删除 `.buildenv/`（702 MB Gradle 缓存，沙箱限制期的临时产物，已 gitignore） | 目录消失 |
| 2 | `apps/server` → `backend/server`（`Move-Item`，保留 git 历史） | 新路径存在 |
| 3 | 修改 8 个手写文件中的 `apps/server` → `backend/server` | 全仓 grep 无手写残留 |
| 4 | `gradlew clean check` | **152 条全绿，行 73.4% / 分支 52.0%** |
| 5 | `gen-index.ps1` 重新生成索引 | 95 条用例路径全部更新 |
| 6 | 空 `apps/` 删除 | 根目录只剩 8 个顶层项 |

> 注：`git mv` 未被使用，而是文件系统 `Move-Item` + `git add -A`。
> Git 通过内容相似度识别为重命名，历史仍可追溯（`git log --follow` 可验证）。
