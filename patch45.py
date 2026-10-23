p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=open(p,encoding='utf-8').read()

old="""        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
        jdbc.update("DELETE FROM approval_instances");
    }"""
new="""        // 用例自建的流程（如会签/或签）不在种子里，删掉；种子流程保留
        jdbc.update("DELETE FROM approval_flows WHERE code NOT IN ('PO-STD','SO-STD','RO-STD')");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
        jdbc.update("DELETE FROM approval_instances");
        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        ensureApprovalSeed();
    }

    /**
     * 修复/补齐审批流种子（幂等）。
     *
     * <p><b>★ 为什么需要"修复"而不只是"假设种子在"</b>：
     * 测试容器的 PostgreSQL 是 <b>{@code withReuse(true)}</b>（含 {@code PostgresTestBase}
     * 的复用配置），同一台容器会被本机反复的 {@code gradlew test} 共用。
     * 早先版本的 {@code truncateAll} 里写过 {@code TRUNCATE ... approval_instances CASCADE}，
     * 而 CASCADE 会连着清空引用它的 {@code approval_steps} —— <b>连定义节点一起没了</b>。
     * 那一次破坏留在了复用容器里，之后每次跑都报
     * "审批流 PO-STD 在金额 50000 下没有任何可执行节点"（看起来像代码 bug，其实是脏库）。
     *
     * <p>因此这里做两件事：① 缺 flow 就补 flow；② 缺定义节点就补定义节点。
     * 这个修复本身也是幂等的，正常干净的库上不会产生任何改动。
     */
    public void ensureApprovalSeed() {
        jdbc.update(\"\"\"
                INSERT INTO approval_flows (code, name, biz_type, description, enabled)
                VALUES ('PO-STD','进货单标准审批','PURCHASE_ORDER','采购主管 → （金额超阈值）财务',FALSE),
                       ('SO-STD','出货单标准审批','SALES_ORDER','销售主管 → （金额超阈值）财务',FALSE),
                       ('RO-STD','退货单标准审批','RETURN_ORDER','客服主管 → （金额超阈值）财务',FALSE)
                ON CONFLICT (code) DO NOTHING
                \"\"\");
        jdbc.update(\"\"\"
                INSERT INTO approval_steps
                    (flow_id, instance_id, step_no, name, approver_permission, approve_mode,
                     required_approvals, threshold_amount)
                SELECT f.id, NULL, s.step_no, s.name, s.permission, 'SINGLE', 1, s.threshold
                  FROM approval_flows f
                  JOIN (VALUES
                    ('PO-STD',1,'采购主管审批','pur:approve',0),
                    ('PO-STD',2,'财务审批（金额超阈值）','set:monthly',500000),
                    ('SO-STD',1,'销售主管审批','sal:approve',0),
                    ('SO-STD',2,'财务审批（金额超阈值）','set:monthly',500000),
                    ('RO-STD',1,'客服主管审批','rma:approve',0),
                    ('RO-STD',2,'财务审批（金额超阈值）','set:monthly',500000)
                  ) AS s(flow_code, step_no, name, permission, threshold)
                    ON f.code = s.flow_code
                 WHERE NOT EXISTS (
                    SELECT 1 FROM approval_steps d
                     WHERE d.flow_id = f.id AND d.instance_id IS NULL
                       AND d.step_no = s.step_no)
                \"\"\");
    }"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("ensureApprovalSeed added")
