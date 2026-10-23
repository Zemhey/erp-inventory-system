p='backend/server/src/main/java/com/erp/iam/application/DataScopeAdminService.java'
s=open(p,encoding='utf-8').read()

# 在 ParsedScope 附近加 ScopeView + toViews
old="""    private record ParsedScope(String scopeType, Long scopeValue) {
    }"""
new="""    private record ParsedScope(String scopeType, Long scopeValue) {
    }

    /**
     * 一条范围配置的<b>应用层视图</b>。
     *
     * <p>对外用与写入同形的字符串（{@code "ALL"} / {@code "SELF"} /
     * {@code "WAREHOUSE:12"}）：读写对称，前端不必写两套映射，
     * 也让"配置写成什么样、读回来就是什么样"成为可断言的性质。
     *
     * <p>为什么不直接返回仓储的 {@code ScopeRow}：Controller 不允许依赖
     * {@code ..repository..} 里的类型（ArchUnit「控制器不得直连仓储」）。
     */
    public record ScopeView(String scopeType, Long scopeValue) {

        /** 与写入同形的字符串形式。 */
        public String token() {
            return scopeValue == null ? scopeType : scopeType + ":" + scopeValue;
        }
    }

    private static List<ScopeView> toViews(List<DataScopeRepository.ScopeRow> rows) {
        return rows.stream()
                .map(r -> new ScopeView(r.scopeType(), r.scopeValue()))
                .toList();
    }"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("ScopeView added")

p='backend/server/src/main/java/com/erp/iam/api/IamAdminController.java'
s=open(p,encoding='utf-8').read()
old="""    private static List<String> scopesToMaps(List<ScopeView> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (ScopeView r : rows) {
            out.add(r.scopeValue() == null
                    ? r.scopeType()
                    : r.scopeType() + ":" + r.scopeValue());
        }
        return out;
    }"""
new="""    private static List<String> scopesToMaps(List<DataScopeAdminService.ScopeView> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (DataScopeAdminService.ScopeView r : rows) {
            out.add(r.token());
        }
        return out;
    }"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("controller scopesToMaps fixed")
