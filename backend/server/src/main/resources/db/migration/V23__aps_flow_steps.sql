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
--   依据（实测 app_role_permissions，列名是 permission，不是 permission_code；
--   下面是 tenant_id=1 的完整授权串，别再凭印象改写）：
--     ADMIN = aps:* cst:* fin:* iam:* inv:* mdm:* prm:* pur:* rma:* rpt:* sal:*
--             set:* sys:*  + dashboard:read sal:bundle sal:pick sal:quote sal:receipt
--             sys:config sys:loginlog sys:numberrule sys:org sys:read tenant:manage
--             ← ActorContext.Actor#hasPermission() 支持"模块:*"前缀通配，
--               所以本迁移用到的每一个码 admin 都签得动
--     FINANCE    = aps:approve aps:read + cst:monthly cst:read cst:recalc
--                  fin:account:write fin:bank:write fin:expense:approve fin:expense:write
--                  fin:invoice:write fin:read fin:report fin:voucher:write
--                  iam:read mdm:read pur:pay pur:payable rma:read rpt:read sal:read
--                  sal:receipt set:daily set:monthly set:read set:reconcile sys:read
--                  ★ 注意 FINANCE **没有** set:*/fin:* 这种通配，是逐条授予的
--     PURCHASER  = aps:read aps:submit + inv:read mdm:read iam:read
--                  pur:approve pur:create pur:payable pur:receive pur:submit pur:void
--     SALESPERSON= aps:read aps:submit + inv:read mdm:read iam:read prm:read
--                  sal:create sal:pick sal:quote sal:receipt sal:ship sal:submit sal:void
--                  rma:create rma:read rma:submit
--   每条节点都满足"admin 签得动"（这是本项目踩过的坑：码选错了没人能签）：
--     pur:approve ⊂ ADMIN 的 pur:*      且显式授予 PURCHASER
--     sal:approve ⊂ ADMIN 的 sal:*      —— 与 SalesOrderController 自己的
--                                          @RequirePermission("sal:approve") 同一个码
--     rma:approve ⊂ ADMIN 的 rma:*      —— 同上，ReturnOrderController:159
--     set:monthly  ⊂ ADMIN 的 set:*      且**只**授予 FINANCE（月结权限，
--                                          语义就是"这笔金额可以进财务账了"）
--   实测确认：admin 登录后 permissions 里有 pur:*/sal:*/rma:*/set:*/aps:*，
--   ActorContext.Actor#hasPermission() 支持"模块:*"前缀通配，所以每一级都签得动；
--   运行期验证里两级节点的 decisionBy 都是 1（admin id），已证。
--   ★ 另一个必须知道的约束：POST /approvals/{id}/approve 的接口级权限是 aps:approve，
--     V14 只把它给了 FINANCE（PURCHASER/SALESPERSON 刻意不给，见 V14:213 注释）。
--     于是"节点权限码给了 PURCHASER 但 PURCHASER 连接口都进不来"——
--     当前库里只有 admin 一个账号（app_users 实测 1 行），所以链路能跑通；
--     真要让采购员自己签第一级，得另外处理 aps:approve 的授予，不在本次范围。
--
--   threshold_amount：首节点必须 0（必经，ApprovalFlow.validate() 的要求）；
--     第二节点取 50 万分 = 5000 元，即 V14 注释里"金额超阈值追加财务"的那条规则。
--     ★ 这个取值不是审美选择，是被测试钉住的：
--       {@code ApprovalIT#TC-APS-012}（FR-IAM-07 的核心用例）断言
--       "5 万分的小额单只实例化 1 个 PENDING 节点 + 1 个 SKIPPED 快照"，
--       而 {@code TestDataFactory#ensureApprovalSeed} 的补种守卫是
--       WHERE NOT EXISTS (flow_id, step_no) —— 迁移一旦种上，测试夹具就不会再插自己那份。
--       所以本迁移的 (step_no, 名称, 权限码, 阈值) 必须与夹具**逐字一致**，
--       否则 TC-APS-012/013 会静默改测另一套流程。
--     副作用与对策：金额 < 5000 元的单据只走 1 级。seeder 因此不能硬写"签两次"，
--     必须读 GET /approvals/{id} 的 steps，对 status=PENDING 的节点逐个签
--     （见报告《给 seeder 的操作序列》）。
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
        -- 进货单：采购主管先批（数量/供应商对不对），超 5000 元追加财务复核金额
        ('PO-STD', 1, '采购主管审批',           'pur:approve',  'SINGLE', 1, 0),
        ('PO-STD', 2, '财务审批（金额超阈值）', 'set:monthly',  'SINGLE', 1, 500000),
        -- 出货单：销售主管先批（能不能发），超 5000 元追加财务复核（信用额度/回款）
        ('SO-STD', 1, '销售主管审批',           'sal:approve',  'SINGLE', 1, 0),
        ('SO-STD', 2, '财务审批（金额超阈值）', 'set:monthly',  'SINGLE', 1, 500000),
        -- 退货单：客服主管先批，超 5000 元退款追加财务
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
