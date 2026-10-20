# 测试用例索引（自动生成，请勿手写）

> 来源：扫源码中的 `@CaseId` 注解 + 追踪矩阵（docs/00-索引与追踪矩阵.md）

| 用例ID | 用例名称 | 所在文件 |
|---|---|---|
| `TC-INV-001` | 预占成功后：实存不变、预占增加、可用量相应减少（不变量 INV-3） | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-002` | 可用量不足必须拒绝，且不产生任何副作用（错误码含定位信息） | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-003` | 预占只受可用量约束：实存 10、已预占 8 时，预占 3 必须失败 | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-004` | 释放预占后可用量恢复，实存不受影响 | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-005` | 相同 requestId 重复预占只生效一次（幂等，不变量 INV-9） | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-006` | ★ 并发预占：100 个请求抢 50 件库存，绝不超卖且无流水漂移 | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-007` | ★ 并发预占同一 requestId：只允许一次生效（数据库唯一约束兜底） | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-008` | 入库写正向流水后，实存与流水净和一致（不变量 INV-2） | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-009` | 按 FEFO 出库：优先扣减最早效期批次，单笔可拆多批次 | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-010` | 数据库 CHECK 约束兜底：即使应用层有 bug，实存也不可能被写成负数 | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |
| `TC-INV-011` | 数据库 CHECK 约束兜底：预占不得超过实存 | `apps/server/src/test/java/com/erp/inv/InventoryReserveIT.java` |

共 11 条用例。
