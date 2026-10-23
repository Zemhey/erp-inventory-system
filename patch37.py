p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
old="""    void documentCannotBeApprovedBeforeSubmittingApproval() {
        long poId = createPurchaseOrder(600_000, "PO-GATE-1");
        purchaseOrders.submit(poId);

        assertThatThrownBy(() -> purchaseOrders.approve(poId))"""
new="""    void documentCannotBeApprovedBeforeSubmittingApproval() {
        long poId = createPurchaseOrder(600_000, "PO-GATE-1");
        purchaseOrders.submit(poId);
        // 单号是应用层生成的（PO+日期+序号），不是创建时传的 remark
        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM purchase_orders WHERE id = ?", String.class, poId);

        assertThatThrownBy(() -> purchaseOrders.approve(poId))"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("orderNo defined")
