p='backend/server/src/main/java/com/erp/iam/api/IamAdminController.java'
s=open(p,encoding='utf-8').read()
old="""    private static List<String> scopesToMaps(List<ScopeView> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (ScopeRow r : rows) {
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
print("fixed scopesToMaps")
