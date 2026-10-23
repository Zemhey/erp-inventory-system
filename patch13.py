p='backend/server/src/main/java/com/erp/rpt/repository/ReportRepository.java'
s=open(p,encoding='utf-8').read()

s=s.replace("""        return documentSql(Source.PURCHASE, from, to, groupBy, warehouseId);""",
"""        return documentSql(Source.PURCHASE, from, to, groupBy, warehouseId,
                scope.current());""",1)
s=s.replace("""        return documentSql(Source.SALES, from, to, groupBy, warehouseId);""",
"""        return documentSql(Source.SALES, from, to, groupBy, warehouseId,
                scope.current());""",1)

s=s.replace("""    private static Sql documentSql(Source src, LocalDate from, LocalDate to,
                                   ReportGroupBy groupBy, Long warehouseId) {""",
"""    /**
     * @param dataScope 数据权限。<b>由调用方传入而不是在这里取</b>：
     *                  本方法是静态的（要能被执行计划用例直接调用），
     *                  在静态方法里读实例字段等于绕过构造注入。
     */
    private static Sql documentSql(Source src, LocalDate from, LocalDate to,
                                   ReportGroupBy groupBy, Long warehouseId,
                                   com.erp.shared.scope.DataScope dataScope) {""",1)

s=s.replace("""        sql.append(scope.current().warehousePredicate("o.warehouse_id", args)).append('\\n');""",
"""        sql.append(dataScope.warehousePredicate("o.warehouse_id", args)).append('\\n');""",1)
open(p,'w',encoding='utf-8').write(s)
print("fixed report static scope")
