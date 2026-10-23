# 4) IamAdminController must not depend on repository types -> introduce application-layer view records
p='backend/server/src/main/java/com/erp/iam/application/UserAdminService.java'
s=open(p,encoding='utf-8').read()
old="""    @Transactional(readOnly = true)
    public List<RoleRepository.RoleRow> listRoles() {
        return roles.findAll();
    }"""
new="""    /**
     * 角色清单（返回<b>应用层视图</ *不返回仓储类型</b>）。
     *
     * <p>为什么不让 Controller 直接用 {@code RoleRepository.RoleRow}：
     * ArchUnit 的「控制器不得直连仓储」规则禁止 Controller 依赖 {@code ..repository..}
     * 里的任何类型 —— 包括这种只有四个字段的行记录。宽松地放行"只是数据结构"
     * 的口子，下一步就会有人 import 整个 Repository。
     */
    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAll().stream()
                .map(r -> new RoleView(r.id(), r.code(), r.name(), r.description()))
                .toList();
    }

    /** 角色视图（契约 {@code Role}）。 */
    public record RoleView(long id, String code, String name, String description) {
    }"""
assert old in s
s=s.replace(old,new,1)

old2="""    @Transactional(readOnly = true)
    public RoleRepository.RoleRow requireRole(long roleId) {
        return roles.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_ROLE_NOT_FOUND,
                        "角色不存在：id=%d".formatted(roleId)));
    }"""
new2="""    @Transactional(readOnly = true)
    public RoleView requireRole(long roleId) {
        var row = roles.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_ROLE_NOT_FOUND,
                        "角色不存在：id=%d".formatted(roleId)));
        return new RoleView(row.id(), row.code(), row.name(), row.description());
    }"""
assert old2 in s
s=s.replace(old2,new2,1)
open(p,'w',encoding='utf-8').write(s)
print("4a) UserAdminService RoleView added")

# 4b) IamAdminController: use RoleView, drop repository imports
p='backend/server/src/main/java/com/erp/iam/api/IamAdminController.java'
s=open(p,encoding='utf-8').read()
s=s.replace("import com.erp.iam.repository.DataScopeRepository.ScopeRow;\n","")
s=s.replace("import com.erp.iam.repository.RoleRepository;\n","")
s=s.replace("for (RoleRepository.RoleRow r : users.listRoles()) {","for (UserAdminService.RoleView r : users.listRoles()) {")
s=s.replace("private static Map<String, Object> toRoleMap(RoleRepository.RoleRow r) {",
            "private static Map<String, Object> toRoleMap(UserAdminService.RoleView r) {")
s=s.replace("private static List<String> scopesToMaps(List<ScopeRow> rows) {",
            "private static List<String> scopesToMaps(List<ScopeView> rows) {")
# DataScopeAdminService must return its own view record
open(p,'w',encoding='utf-8').write(s)
print("4b) IamAdminController updated")

# 4c) DataScopeAdminService: own ScopeView record, no repository import leakage
p='backend/server/src/main/java/com/erp/iam/application/DataScopeAdminService.java'
s=open(p,encoding='utf-8').read()
s=s.replace("import com.erp.iam.repository.DataScopeRepository.ScopeRow;\n","")
s=s.replace("import com.erp.iam.repository.RoleRepository;\n","")
s=s.replace("import com.erp.iam.repository.DataScopeRepository;\n","import com.erp.iam.repository.DataScopeRepository;\n")
s=s.replace("public List<ScopeRow> scopesOfUser(long userId) {","public List<ScopeView> scopesOfUser(long userId) {")
s=s.replace("public List<ScopeRow> directScopesOfUser(long userId) {","public List<ScopeView> directScopesOfUser(long userId) {")
s=s.replace("public List<ScopeRow> inheritedScopesOfUser(long userId) {","public List<ScopeView> inheritedScopesOfUser(long userId) {")
s=s.replace("public List<ScopeRow> scopesOfRole(long roleId) {","public List<ScopeView> scopesOfRole(long roleId) {")
s=s.replace("return scopes.rowsForUser(userId);","return toViews(scopes.rowsForUser(userId));")
s=s.replace("return scopes.directRowsForUser(userId);","return toViews(scopes.directRowsForUser(userId));")
s=s.replace("return scopes.inheritedRowsForUser(userId);","return toViews(scopes.inheritedRowsForUser(userId));")
s=s.replace("return scopes.rowsForRole(roleId);","return toViews(scopes.rowsForRole(roleId));")
open(p,'w',encoding='utf-8').write(s)
print("4c) DataScopeAdminService updated")
