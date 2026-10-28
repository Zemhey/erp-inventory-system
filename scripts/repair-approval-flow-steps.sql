-- ════════════════════════════════════════════════════════════════════════════
-- 修复：恢复三条审批流的「节点定义」（instance_id IS NULL）
--
-- 背景：早期版本的 scripts/reset-demo-data.sql 直接 TRUNCATE approval_steps，
--       而这张表同时存两类行：
--         instance_id IS NULL     → 流程节点定义（配置，属于"系统本身"）
--         instance_id IS NOT NULL → 某张单据的实例节点（业务数据）
--       清表把定义也删了，于是审批流"已启用却没有节点"：
--       任何单据 approve 都报 APS_APPROVAL_REQUIRED，而单据/主数据表全正常。
--
-- 本脚本与 V23__aps_flow_steps.sql 的定义逐字一致（step_no / 名称 / 权限 / 阈值），
-- 幂等：已存在的节点不会重复插入。可安全重复执行。
--
-- 用法：
--   Get-Content scripts/repair-approval-flow-steps.sql -Raw |
--     docker exec -i erp-postgres psql -U erp -d erp -f -
-- ════════════════════════════════════════════════════════════════════════════

BEGIN;

INSERT INTO approval_steps
    (flow_id, instance_id, step_no, name, approver_permission, approve_mode,
     required_approvals, threshold_amount, status, tenant_id)
SELECT f.id,
       NULL,
       s.step_no,
       s.step_name,
       s.approver_permission,
       s.approve_mode,
       s.required_approvals,
       s.threshold_amount,
       'DEFINED',
       f.tenant_id
  FROM approval_flows f
  JOIN (VALUES
        -- 进货单：采购主管先批；超 5000 元追加财务复核金额
        ('PO-STD', 1, '采购主管审批',           'pur:approve',  'SINGLE', 1, 0),
        ('PO-STD', 2, '财务审批（金额超阈值）', 'set:monthly',  'SINGLE', 1, 500000),
        -- 出货单：销售主管先批；超 5000 元追加财务复核（信用额度/回款）
        ('SO-STD', 1, '销售主管审批',           'sal:approve',  'SINGLE', 1, 0),
        ('SO-STD', 2, '财务审批（金额超阈值）', 'set:monthly',  'SINGLE', 1, 500000),
        -- 退货单：客服主管先批；超 5000 元退款追加财务
        ('RO-STD', 1, '客服主管审批',           'rma:approve',  'SINGLE', 1, 0),
        ('RO-STD', 2, '财务审批（金额超阈值）', 'set:monthly',  'SINGLE', 1, 500000)
      ) AS s(flow_code, step_no, step_name, approver_permission,
             approve_mode, required_approvals, threshold_amount)
    ON f.code = s.flow_code
 WHERE NOT EXISTS (
       SELECT 1
         FROM approval_steps x
        WHERE x.flow_id = f.id
          AND x.instance_id IS NULL
          AND x.step_no = s.step_no
 );

COMMIT;

-- 自检：每条启用中的流都必须有首节点（阈值 0），否则它就是"启用但空转"的坏流程
SELECT f.tenant_id, f.code, f.biz_type, f.enabled,
       COUNT(s.id) FILTER (WHERE s.instance_id IS NULL) AS defined_steps
  FROM approval_flows f
  LEFT JOIN approval_steps s ON s.flow_id = f.id
 GROUP BY f.tenant_id, f.code, f.biz_type, f.enabled
 ORDER BY f.tenant_id, f.code;
