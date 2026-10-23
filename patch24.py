p='backend/server/src/main/resources/db/migration/V14__iam_deepening_and_approval.sql'
s=open(p,encoding='utf-8').read()

old="""-- ─────────────────────────── 种子审批流（开发/测试用） ───────────────────────────
--
-- 与 V3 的做法一致：**不种用户账号**，只种"配置类"数据。
-- 三条流程各自带一个阈值节点，用来演示「金额阈值触发上级」：
--   进货单：<= 50 万分（5000 元）只需采购主管；超过则追加财务审批。

INSERT INTO approval_flows (code, name, biz_type, description) VALUES
    ('PO-STD', '进货单标准审批', 'PURCHASE_ORDER', '采购主管 → （金额超阈值）财务'),
    ('SO-STD', '出货单标准审批', 'SALES_ORDER',    '销售主管 → （金额超阈值）财务'),
    ('RO-STD', '退货单标准审批', 'RETURN_ORDER',   '客服主管 → （金额超阈值）财务');"""
new="""-- ─────────────────────────── 种子审批流（开发/测试用，★★ 默认停用） ───────────────────────────
--
-- 与 V3 的做法一致：**不种用户账号**，只种"配置类"数据。
-- 三条流程各自带一个阈值节点，用来演示「金额阈值触发上级」：
--   进货单：<= 50 万分（5000 元）只需采购主管；超过则追加财务审批。
--
-- ★★ 为什么 `enabled` 一律为 FALSE：这是与"数据权限未配置 = 全量"完全同一个原则 ——
--   **引入一项新治理能力，不能默认改变存量系统的行为**。
--   若这里种成启用态，V1~V8 的 251 条存量用例（它们直接 create→submit→approve）
--   会在第一次 approve 就撞上 APS_APPROVAL_REQUIRED，主干立刻全红；
--   生产上更糟：升级当天所有单据突然都必须走审批，没人配过审批人。
--   启用是一条显式的运维动作（PUT /approvals/flows/{id}/enabled），
--   由运营在配好流程与审批人之后再打开。
--   这条性质的回归断言见 TC-APS-026「没有启用流程时单据审核照旧」。

INSERT INTO approval_flows (code, name, biz_type, description, enabled) VALUES
    ('PO-STD', '进货单标准审批', 'PURCHASE_ORDER', '采购主管 → （金额超阈值）财务', FALSE),
    ('SO-STD', '出货单标准审批', 'SALES_ORDER',    '销售主管 → （金额超阈值）财务', FALSE),
    ('RO-STD', '退货单标准审批', 'RETURN_ORDER',   '客服主管 → （金额超阈值）财务', FALSE);"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("seeded flows disabled")
