p='backend/server/src/main/java/com/erp/sal/repository/SalesOrderRepository.java'
s=open(p,encoding='utf-8').read()

old_find="""    public Optional<SalesOrder> findHeaderById(long id) {
        return jdbc.query(\"\"\"
                SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM sales_orders WHERE id = ?
                \"\"\", ORDER_MAPPER, id).stream().findFirst();
    }"""
new_find="""    /**
     * 查主表，<b>并施加数据权限</b>。
     *
     * <p>越权的 id 直接查不出行 → 上层抛 {@code SAL_ORDER_NOT_FOUND}（<b>404 而非 403</b>）：
     * 403 会泄露"这个 id 存在"，攻击者据此遍历即可枚举全部单据 id。
     */
    public Optional<SalesOrder> findHeaderById(long id) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        String predicate = scope.predicates("warehouse_id", "created_by", args);
        return jdbc.query(\"\"\"
                SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM sales_orders WHERE id = ?
                \"\"\" + predicate, ORDER_MAPPER, args.toArray()).stream().findFirst();
    }"""
assert old_find in s
s=s.replace(old_find,new_find,1)

old_page_head="""    public PageResult<SalesOrder> page(DocumentStatus status, Long customerId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }"""
new_page_head="""    /**
     * 分页列表，<b>并施加数据权限</b>。
     *
     * <p>"列表注入"防线的关键：仓库过滤<b>不接受外部输入</b>，只认当前登录人的
     * 数据权限配置。攻击者把别仓的 warehouseId 塞进查询参数也不会影响这里。
     */
    public PageResult<SalesOrder> page(DocumentStatus status, Long customerId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        where.append(scope.predicates("warehouse_id", "created_by", args));"""
assert old_page_head in s
s=s.replace(old_page_head,new_page_head,1)
open(p,'w',encoding='utf-8').write(s)
print("patched SO repo find+page")
