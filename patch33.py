p='backend/server/src/main/java/com/erp/shared/scope/DataScope.java'
s=open(p,encoding='utf-8').read()
old="""            args.addAll(warehouseIds);
            return " AND " + warehouseColumn + " = ANY (?)";"""
new="""            // ★ 用 IN (?, ?, ...) 而不是 = ANY (?)：
            //   JdbcTemplate 把 Java 的 List 当成**一个参数**传给 PreparedStatement，
            //   而 PG 驱动只有在参数本身是 java.sql.Array 时才能映射成 BIGINT[]。
            //   写成 ANY(?) 的后果是运行期报
            //   "cannot cast type bigint to bigint[]"（实测为 500，不是编译错误）。
            //   IN 列表是按集合长度动态生成占位符，驱动无需理解数组类型。
            args.addAll(warehouseIds);
            String placeholders = String.join(", ", java.util.Collections.nCopies(
                    warehouseIds.size(), "?"));
            return " AND " + warehouseColumn + " IN (" + placeholders + ")";"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("DataScope IN-list fixed")

# fix test expectation TC-IAM-041
p='backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java'
s=open(p,encoding='utf-8').read()
s=s.replace("""        assertThat(some.warehousePredicate("warehouse_id", args2))
                .isEqualTo(" AND warehouse_id = ANY (?");""",
"""        assertThat(some.warehousePredicate("warehouse_id", args2))
                .isEqualTo(" AND warehouse_id IN (?, ?)");""")
open(p,'w',encoding='utf-8').write(s)
print("test expectation updated")
