p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=open(p,encoding='utf-8').read()
old="""                         skus, products, warehouses, suppliers, customers,
                         approval_steps, approval_instances,
                         data_scopes,
                         app_user_roles, app_users
                RESTART IDENTITY CASCADE
                \"\"\");
        // ★ approval_flows 属于**配置**而不是业务数据，不能 truncate ——
        //   它由 V14 迁移种子给出，truncate 掉之后所有后续用例都会看不到流程。
        //   但"启用态"是可能被用例改坏的（同类型只允许一条启用流），
        //   所以在每个用例前统一复位成迁移里的初始状态（全部停用）。
        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
    }"""
new="""                         skus, products, warehouses, suppliers, customers,
                         data_scopes,
                         app_user_roles, app_users
                RESTART IDENTITY CASCADE
                \"\"\");
        // ★★ 审批的两张表<b>刻意不放进 TRUNCATE</b>：{@code approval_flows} 与
        //   {@code approval_steps} 的定义行是**配置**（由 V14 迁移种子给出），
        //   而 TRUNCATE 的 CASCADE 会把引用它的表整表清掉 ——
        //   TRUNCATE approval_instances CASCADE 会顺手清空 approval_steps，
        //   连"定义节点"（instance_id IS NULL）一起消失，于是后续用例看到
        //   "流程存在但没有任何节点"（实测：TC-APS-019 报"在金额 10000 下没有任何可执行节点"）。
        //
        //   正确做法是 DELETE：只清实例及其快照节点，定义节点自然保留。
        //   同时把"启用态"复位成迁移里的初始值（全部停用），
        //   因为部分唯一索引只允许同类型有一条启用流，用例改过之后必须还原。
        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
        jdbc.update("DELETE FROM approval_instances");
    }"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("truncateAll fixed: instances deleted not truncated")
