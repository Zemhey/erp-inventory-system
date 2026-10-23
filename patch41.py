p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=open(p,encoding='utf-8').read()
old="""        jdbc.update("UPDATE approval_flows SET enabled = FALSE");"""
new="""        // ★★ 角色与权限也是**配置**（V3/V5/V7 种子），同样不能 truncate；
        //   但用例会改它们（"撤掉 pur:approve""给角色加 mdm:write"），
        //   若不复位，下一条用例就继承了上一条的改坏状态 ——
        //   症状是"单独跑绿、整套跑红"（实测：TC-IAM-033 之后所有 403 断言全反）。
        //   这里把种子恢复成迁移里的初始授权（与 V3+V5+V7 的三段 INSERT 一致）。
        restoreSeedRolePermissions();
        jdbc.update("UPDATE approval_flows SET enabled = FALSE");"""
assert old in s
s=s.replace(old,new,1)

# append helper before final closing brace
anchor="""    /**
     * 建测试账号并赋予角色。"""
helper="""    /**
     * 把四个种子角色的权限恢复成 V3/V5/V7 的初始授权。
     *
     * <p><b>为什么必须显式恢复而不是"反正用例会自己重建"</b>：
     * 角色是<b>配置</b>而不是业务数据，{@code truncateAll} 不碰它们；
     * 而权限测试必然会改角色权限（撤权、加权限、覆盖式保存）。
     * 只要有一条用例改了不改回来，后续所有 RBAC 断言就会静默反向 ——
     * 那类失败最难查，因为它取决于用例执行顺序。
     *
     * <p>清单与迁移逐条对应：{@code V3__iam.sql}（mdm/pur/sal/inv）、
     * {@code V5__settlement.sql}（set/rpt + FINANCE）、
     * {@code V7__finance_mdm_read.sql}（FINANCE 的 mdm:read）、
     * {@code V14__iam_deepening_and_approval.sql}（iam:*/aps:*）。
     */
    public void restoreSeedRolePermissions() {
        jdbc.update("DELETE FROM app_role_permissions");
        jdbc.update(\"\"\"
                INSERT INTO app_role_permissions (role_id, permission)
                SELECT r.id, p.permission FROM app_roles r
                JOIN (VALUES
                    ('ADMIN','mdm:*'),('ADMIN','pur:*'),('ADMIN','sal:*'),('ADMIN','inv:*'),
                    ('ADMIN','rma:*'),('ADMIN','set:*'),('ADMIN','rpt:*'),('ADMIN','nl:*'),
                    ('ADMIN','iam:*'),('ADMIN','aps:*'),
                    ('PURCHASER','mdm:read'),('PURCHASER','pur:create'),('PURCHASER','pur:submit'),
                    ('PURCHASER','pur:approve'),('PURCHASER','pur:receive'),('PURCHASER','pur:void'),
                    ('PURCHASER','inv:read'),('PURCHASER','pur:read'),
                    ('PURCHASER','aps:submit'),('PURCHASER','aps:read'),('PURCHASER','iam:read'),
                    ('SALESPERSON','mdm:read'),('SALESPERSON','sal:create'),('SALESPERSON','sal:submit'),
                    ('SALESPERSON','sal:ship'),('SALESPERSON','sal:approve'),('SALESPERSON','sal:void'),
                    ('SALESPERSON','sal:read'),('SALESPERSON','inv:read'),
                    ('SALESPERSON','aps:submit'),('SALESPERSON','aps:read'),('SALESPERSON','iam:read'),
                    ('VIEWER','mdm:read'),('VIEWER','inv:read'),('VIEWER','pur:read'),
                    ('VIEWER','sal:read'),('VIEWER','aps:read'),('VIEWER','iam:read'),
                    ('FINANCE','mdm:read'),('FINANCE','set:read'),('FINANCE','set:daily'),
                    ('FINANCE','set:monthly'),('FINANCE','set:reconcile'),('FINANCE','rpt:read'),
                    ('FINANCE','rma:read'),('FINANCE','aps:read'),('FINANCE','aps:approve'),
                    ('FINANCE','iam:read')
                ) AS p(role_code, permission) ON r.code = p.role_code
                \"\"\");
        // 用例临时建的角色（不在种子里的）随用户一起被清掉即可，但为了避免
        // uq_app_role_code 冲突，这里把非种子角色也一并删除。
        jdbc.update("DELETE FROM app_roles WHERE code NOT IN "
                + "('ADMIN','PURCHASER','SALESPERSON','VIEWER','FINANCE')");
    }

    /**
     * 建测试账号并赋予角色。"""
assert anchor in s
s=s.replace(anchor,helper,1)
open(p,'w',encoding='utf-8').write(s)
print("restoreSeedRolePermissions added")
