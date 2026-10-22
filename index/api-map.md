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
| GET | `/return-orders/{id}` | `getReturnOrder` | 退货单详情（rma:read） |
| POST | `/return-orders/{id}/submit` | `submitReturnOrder` | 提交退货单（rma:submit） |
| POST | `/return-orders/{id}/approve` | `approveReturnOrder` | 审核退货单（rma:approve） |
| POST | `/return-orders/{id}/reject` | `rejectReturnOrder` | 驳回退货单（rma:reject） |
| POST | `/return-orders/{id}/receive` | `receiveReturnOrder` | 退货入库（rma:receive） |
| POST | `/return-orders/{id}/void` | `voidReturnOrder` | 作废退货单（rma:void） |
| POST | `/settlements/daily` | `runDailySettlement` | 日清（每日汇总当日出入库与金额） |
| POST | `/settlements/monthly` | `runMonthlySettlement` | 月结（期间结账并锁账） |
| GET | `/settlements/reconcile` | `reconcile` | 总账与明细对账（差额必须为 0） |
| GET | `/reports/purchase` | `purchaseReport` | 进货统计（按供应商/商品/期间） |
| GET | `/reports/sales` | `salesReport` | 出货统计（按客户/商品/期间） |
| GET | `/reports/inventory-summary` | `inventorySummaryReport` | 进销存汇总表（期初 + 入 − 出 = 期末） |

