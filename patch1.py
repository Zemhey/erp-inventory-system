import re, io, sys
p='backend/server/src/main/java/com/erp/pur/application/PurchaseOrderApplicationService.java'
s=open(p,encoding='utf-8').read()
old="""        String orderNo = numberGenerator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, bizDate);
        long orderId = orders.insertHeader(orderNo, cmd.supplierId(), cmd.warehouseId(), bizDate,
                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId());"""
new="""        String orderNo = numberGenerator.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, bizDate);
        long orderId = orders.insertHeader(orderNo, cmd.supplierId(), cmd.warehouseId(), bizDate,
                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId(),
                // 数据权限"按本人"（FR-IAM-04）依赖这一列；系统任务无上下文时为 null
                actors.currentUserId().isPresent()
                        ? actors.currentUserId().getAsLong() : null);"""
assert old in s
s=s.replace(old,new)
open(p,'w',encoding='utf-8').write(s)
print("patched insertHeader call")
