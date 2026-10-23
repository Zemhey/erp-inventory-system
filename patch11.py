p='backend/server/src/main/java/com/erp/inv/repository/InventoryLedgerRepository.java'
s=open(p,encoding='utf-8').read()
old="""    private final JdbcTemplate jdbc;

    public InventoryLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }"""
new="""    private final JdbcTemplate jdbc;

    /** 数据权限（FR-IAM-04）：流水只有仓库维度（它是单据产生的，不是人建的）。 */
    private final com.erp.shared.scope.ScopeContext scope;

    public InventoryLedgerRepository(JdbcTemplate jdbc,
                                     com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }"""
assert old in s
s=s.replace(old,new,1)

old_to="""        if (to != null) {
            where.append(" AND occurred_at <= ?");
            args.add(to);
        }

        Long total = jdbc.queryForObject("""
new_to="""        if (to != null) {
            where.append(" AND occurred_at <= ?");
            args.add(to);
        }
        // ★ 数据权限：流水是"关联查询/统计聚合"最常用的入口，收窄必须在这里也生效，
        //   否则前四个接口都挡住了、从流水反查却能看到别仓的每一笔进出。
        where.append(scope.warehouseOnly("warehouse_id", args));

        Long total = jdbc.queryForObject("""
assert old_to in s
s=s.replace(old_to,new_to,1)

old_count="""    public int countByBizRef(String bizRefNo) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_ref_no = ?",
                Integer.class, bizRefNo);
        return v == null ? 0 : v;
    }"""
new_count="""    /**
     * 按来源单号数流水条数（关联查询的一种）。
     *
     * <p>刻意<b>不</b>加数据权限：本方法只返回一个整数，用于
     * "这张单是否产生过库存变动"这类<b>安全不变量</b>校验
     * （作废前置检查、幂等判定）。把权限塞进不变量校验会让
     * "看不到 B 仓的人以为 B 仓的单没入库" —— 那是个正确性 bug，不是权限问题。
     * 面向用户的流水条数走 {@link #page}，那里有权限谓词。
     */
    public int countByBizRef(String bizRefNo) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_ref_no = ?",
                Integer.class, bizRefNo);
        return v == null ? 0 : v;
    }"""
assert old_count in s
s=s.replace(old_count,new_count,1)
open(p,'w',encoding='utf-8').write(s)
print("patched ledger repo")
