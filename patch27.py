p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=open(p,encoding='utf-8').read()
old="""                         approval_steps, approval_instances, approval_flows,
                         data_scopes,"""
new="""                         approval_steps, approval_instances,
                         data_scopes,"""
assert old in s
s=s.replace(old,new,1)

old2="""                RESTART IDENTITY CASCADE
                \"\"\");
    }"""
new2="""                RESTART IDENTITY CASCADE
                \"\"\");
        // ★ approval_flows 属于**配置**而不是业务数据，不能 truncate ——
        //   它由 V14 迁移种子给出，truncate 掉之后所有后续用例都会看不到流程。
        //   但"启用态"是可能被用例改坏的（同类型只允许一条启用流），
        //   所以在每个用例前统一复位成迁移里的初始状态（全部停用）。
        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
    }"""
assert old2 in s
s=s.replace(old2,new2,1)
open(p,'w',encoding='utf-8').write(s)
print("truncateAll corrected")

# ApprovalIT: reuse role instead of inserting duplicate
p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
old3="""        long writerId = roles.insert("APS_SUBMITTER", "仅可发起审批", "测试用角色");"""
# find actual line
import re
m=re.search(r'long writerId = roles\.insert\([^;]*;', s)
print("found:", m.group(0) if m else "NONE")
