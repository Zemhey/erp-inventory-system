# backend/ · 服务端

> 本目录是**全部服务端代码**。前端（Web / 小程序 / Android）在 [`../frontend/`](../frontend/)。

## 技术栈

Java 17 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · JUnit 5 · Testcontainers · ArchUnit

## 模块划分（`server/src/main/java/com/erp/`）

| 包 | 职责 | 关键类 | 对应 FR |
|---|---|---|---|
| `mdm` | 主数据：商品 / SKU / 供应商 / 客户 / 仓库 | `SkuApplicationService`（颜色×尺码矩阵） | FR-MDM-01~04 |
| `inv` | 库存：余额 / 流水 / 预占 / FEFO | `InventoryApplicationService` ★ 库存变动唯一入口 | FR-INV-01~03/05 |
| `pur` | 进货：建单 / 审核 / 入库 | `PurchaseOrderApplicationService` | FR-PUR-01/02 |
| `sal` | 出货：建单即预占 / 出货 | `SalesOrderApplicationService` | FR-SAL-01/02 |
| `iam` | 认证与权限：JWT / 密码锁定 / RBAC | `JwtTokenService`、`PermissionAspect` | FR-IAM-01/02 |
| `shared` | 通用：时钟、错误码、单据状态机、取号 | `DocumentStatus`、`DocumentNumberGenerator` | — |

**分层约定**：`api`（Controller）→ `application`（业务规则）→ `repository`（SQL）→ `domain`（纯对象）。
Controller **不得**直连 Repository，由 ArchUnit 在 CI 强制。

## 关键约束（改代码前必读）

1. **库存只能通过 `InventoryApplicationService` 变动**，禁止任何模块直接 `UPDATE inventory_balances`。
   ArchUnit 规则 `库存变动只能经由INV模块` 会拦住违规。
2. **单据状态只能走 `DocumentStatusMachine.requireTransition`**，不要自己写 if 判断状态。
3. **禁止 `LocalDateTime.now()`**，时间一律来自可注入的 `ClockProvider`。
4. **金额一律 `long` 整数分**（ADR-0005），禁止 float/double。

## 常用命令

```powershell
# 首次：建 ASCII 构建联接（路径含中文时必需，见 ADR-0010）
cd ..; .\scripts\build-env.ps1

# 构建 + 全部测试 + 覆盖率门禁
cd D:\erp-build\backend\server
.\gradlew.bat check

# 只跑某个测试类
.\gradlew.bat test --tests "com.erp.sal.SalesOrderIT"

# 启动服务（http://127.0.0.1:8080，Swagger: /swagger-ui.html）
.\gradlew.bat bootRun
```

> **为什么构建要在 `D:\erp-build` 而不是本目录**：
> Gradle 8.9 在 Windows 上无法从含中文的路径运行测试（所有测试类报 `ClassNotFoundException`，
> 看似"没编译"，实为 ANSI 编码不一致）。详见 [ADR-0010](../docs/07-决策记录ADR/ADR-0010-Windows中文路径用ASCII联接构建.md)。

## 测试现状

156 条用例：库存 11 / 主数据 19 / 进货 15 / 出货 17 / IAM 10 / 状态机 54 / 取号 4 / API 契约 14 / 架构 12。
覆盖率：行 74.2%、分支 53.2%（门禁 ≥60% / ≥50%）。

## 数据库迁移

`src/main/resources/db/migration/`：

| 版本 | 内容 |
|---|---|
| V1 | 库存纵向切片（仓库/商品/SKU/余额/流水/预占/幂等键） |
| V2 | 主数据补强 + 单据表（进货单/出货单/供应商/客户/序号表） |
| V3 | IAM（用户/角色/权限） |

迁移由 Flyway 在启动时自动执行，**不要手改已应用的迁移文件**（会导致校验和不匹配）。
