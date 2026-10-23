p='backend/server/src/test/java/com/erp/api/ApiContractMappingIT.java'
s=open(p,encoding='utf-8').read()
old="""        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/{}/reject", "出货工作流，契约待补");
    }"""
new="""        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/{}/reject", "出货工作流，契约待补");

        // ── V2-6 权限深化与审批流 ──
        // 本泳道**不是契约 owner**（任务书纪律 7）：需要的新端点一律先登记在这里，
        // 并同时提交一份《契约变更申请单》给契约 owner。契约补齐后这些行必须删除
        // （本类的第 ③ 段会反向校验清单是否过期）。
        // 认证：改密 + 登录响应三字段（FR-IAM-06）
        UNDOCUMENTED_DEBT.put("POST /api/v1/auth/change-password", "V2-6 契约变更申请 · FR-IAM-06");
        // 用户与角色管理（FR-IAM-05）
        UNDOCUMENTED_DEBT.put("GET /api/v1/iam/users", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("GET /api/v1/iam/users/{}", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("POST /api/v1/iam/users", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/iam/users/{}/roles", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("POST /api/v1/iam/users/{}/enabled", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("POST /api/v1/iam/users/{}/reset-password", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("GET /api/v1/iam/roles", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("POST /api/v1/iam/roles", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("GET /api/v1/iam/roles/{}/permissions", "V2-6 契约变更申请 · FR-IAM-05");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/iam/roles/{}/permissions", "V2-6 契约变更申请 · FR-IAM-05");
        // 数据权限（FR-IAM-04）
        UNDOCUMENTED_DEBT.put("GET /api/v1/iam/users/{}/data-scopes", "V2-6 契约变更申请 · FR-IAM-04");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/iam/users/{}/data-scopes", "V2-6 契约变更申请 · FR-IAM-04");
        UNDOCUMENTED_DEBT.put("GET /api/v1/iam/roles/{}/data-scopes", "V2-6 契约变更申请 · FR-IAM-04");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/iam/roles/{}/data-scopes", "V2-6 契约变更申请 · FR-IAM-04");
        // 审批流（FR-APS-01/02、FR-IAM-07）
        UNDOCUMENTED_DEBT.put("GET /api/v1/approvals", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("GET /api/v1/approvals/{}", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals/{}/approve", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals/{}/reject", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals/{}/withdraw", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals/{}/transfer", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals/{}/accept-transfer", "V2-6 契约变更申请 · FR-APS-02");
        UNDOCUMENTED_DEBT.put("GET /api/v1/approvals/flows", "V2-6 契约变更申请 · FR-APS-01");
        UNDOCUMENTED_DEBT.put("POST /api/v1/approvals/flows/{}/enabled", "V2-6 契约变更申请 · FR-APS-01");
    }"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("contract debt registered")
