# index/ · 代码索引（生成物，请勿手写）

> ⚠️ **本目录下所有文件都由脚本生成，禁止手工编辑。**
> 手改会在下次生成时被覆盖；且手写索引必然过期后开始误导（见 [ADR-0009](../docs/07-决策记录ADR/ADR-0009-代码索引脚本生成.md)）。

## 生成物清单

| 文件 | 内容 | 数据来源 | 生成脚本 | 状态 |
|---|---|---|---|---|
| `modules.md` | 模块清单：包名、职责、文件数、代码行数、依赖 | `apps/server/src/main/java/com/erp/` 目录扫描 | `scripts/gen-index.sh` | ⬜ 待 M0 骨架 |
| `api-map.md` | 接口清单：路径、方法、请求/响应 schema、错误码 | `packages/api-contract/openapi.yaml` | `scripts/gen-openapi.sh` | ⬜ 待契约 |
| `deps.json` | 模块与第三方依赖图（机器可读） | `gradle dependencies` / `npm ls` | `scripts/gen-index.sh` | ⬜ 待骨架 |
| `coverage-map.md` | 按模块的测试覆盖率 | JaCoCo / c8 报告（CI 产物） | CI | ⬜ 待测试地基 |
| `test-map.md` | 需求 FR ↔ 测试用例 TC 的映射与状态 | [追踪矩阵](../docs/00-索引与追踪矩阵.md#3-追踪矩阵--本文件的核心) + Allure 结果 | `scripts/gen-index.sh` | ⬜ 待测试地基 |

## 刷新命令

```bash
./scripts/gen-index.sh            # 重新生成 index/ 下全部产物
./scripts/gen-index.sh --check    # CI 模式：生成结果与已提交内容不一致 → 构建失败
```

## 设计原则

1. **永不手写** —— 只有脚本能写这个目录；
2. **可校验** —— `--check` 模式纳入 CI，保证文档与代码零漂移；
3. **单一来源** —— 接口相关的一切都从 `openapi.yaml` 派生（[ADR-0008](../docs/07-决策记录ADR/ADR-0008-OpenAPI为单一事实源.md)）；
4. **机器可读优先** —— 先产出 `deps.json` 等结构化数据，再渲染 Markdown。

## 为什么现在还没有内容

代码和契约尚未落地，索引无内容可生成。这一阶段要做的是**定义生成方式与产物模板**（已完成），骨架跑通后一条命令即可刷新。

> 简历价值点："代码索引与接口清单由脚本从源码与 OpenAPI 自动生成，并纳入 CI 校验以保证文档与代码零漂移。"
