# 接口索引（自动生成，请勿手写）

> 来源：`packages/api-contract/openapi.yaml`（单一事实源，ADR-0008）

| 方法 | 路径 | operationId | 摘要 |
|---|---|---|---|
| GET | `/warehouses` | `listWarehouses` | 查询仓库列表（返回数组，不分页） |
| POST | `/warehouses` | `createWarehouse` | 新建仓库 |
| GET | `/warehouses/{id}` | `getWarehouse` | 仓库详情 |
| PUT | `/warehouses/{id}` | `updateWarehouse` | 更新仓库（编码不可修改） |
| POST | `/warehouses/{id}/disable` | `disableWarehouse` | 停用仓库 |
| GET | `/suppliers` | `listSuppliers` | 查询供应商列表 |
| POST | `/suppliers` | `createSupplier` | 新建供应商 |
| GET | `/suppliers/{id}` | `getSupplier` | 供应商详情 |
| PUT | `/suppliers/{id}` | `updateSupplier` | 更新供应商（编码不可修改） |
| POST | `/suppliers/{id}/disable` | `disableSupplier` | 停用供应商 |
| GET | `/customers` | `listCustomers` | 查询客户列表 |
| POST | `/customers` | `createCustomer` | 新建客户 |
| GET | `/customers/{id}` | `getCustomer` | 客户详情 |
| PUT | `/customers/{id}` | `updateCustomer` | 更新客户（编码不可修改） |
| POST | `/customers/{id}/disable` | `disableCustomer` | 停用客户 |
| GET | `/products` | `listProducts` | 查询商品列表 |
| POST | `/products` | `createProduct` | 新建商品（款式） |
| GET | `/products/{id}` | `getProduct` | 商品详情 |
| GET | `/skus` | `listSkus` | 查询 SKU 列表 |
| POST | `/skus` | `createSku` | 新建 SKU（或按颜色×尺码矩阵批量生成） |
| GET | `/inventory` | `queryInventory` | 实时库存查询（多仓/多SKU） |
| GET | `/inventory/ledger` | `queryInventoryLedger` | 库存流水（不可变，可反查来源单据） |
| POST | `/inventory/reserve` | `reserveInventory` | 库存预占（下单锁定可用量） |
| POST | `/inventory/reservations/{id}/release` | `releaseReservation` | 释放预占（取消订单 / 超时释放） |
| GET | `/inventory/transfers` | `listInventoryTransfers` | 调拨单列表（FR-INV-07） |
| POST | `/inventory/transfers` | `createInventoryTransfer` | 建调拨单并立即调出（两段式第一段） |
| POST | `/inventory/transfers/{id}/receive` | `receiveInventoryTransfer` | 调拨到货（两段式第二段） |
| GET | `/inventory/stocktakes` | `listStocktakes` | 盘点单列表（FR-INV-08） |
| POST | `/inventory/stocktakes` | `createStocktake` | 建盘点单（快照账面量） |
| POST | `/inventory/stocktakes/{id}/post` | `postStocktake` | 盘点过账（差异生成 ADJUST_IN/OUT 流水） |
| GET | `/inventory/adjustments` | `listInventoryAdjustments` | 库存调整列表（FR-INV-09） |
| POST | `/inventory/adjustments` | `createInventoryAdjustment` | 库存调整（原因必填） |
| GET | `/inventory/freezes` | `listInventoryFreezes` | 冻结记录列表（FR-INV-10） |
| POST | `/inventory/freezes` | `createInventoryFreeze` | 冻结库存（冻结量不进可用量） |
| POST | `/inventory/freezes/{id}/release` | `releaseInventoryFreeze` | 解冻库存（人工） |
| GET | `/inventory/balance-dimensions` | `queryBalanceDimensions` | 五维结存查询（仓 × SKU × 批次 × 状态 × 权属，FR-INV-11） |
| GET | `/inventory/alerts` | `queryInventoryAlerts` | 库存预警（安全库存 / 滞销 / 临期，FR-INV-12） |
| GET | `/inventory/replenishment` | `queryReplenishment` | 补货建议（min/max 阈值档，FR-INV-13） |
| GET | `/purchase-orders` | `listPurchaseOrders` | 查询进货单 |
| POST | `/purchase-orders` | `createPurchaseOrder` | 创建进货单（草稿） |
| GET | `/purchase-orders/{id}` | `getPurchaseOrder` | 进货单详情 |
| POST | `/purchase-orders/{id}/receive` | `receivePurchaseOrder` | 入库确认（生成入库流水、增加库存） |
| POST | `/purchase-orders/{id}/void` | `voidPurchaseOrder` | 作废/红冲进货单 |
| GET | `/sales-orders` | `listSalesOrders` | 查询出货单 |
| POST | `/sales-orders` | `createSalesOrder` | 创建出货单（同时预占库存） |
| POST | `/sales-orders/{id}/ship` | `shipSalesOrder` | 出货确认（预占转出库、扣减库存） |
| POST | `/sales-orders/{id}/void` | `voidSalesOrder` | 作废/红冲出货单（回补库存） |
| GET | `/return-orders` | `listReturnOrders` | 查询退货单（rma:read） |
| POST | `/return-orders` | `createReturnOrder` | 创建退货单（rma:create） |
| GET | `/return-orders/returnable-orders` | `listReturnableOrders` | 查询可退货的出货单（rma:read） |
| GET | `/return-orders/{id}` | `getReturnOrder` | 退货单详情（rma:read） |
| POST | `/return-orders/{id}/submit` | `submitReturnOrder` | 提交退货单（rma:submit） |
| POST | `/return-orders/{id}/approve` | `approveReturnOrder` | 审核退货单（rma:approve） |
| POST | `/return-orders/{id}/reject` | `rejectReturnOrder` | 驳回退货单（rma:reject） |
| POST | `/return-orders/{id}/receive` | `receiveReturnOrder` | 退货入库（rma:receive） |
| POST | `/return-orders/{id}/void` | `voidReturnOrder` | 作废退货单（rma:void） |
| POST | `/settlements/daily` | `runDailySettlement` | 日清（每日汇总当日出入库与金额） |
| POST | `/settlements/monthly` | `runMonthlySettlement` | 月结（期间结账并锁账） |
| GET | `/settlements/reconcile` | `reconcile` | 总账与明细对账（差额必须为 0） |
| GET | `/settlements/periods` | `listSettlementPeriods` | 账期列表查询（set:read，只读） |
| GET | `/reports/purchase` | `purchaseReport` | 进货统计（按供应商/商品/期间） |
| GET | `/reports/sales` | `salesReport` | 出货统计（按客户/商品/期间） |
| GET | `/reports/inventory-summary` | `inventorySummaryReport` | 进销存汇总表（期初 + 入 − 出 = 期末） |
| POST | `/nl/command` | `nlCommand` | 自然语言操作入口（FR-SYS-04） |
| POST | `/cost/recalculate` | `recalculateCost` | 全量重算成本层（FR-CST-01，INV-11） |
| POST | `/cost/monthly` | `runCostMonthly` | 成本月结（FR-CST-02，幂等） |
| GET | `/cost/monthly` | `listCostMonthly` | 查询成本月结（FR-CST-02） |
| GET | `/reports/profit` | `getProfitReport` | 毛利分析（FR-CST-03 / FR-RPT-04） |
| GET | `/purchase-requisitions` | `listPurchaseRequisitions` | 查询请购单 |
| POST | `/purchase-requisitions` | `createPurchaseRequisition` | 创建请购单（草稿） |
| GET | `/purchase-requisitions/{id}` | `getPurchaseRequisition` | 请购单详情 |
| POST | `/purchase-requisitions/{id}/submit` | `submitPurchaseRequisition` | 提交请购单 |
| POST | `/purchase-requisitions/{id}/approve` | `approvePurchaseRequisition` | 审核请购单 |
| POST | `/purchase-requisitions/{id}/convert` | `convertPurchaseRequisition` | 请购转采购订单 |
| GET | `/purchase-orders/{id}/receipts` | `listPurchaseReceipts` | 到货/质检记录 |
| GET | `/purchase-returns` | `listPurchaseReturns` | 查询采购退货单 |
| POST | `/purchase-returns` | `createPurchaseReturn` | 创建采购退货单（草稿，不动库存/应付） |
| GET | `/purchase-returns/{id}` | `getPurchaseReturn` | 采购退货单详情 |
| POST | `/purchase-returns/{id}/approve` | `approvePurchaseReturn` | 执行采购退货（扣库存 + 冲应付） |
| POST | `/purchase-returns/{id}/void` | `voidPurchaseReturn` | 作废采购退货草稿 |
| GET | `/supplier-payables` | `listSupplierPayables` | 查询供应商应付单 |
| GET | `/supplier-payables/reconcile` | `reconcileSupplierPayables` | 应付余额与核销守恒核对 |
| GET | `/supplier-payables/{id}` | `getSupplierPayable` | 应付单详情（含核销明细） |
| POST | `/supplier-payments` | `createSupplierPayment` | 付款并核销（支持部分核销、多应付、超额拒绝） |
| GET | `/audits` | `listAudits` | 审计查询（FR-SYS-07，按人/单据/时间/操作类型，只读） |
| GET | `/audits/{id}` | `getAudit` | 审计详情（FR-SYS-07） |
| GET | `/audits/timeline` | `auditTimeline` | 某张业务单据的审计轨迹（FR-SYS-07，按时间正序） |
| GET | `/audits/by-trace` | `auditByTrace` | 按 traceId 取该请求产生的全部审计（FR-SYS-08 贯穿性的落库侧证据） |
| GET | `/dicts` | `listDictTypes` | 字典类型列表（只读） |
| GET | `/dicts/{typeCode}/items` | `listDictItems` | 某类型的字典项（按 sortNo 升序） |

