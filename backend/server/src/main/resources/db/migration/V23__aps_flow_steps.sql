-- ============================================================================
-- V23：给 V14 的三条审批流补齐真实的审批节点（缺陷修复）
--
-- 【背景：这不是"加功能"，是补一个已复现的砖】
--   V14 同时写了 approval_flows 的头（3 条）和 approval_steps 的节点（6 行）。
--   头种成功了，节点一行都没有 —— 现网库里 approval_steps 是 0 行。
--   后果（主控已实测复现）：一旦有人把 PO-STD 的 enabled 打开，
--     ① ApprovalApplicationService.submit() 取到 flow 后 flow.steps() 为空，
--        实例插入 → 快照 0 个节点 → firstActive == null → 整个事务回滚，
--        approval_instances 一条都不留（GET /approvals total=0）；
--     ② 单据已经 SUBMITTED，闸门 requireApproved() 只看"有没有实例"，
--        于是报 APS_APPROVAL_REQUIRED「需要审批但尚未提交」—— 语义完全误导；
--     ③ 单据永久卡在 SUBMITTED，进货→入库→自动生成凭证整条链停摆。
--   关流能止血，但功能等于不存在（审批工作台/待办中心的"待审批"永远是 0）。
--   V14 在 flyway_schema_history 里是 success=t，永远不会重跑；
--   改 V14 又会让 checksum 变化导致校验失败 —— 所以修复只能是追加 V23。
--
-- 【列名以 \d 实测为准，不凭猜】（本仓库已两次栽在猜列名上）
--   approval_flows (id, code varchar(32), name varchar(64), biz_type varchar(32),
--                   description varchar(200), enabled boolean NOT NULL DEFAULT true,
--                   created_at/updated_at timestamptz, tenant_id bigint NOT NULL)
--     CK biz_type IN (PURCHASE_ORDER, SALES_ORDER, RETURN_ORDER)
--     UQ (tenant_id, code)                      ← V16 把单列 code 唯一改成了带租户的
--     UQ (tenant_id, biz_type) WHERE enabled    ← 每种单据只允许一条启用流
--     TRG trg_approval_flows_tenant_fill（tenant_id 为 NULL 时按会话租户填充）
--
--   approval_steps (id, flow_id BIGINT NOT NULL →approval_flows ON DELETE CASCADE,
--                   instance_id BIGINT NULL →approval_instances ON DELETE CASCADE,
--                   step_no int NOT NULL, name varchar(64) NOT NULL,
--                   approver_permission varchar(64) NOT NULL,
--                   approve_mode varchar(16) NOT NULL, required_approvals int NOT NULL DEFAULT 1,
--                   threshold_amount bigint NOT NULL DEFAULT 0,
--                   status varchar(16) NOT NULL DEFAULT 'DEFINED',
--                   approved_by bigint[] NOT NULL DEFAULT '{}',
--                   decided_at timestamptz NULL, decision_by bigint NULL,
--                   remark varchar(200), created_at/updated_at timestamptz,
--                   tenant_id bigint NOT NULL)
--     CK approve_mode IN (SINGLE, ALL_SIGN, ANY_SIGN)
--     CK required_approvals > 0
--     CK threshold_amount >= 0
--     CK step_no > 0
--     CK status IN (DEFINED, PENDING, APPROVED, REJECTED, SKIPPED)
--     ck_approval_step_form: 定义行必须 instance_id IS NULL AND status='DEFINED'
--                            AND decided_at IS NULL AND decision_by IS NULL
--                            AND approved_by='{}'；实例行必须 status<>'DEFINED'
--     UQ (flow_id, step_no) WHERE instance_id IS NULL   ← 幂等的天然键
--     UQ (instance_id, step_no) WHERE instance_id IS NOT NULL
--     ★ 注意：**没有** (flow_id, name) 之类的唯一键，所以幂等只能按 (flow_id, step_no)。
--
--   approval_instances (id, flow_id NOT NULL →approval_flows, biz_type varchar(32) NOT NULL,
--                       biz_id bigint NOT NULL, biz_ref_no varchar(64) NOT NULL,
--                       amount bigint NOT NULL DEFAULT 0 CHECK(>=0),
--                       current_step int NOT NULL DEFAULT 1 CHECK(>0),
--                       status varchar(16) NOT NULL DEFAULT 'DRAFT',
--                       initiator_id bigint NOT NULL →app_users,
--                       request_id varchar(64) NULL, version int NOT NULL DEFAULT 0,
--                       created_at/updated_at timestamptz, tenant_id bigint NOT NULL)
--     CK status IN (DRAFT, PENDING, TRANSFERRED, APPROVED, REJECTED, WITHDRAWN, CANCELLED)
--     UQ (biz_type, biz_id) WHERE status IN (DRAFT, PENDING, TRANSFERRED)  ← 一单一个未结实例
--
-- 【节点设计与其权限码依据】
--   选型原则：**只用 @RequirePermission 已经在用的真实权限码**，不发明新码。
--   依据（实测 app_role_permissions，列名是 permission，不是 permission_code）：
--     ADMIN      持 mdm:* pur:* sal:* inv:* fin:* set:* cst:* rma:* prm:* rpt:*
--                sys:* iam:* aps:*                    ← ActorContext.hasPermission() 支持
--                                                          "模块:*" 前缀通配，所以 admin 一定能签
--     FINANCE    持 pur:pay pur:payable sal:receipt set:* cst:* fin:* aps:approve aps:read
--     PURCHASER  持 pur:create/submit/approve/receive/void + aps:submit aps:read
--     SALESPERSON持 sal:create/submit/ship/void + aps:submit aps:read
--   每条节点都满足"admin 拿得到"：pur:receive ⊂ pur:*，sal:approve ⊂ sal:*，
--   sal:receipt 显式授予 FINANCE，pur:pay 显式授予 FINANCE，rma:approve ⊂ rma:*。
--   （反面教训：V14 原本想用 set:monthly 当财务节点，能签，但它和"月结"绑定，
--     语义上是"关账权"而不是"复核这笔金额"，这里换成 pur:pay / sal:receipt。）
--
--   threshold_amount 全部取 0 = 必经。刻意不用阈值：
--     · ApprovalStep.appliesTo() 是 amount >= threshold，首节点必须为 0（ApprovalFlow.validate()）；
--     · 若第二节点带阈值，小额单会把它置 SKIPPED，调用方就要先读实例再决定签几次，
--       seeder 与运行期验证都需要分支。0 让 PO/SO 恒为 2 签、RO 恒为 1 签，链路可预期。
--     · 阈值能力没有被删，只是本流不用；要演示"超阈值追加审批"另开一条流即可。
-- ============================================================================

