p='backend/server/src/main/java/com/erp/rpt/repository/ReportRepository.java'
s=open(p,encoding='utf-8').read()
old="""    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }"""
new="""    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）。
     *
     * <p><b>★ "统计聚合"越权方式的防线</b>：这是五种越权方式里最隐蔽的一种 ——
     * 攻击者不查明细、只查"本月出货总额"，而汇总里混进了别仓的数字。
     * 页面看不出异常（数字就是个数字），但数据已经泄露。
     * 因此仓库收窄必须同时进入<b>三条</b>聚合 SQL（进货/出货/进销存）。
     *
     * <p>报表是只读的，所以这里只需要仓库维度（没有"谁建的"这回事：
     * 统计的对象是单据事实，不是操作行为）。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public ReportRepository(JdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }"""
assert old in s
s=s.replace(old,new,1)

# purchase/sales document SQL: apply scope before optional warehouseId
old_doc="""        if (warehouseId != null) {
            sql.append("   AND o.warehouse_id = ?\\n");
            args.add(warehouseId);
        }
        sql.append(" GROUP BY ").append(g.groupByExpr()).append('\\n');"""
new_doc="""        if (warehouseId != null) {
            sql.append("   AND o.warehouse_id = ?\\n");
            args.add(warehouseId);
        }
        // ★ 数据权限：与用户给的 warehouseId 是"与"关系，不会互相覆盖 ——
        //   传一个别仓的 warehouseId 只会得到空结果，而不是别人的数据。
        sql.append(scope.current().warehousePredicate("o.warehouse_id", args)).append('\\n');
        sql.append(" GROUP BY ").append(g.groupByExpr()).append('\\n');"""
assert old_doc in s
s=s.replace(old_doc,new_doc,1)

old_sum="""        if (warehouseId != null) {
            sql.append("   AND l.warehouse_id = ?\\n");
            args.add(warehouseId);
        }
        sql.append(" GROUP BY l.sku_id, l.warehouse_id\\n");"""
new_sum="""        if (warehouseId != null) {
            sql.append("   AND l.warehouse_id = ?\\n");
            args.add(warehouseId);
        }
        // ★ 数据权限：进销存汇总同样要收窄，否则"合计"会把别仓的进出算进来
        sql.append(scope.current().warehousePredicate("l.warehouse_id", args)).append('\\n');
        sql.append(" GROUP BY l.sku_id, l.warehouse_id\\n");"""
assert old_sum in s
s=s.replace(old_sum,new_sum,1)
open(p,'w',encoding='utf-8').write(s)
print("patched report repo")
