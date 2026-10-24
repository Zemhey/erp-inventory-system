import io
p='backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java'
s=io.open(p,encoding='utf-8').read()
s=s.replace('''        assertThat(some.warehousePredicate("warehouse_id", args2))
                .isEqualTo(" AND warehouse_id = ANY (?)");''',
'''        // ★ 用 IN (?, ?, ...) 而不是 = ANY(?)：JdbcTemplate 会把 Java List 当成
        //   一个参数，而 PG 驱动只有在参数本身是 java.sql.Array 时才能映射成 BIGINT[]。
        //   写成 ANY(?) 的后果是运行期 "cannot cast type bigint to bigint[]"（实测 500）。
        assertThat(some.warehousePredicate("warehouse_id", args2))
                .isEqualTo(" AND warehouse_id IN (?, ?)");''',1)
io.open(p,'w',encoding='utf-8').write(s)
print("ANY->IN assertion updated")
