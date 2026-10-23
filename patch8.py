p='backend/server/src/main/java/com/erp/rma/repository/ReturnOrderRepository.java'
s=open(p,encoding='utf-8').read()

old_ctor="""    public ReturnOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }"""
new_ctor="""    /** 数据权限（FR-IAM-04）：仓库 + 本人；过滤在仓储层，出口唯一。 */
    private final com.erp.shared.scope.ScopeContext scope;

    public ReturnOrderRepository(JdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }"""
assert old_ctor in s
s=s.replace(old_ctor,new_ctor,1)

old_ins="""    public long insertHeader(String orderNo, long originSalesOrderId, long customerId,
                             long warehouseId, LocalDate bizDate, long totalAmount, int totalQty,
                             String period, String remark, String requestId) {"""
new_ins="""    /** 插入主表。{@code createdBy} 供数据权限"按本人"判定，系统任务可为 null。 */
    public long insertHeader(String orderNo, long originSalesOrderId, long customerId,
                             long warehouseId, LocalDate bizDate, long totalAmount, int totalQty,
                             String period, String remark, String requestId, Long createdBy) {"""
assert old_ins in s
s=s.replace(old_ins,new_ins,1)

old_sql="""                    INSERT INTO return_orders
                        (order_no, status, origin_sales_order_id, customer_id, warehouse_id,
                         biz_date, total_amount, total_qty, period, remark, request_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
new_sql="""                    INSERT INTO return_orders
                        (order_no, status, origin_sales_order_id, customer_id, warehouse_id,
                         biz_date, total_amount, total_qty, period, remark, request_id, created_by)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
assert old_sql in s
s=s.replace(old_sql,new_sql,1)

old_set="""            ps.setString(10, requestId);
            return ps;"""
new_set="""            ps.setString(10, requestId);
            if (createdBy == null) {
                ps.setNull(11, java.sql.Types.BIGINT);
            } else {
                ps.setLong(11, createdBy);
            }
            return ps;"""
assert old_set in s
s=s.replace(old_set,new_set,1)

old_find="""    public Optional<ReturnOrder> findHeaderById(long id) {
        return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM return_orders WHERE id = ?",
                ORDER_MAPPER, id).stream().findFirst();
    }"""
new_find="""    /**
     * 查主表，<b>并施加数据权限</b>：越权 id 查不出行 → 上层 404（不是 403）。
     */
    public Optional<ReturnOrder> findHeaderById(long id) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        String predicate = scope.predicates("warehouse_id", "created_by", args);
        return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM return_orders WHERE id = ?"
                + predicate, ORDER_MAPPER, args.toArray()).stream().findFirst();
    }"""
assert old_find in s
s=s.replace(old_find,new_find,1)

old_page="""        if (originSalesOrderId != null) {
            where.append(" AND origin_sales_order_id = ?");
            args.add(originSalesOrderId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM return_orders" + where,"""
new_page="""        if (originSalesOrderId != null) {
            where.append(" AND origin_sales_order_id = ?");
            args.add(originSalesOrderId);
        }
        where.append(scope.predicates("warehouse_id", "created_by", args));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM return_orders" + where,"""
assert old_page in s
s=s.replace(old_page,new_page,1)
open(p,'w',encoding='utf-8').write(s)
print("patched RMA repo")
