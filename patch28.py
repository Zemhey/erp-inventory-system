p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
import re
m=re.search(r'        long writerId = roles\.insert\("APS_SUBMITTER"[^;]*;\n', s)
assert m, "role insert line not found"
old=m.group(0)
new="""        // 角色是**配置**（不在 truncateAll 的清单里），所以按"取出或新建"处理：
        // 直接用 insert 会在第二个用例时撞 uq_app_role_code。
        long writerId = roles.findByCode("APS_SUBMITTER")
                .map(RoleRepository.RoleRow::id)
                .orElseGet(() -> roles.insert("APS_SUBMITTER", "仅可发起审批", "测试用角色"));
"""
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("ApprovalIT role reuse fixed")
