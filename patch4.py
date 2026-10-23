p='backend/server/src/main/java/com/erp/sal/repository/SalesOrderRepository.java'
s=open(p,encoding='utf-8').read()

old_ctor="""    private final JdbcTemplate jdbc;

    public SalesOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }"""
new_ctor="""    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）：仓库 + 本人。
     *
     * <p>过滤注入在仓储而不是 Controller —— 分页/详情/导出/关联/统计五条取数路径
     * 只要有一个出口，就不会漏改（越权矩阵正是按这五种方式抽查）。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public SalesOrderRepository(JdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }"""
assert old_ctor in s
s=s.replace(old_ctor,new_ctor,1)

old_ins="""    public long insertHeader(String orderNo, long customerId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId) {"""
new_ins="""    /** 插入主表。{@code createdBy} 供数据权限"按本人"判定，系统任务可为 null。 */
    public long insertHeader(String orderNo, long customerId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId, Long createdBy) {"""
assert old_ins in s
s=s.replace(old_ins,new_ins,1)

old_sql="""                    INSERT INTO sales_orders
                        (order_no, status, customer_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, remark, request_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)
                    \"\"\", new String[]{"id"});"""
new_sql="""                    INSERT INTO sales_orders
                        (order_no, status, customer_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, remark, request_id, created_by)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    \"\"\", new String[]{"id"});"""
assert old_sql in s, "sales insert sql not found"
s=s.replace(old_sql,new_sql,1)

old_set="""            ps.setString(9, requestId);
            return ps;"""
new_set="""            ps.setString(9, requestId);
            if (createdBy == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, createdBy);
            }
            return ps;"""
assert old_set in s
s=s.replace(old_set,new_set,1)
open(p,'w',encoding='utf-8').write(s)
print("patched SO repo header+ctor")
