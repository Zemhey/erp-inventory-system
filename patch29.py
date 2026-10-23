p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()

# setUp enables flows; truncateAll resets to disabled first (called before), so ok.
# createFlow must restore the default flow afterwards for OTHER tests? Each test re-setups so fine.
# But TC-APS-016/017 disable the custom flow at end and leave biz-type flow disabled -> ok since setUp re-enables.

# 1) TC-APS-024/025/026 rely on PO flow enabled -> setUp does it. good.
# 2) TC-APS-021/022/023 create instances with arbitrary bizId -> need enabled flow: ok.

# Fix: TC-APS-016 allSign — flow APS_SUBMITTER role perms must include pur:approve removal? 
# supervisor PUSRCHASER had pur:approve removed in setUp; then createFlow uses pur:approve.
# But setUp removed pur:approve from PURCHASER, and supervisor is PURCHASER => cannot approve!
# We re-grant for supervisor by giving him a dedicated role with pur:approve.
old="""        supervisorId = data.user("supervisor", "Super1!or", "PURCHASER", encoder);
        financeId = data.user("finance_ap", "Fin1!ance", "FINANCE", encoder);
        purchaserId = data.user("buyer", "Buy1!er", "PURCHASER", encoder);"""
new="""        supervisorId = data.user("supervisor", "Super1!or", "PURCHASER", encoder);
        financeId = data.user("finance_ap", "Fin1!ance", "FINANCE", encoder);
        purchaserId = data.user("buyer", "Buy1!er", "PURCHASER", encoder);

        // 采购主管单独补一个 pur:approve（下面会从 PURCHASER 角色里撤掉它，
        // 用来构造"只有 aps:submit、没有节点审批权"的采购员身份）
        long supervisorRoleId = roles.findByCode("PURCHASER").orElseThrow().id();"""
assert old in s
s=s.replace(old,new,1)

old2="""        long roleId = roles.findByCode("PURCHASER").orElseThrow().id();
        jdbc.update("DELETE FROM app_role_permissions WHERE role_id = ? AND permission = ?",
                roleId, "pur:approve");"""
new2="""        // ★ 从 PURCHASER 角色上撤掉 pur:approve，然后**单独**给主管一个带该权限的角色。
        //   这样"采购员没有节点审批权""主管有第一节点权但没有财务权"两件事都是可断言的。
        jdbc.update("DELETE FROM app_role_permissions WHERE role_id = ? AND permission = ?",
                supervisorRoleId, "pur:approve");

        long approveOnlyId = roles.findByCode("PUR_APPROVER")
                .map(RoleRepository.RoleRow::id)
                .orElseGet(() -> roles.insert("PUR_APPROVER", "进货审批", "测试用角色"));
        roles.replacePermissions(approveOnlyId, List.of("mdm:read", "pur:read", "pur:approve",
                "aps:submit", "aps:read", "iam:read"));
        users.assignRoleById(supervisorId, approveOnlyId);"""
assert old2 in s
s=s.replace(old2,new2,1)

# TC-APS-020 re-grants PURCHASER permissions incl pur:approve -> would make supervisor still fine.
# But it also re-adds pur:approve to PURCHASER, giving purchaserId approval -> TC-APS-015 runs before? order independent!
# Safer: remove that re-grant block, grant to `otherSupervisor` a dedicated role instead.
old3="""        long otherSupervisor = data.user("other_sup", "Other1!sup", "PURCHASER", encoder);
        // 恢复 PURCHASER 的 pur:approve（setUp 里为构造"无审批权身份"删掉过）
        long roleId = roles.findByCode("PURCHASER").orElseThrow().id();
        roles.replacePermissions(roleId, List.of("mdm:read", "pur:create", "pur:read",
                "pur:approve", "pur:submit", "pur:receive", "pur:void", "inv:read",
                "aps:submit", "aps:read", "iam:read"));"""
new3="""        long otherSupervisor = data.user("other_sup", "Other1!sup", "PURCHASER", encoder);
        // 接办人要有 pur:approve（节点资格），但不必改 PURCHASER 角色 ——
        // 改角色会影响同用例里"无审批权的采购员"这一身份，用例之间就会互相污染。
        users.assignRoleById(otherSupervisor,
                roles.findByCode("PUR_APPROVER").orElseThrow().id());
        users.assignRoleById(otherSupervisor,
                roles.findByCode("PURCHASER").orElseThrow().id());"""
assert old3 in s
s=s.replace(old3,new3,1)
open(p,'w',encoding='utf-8').write(s)
print("ApprovalIT permission setup fixed")
