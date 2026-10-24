import io
p='backend/server/src/main/java/com/erp/pur/repository/PurchaseOrderRepository.java'
s=io.open(p,encoding='utf-8').read()
bad = """     * 不会出现"第 1 页 3 条、第 2 页 0 条"的假象。
     */
     * 写回累计质检量（FR-PUR-05）。"""
good = """     * 不会出现"第 1 页 3 条、第 2 页 0 条"的假象。
     */
    /**
     * 写回累计质检量（FR-PUR-05）。"""
assert bad in s
s=s.replace(bad,good,1)
io.open(p,'w',encoding='utf-8').write(s)
print("orphaned javadoc fixed")
