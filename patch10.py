p='backend/server/src/main/java/com/erp/inv/repository/InventoryBalanceRepository.java'
s=open(p,encoding='utf-8').read()
old="""    private final JdbcTemplate jdbc;

    public InventoryBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }"""
new="""    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）：库存余额只有仓库维度（它不记录"谁建的" ——
     * 余额是单据执行产生的派生结果，不是人直接创建的行）。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public InventoryBalanceRepository(JdbcTemplate jdbc,
                                      com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }"""
assert old in s
s=s.replace(old,new,1)

old_q="""        if (onlyAvailable) {
            sql.append(" AND available_qty > 0");
        }
        sql.append(" ORDER BY sku_id, warehouse_id, expiry_date NULLS LAST, batch_no");"""
new_q="""        // ★ 数据权限：A 仓仓管查 B 仓库存必须得到**空结果**（不是 403）——
        //   条件拼进 SQL 之后，"库里有行但你看不到"与"库里本来没有行"
        //   在响应上是同一个形状，因此不泄露资源存在性（硬验收第 3 条）。
        sql.append(scope.warehouseOnly("warehouse_id", args));
        if (onlyAvailable) {
            sql.append(" AND available_qty > 0");
        }
        sql.append(" ORDER BY sku_id, warehouse_id, expiry_date NULLS LAST, batch_no");"""
assert old_q in s
s=s.replace(old_q,new_q,1)

old_find="""    public Optional<InventoryBalance> find(long skuId, long warehouseId, String batchNo) {
        List<InventoryBalance> rows = jdbc.query(\"\"\"
                SELECT id, sku_id, warehouse_id, batch_no, expiry_date,
                       on_hand_qty, reserved_qty, available_qty, version
                  FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                \"\"\", MAPPER, skuId, warehouseId, batchNo);
        return rows.stream().findFirst();
    }"""
new_find="""    /**
     * 按三维键取一行，<b>并施加数据权限</b>。
     *
     * <p>⚠️ 本方法<b>只用于读路径</b>（查询、详情）。写路径（预占/入库/出库）
     * 必须能操作任何仓库的行 —— 否则一张跨仓调拨单在"看不到 B 仓"的
     * 操作员手里会静默失败。写路径走 {@code tryReserve/consumeReserved}
     * 这些不带权限谓词的方法，由单据侧的权限码把关。
     */
    public Optional<InventoryBalance> find(long skuId, long warehouseId, String batchNo) {
        java.util.List<Object> args = new java.util.ArrayList<>(List.of(skuId, warehouseId, batchNo));
        String predicate = scope.warehouseOnly("warehouse_id", args);
        List<InventoryBalance> rows = jdbc.query(\"\"\"
                SELECT id, sku_id, warehouse_id, batch_no, expiry_date,
                       on_hand_qty, reserved_qty, available_qty, version
                  FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                \"\"\" + predicate, MAPPER, args.toArray());
        return rows.stream().findFirst();
    }"""
assert old_find in s
s=s.replace(old_find,new_find,1)
open(p,'w',encoding='utf-8').write(s)
print("patched balance repo")
