p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()

# 1) enable seeded flows at setup; truncate must not clear flows but tests may disable them
old="""        cityPlaceholder"""
# add helper enableFlow + call in setUp after users created
old_setup_tail="""        users.clearRoles(purchaserId);
        users.assignRoleById(purchaserId, writerId);
    }"""
new_setup_tail="""        users.clearRoles(purchaserId);
        users.assignRoleById(purchaserId, writerId);

        // ★ 种子流程默认停用（V14 注释：引入审批不能改变存量行为）。
        //   本类要测审批，所以显式启用；同一单据类型只能有一条启用流，
        //   因此每条用例再用 createFlow 时会先停掉这里的默认流。
        jdbc.update("UPDATE approval_flows SET enabled = TRUE WHERE code IN "
                + "('PO-STD','SO-STD','RO-STD')");
    }"""
assert old_setup_tail in s
s=s.replace(old_setup_tail,new_setup_tail,1)

# 2) createFlow already disables same-biz enabled flows -> but must also re-enable after? tests handle.
# Make source-order safe: the RO-STD flow for RETURN_ORDER stays enabled.
open(p,'w',encoding='utf-8').write(s)
print("ApprovalIT setup updated")