-- ────────────── 三条流的节点定义（按 flow 逐租户展开，幂等） ──────────────
-- JOIN approval_flows 而不是写死 flow_id：
--   V16 给演示租户(tenant_id=2)复制了同 code 的三条流，写死 id 只会补齐 tenant 1。
--   tenant_id 显式取 f.tenant_id —— 触发器只在 NULL 时按会话租户填充，
--   而迁移是在 Flyway 连接里跑的（会话没有 erp.tenant_id，erp_tenant_current() 实测返回 NULL），
--   不显式写就会撞 tenant_id NOT NULL。
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
        -- 进货单：先确认"到的货对不对"，再确认"付的钱对不对"
        ('PO-STD', 1, '仓管确认数量',     'pur:receive', 'SINGLE', 1, 0),
        ('PO-STD', 2, '财务复核金额',     'pur:pay',     'SINGLE', 1, 0),
        -- 出货单：先确认"能不能发"，再确认"信用额度还够不够"
        ('SO-STD', 1, '销售主管审核发货', 'sal:approve', 'SINGLE', 1, 0),
        ('SO-STD', 2, '财务复核信用额度', 'sal:receipt', 'SINGLE', 1, 0),
        -- 退货单：一级。退货金额小、且入库即回补库存，两级纯属拖累
        ('RO-STD', 1, '客服主管审核退货', 'rma:approve', 'SINGLE', 1, 0)
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

-- 二次幂等（V14 若在别的环境上成功种过节点，名字与这里不同也无妨：
-- 上面的 NOT EXISTS 按 step_no 拦住了，不会插出第二份）。

-- ────────────── 断言：三条流都必须有必经首节点 ──────────────
-- 放在迁移里而不是只靠人肉 review：本缺陷的形态恰恰是"看起来种好了"。
DO $$
DECLARE
    bad_flow TEXT;
BEGIN
    -- 注意：PostgreSQL 的 format() 只有 %s/%I/%L/%p，**没有 %d**（整数走 %s）。
    -- 这里曾在 %d 上静默炸掉：断言的 SELECT 只对"不合格的行"求值，
    -- 一切正常时 format() 根本不被调用，写错的格式串要到真出错那天才暴露 ——
    -- 所以本迁移的失败路径必须单独验一次（见 V4-3-审批流节点-报告.md）。
    SELECT string_agg(
               format('%s/tenant%s：节点 %s 个，必经首节点 %s 个',
                      x.code, x.tenant_id, x.def_steps, x.must_pass_first),
               '；' ORDER BY x.tenant_id, x.code)
      INTO bad_flow
      FROM (
        SELECT f.code,
               f.tenant_id,
               count(s.id) FILTER (WHERE s.instance_id IS NULL)              AS def_steps,
               -- 首节点必须恰好一个，且 step_no=1 且 threshold=0（ApprovalFlow.validate() 的要求）
               count(s.id) FILTER (WHERE s.instance_id IS NULL AND s.step_no = 1
                                                 AND s.threshold_amount = 0) AS must_pass_first
          FROM approval_flows f
          LEFT JOIN approval_steps s ON s.flow_id = f.id
         GROUP BY f.code, f.tenant_id
      ) x
     WHERE x.def_steps = 0
        OR x.must_pass_first <> 1
        OR x.def_steps < x.must_pass_first;

    IF bad_flow IS NOT NULL THEN
        RAISE EXCEPTION 'V23 收尾自检失败：[%]。零节点流程一旦被启用，submit() 会空转回滚、单据卡死在 SUBMITTED —— 这条断言就是为了让这种流程无法静默存在。', bad_flow;
    END IF;
END
$$;
