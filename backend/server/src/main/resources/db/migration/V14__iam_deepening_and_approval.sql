-- ============================================================================
-- V14 · V2-6 权限深化与审批流（FR-IAM-04/05/06/07、FR-APS-01/02）
-- 范围：
--   ① data_scopes        —— 数据权限（按仓库 / 按本人），主体为「用户」或「角色」
--   ② app_users 加列      —— 密码过期时刻 + 强制改密标记
--   ③ approval_flows      —— 审批流定义（单据类型 + 启用态，一个类型最多一条启用流）
--   ④ approval_steps      —— 审批节点：定义态（instance_id IS NULL）与实例快照态同表
--   ⑤ approval_instances  —— 审批实例（单据审批这一种流程，不做通用 BPMN）
--   ⑥ iam:* / aps:* 权限码种子
-- 依据：docs/planning/V2-商业化扩展蓝图.md §4.2 链 9、§6.9；.plan/V2-6-计划书.md
--
-- ── 三条设计决定（都是"为什么不那样做"）────────────────────────────────
--
-- ① **数据权限默认放开（未配置 = 全量）**，而不是默认收紧。
--    收紧的默认值会让 V1~V8 的 234 条存量用例全部变红（它们都没有造 data_scopes），
--    而且"新功能把老系统锁死"在生产里就是事故。收窄是**显式配置**的结果：
--    只要一个用户（或他所属的某个角色）配了 WAREHOUSE/SELF 行，就不再是全量。
--    这条语义写在 DataScope.resolve 里，并由 TC-IAM-0xx 钉死。
--
-- ② **approval_steps 一张表承担两个角色**：instance_id IS NULL = 流程定义节点；
--    IS NOT NULL = 实例化后的快照节点。蓝图 §4.1 给审批只留 3 张表，
--    而"阈值节点可能因金额被跳过"决定了节点**必须在实例化时快照**，
--    不能只查定义。用 CHECK 把两种形态钉死，就不会出现"半定义半实例"的脏行。
--
-- ③ **审批状态机与单据状态机是两套**（DocumentStatus 七态 vs ApprovalStatus 七态）。
--    审批实例的状态只回答"这个审批走完了没有"，它**不写**单据的 status。
--    单据侧在审核前调 ApprovalGate 问一句"可否"，状态推进仍由单据自己走
--    DocumentStatusMachine —— 这样红冲（V8 的补偿性转移）与审批不会互相纠缠。
-- ============================================================================

-- ─────────────────────────── ① 数据权限 ───────────────────────────

CREATE TABLE data_scopes (
    id           BIGSERIAL   PRIMARY KEY,
    -- 主体二选一：直接给用户，或给角色（角色授权对角色下所有用户生效）
    subject_type VARCHAR(8)  NOT NULL CHECK (subject_type IN ('USER', 'ROLE')),
    user_id      BIGINT      REFERENCES app_users (id) ON DELETE CASCADE,
    role_id      BIGINT      REFERENCES app_roles (id) ON DELETE CASCADE,
    -- ALL = 全量（显式放开）；WAREHOUSE = 限定到 scope_value 指向的仓库；SELF = 只看本人创建
    scope_type   VARCHAR(16) NOT NULL CHECK (scope_type IN ('ALL', 'WAREHOUSE', 'SELF')),
    -- WAREHOUSE 时是 warehouses.id；ALL / SELF 必须为空
    scope_value  BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 主体二选一，不能既给用户又给角色，也不能两个都不给
    CONSTRAINT ck_data_scope_subject CHECK (
        (subject_type = 'USER' AND user_id IS NOT NULL AND role_id IS NULL)
     OR (subject_type = 'ROLE' AND role_id IS NOT NULL AND user_id IS NULL)),
    -- 值的存在性跟着类型走：WAREHOUSE 必须有仓库，ALL/SELF 必须没有
    CONSTRAINT ck_data_scope_value CHECK (
        (scope_type = 'WAREHOUSE' AND scope_value IS NOT NULL)
     OR (scope_type <> 'WAREHOUSE' AND scope_value IS NULL))
);
COMMENT ON TABLE data_scopes IS
    '数据权限：按仓库或按本人收窄可见范围（FR-IAM-04）。未配置任何行 = 全量（见 V14 注释①）';
COMMENT ON COLUMN data_scopes.subject_type IS 'USER=直接授给用户；ROLE=授给角色，对该角色全部用户生效';
COMMENT ON COLUMN data_scopes.scope_type IS 'ALL=全量 / WAREHOUSE=限定仓库 / SELF=只看本人创建';
COMMENT ON COLUMN data_scopes.scope_value IS 'scope_type=WAREHOUSE 时指向 warehouses.id；其余为 NULL';

-- 同一主体同一 scope 不能重复配。
-- scope_value 对 ALL/SELF 是 NULL，而 PostgreSQL 的唯一索引认为 NULL 互不相等，
-- 直接用 (user_id, scope_type, scope_value) 会允许无限条重复的 ALL 行 ——
-- 因此用 COALESCE(scope_value, 0) 这个表达式索引把 NULL 折成一个固定值。
CREATE UNIQUE INDEX uq_data_scope_user ON data_scopes (user_id, scope_type,
                                                       COALESCE(scope_value, 0))
    WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_data_scope_role ON data_scopes (role_id, scope_type,
                                                       COALESCE(scope_value, 0))
    WHERE role_id IS NOT NULL;
CREATE INDEX idx_data_scope_user ON data_scopes (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_data_scope_role ON data_scopes (role_id) WHERE role_id IS NOT NULL;

-- ─────────────────────────── ② 登录安全策略加列 ───────────────────────────
--
-- ★ 先查 V3 已有什么：V3 已经建了 failed_attempts（失败计数）与 locked_until（锁定截止），
--   所以这两列**不重复加** —— 任务书里的 failed_count / locked_until 已经存在，
--   只是列名是 failed_attempts。这里只补"密码生命周期"两列。
ALTER TABLE app_users
    ADD COLUMN password_expires_at  TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN app_users.password_expires_at IS
    '密码过期时刻（FR-IAM-06）。NULL = 不按时间过期；过期后除改密接口外一律 IAM_PASSWORD_EXPIRED';
COMMENT ON COLUMN app_users.must_change_password IS
    '强制改密标记：管理员重置密码/首次登录时置 TRUE，改密成功后清零';

-- 到期密码的巡检/登录查形状：只关心"已经/即将过期"的行
CREATE INDEX idx_app_user_password_expiry ON app_users (password_expires_at)
    WHERE password_expires_at IS NOT NULL;

-- ─────────────────────────── ③ 审批流定义 ───────────────────────────

CREATE TABLE approval_flows (
    id          BIGSERIAL   PRIMARY KEY,
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    -- 只做「单据审批」这一种流程（蓝图 §0.3 已砍掉通用 BPMN）
    biz_type    VARCHAR(32) NOT NULL CHECK (biz_type IN (
                    'PURCHASE_ORDER', 'SALES_ORDER', 'RETURN_ORDER')),
    description VARCHAR(200),
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_approval_flow_code UNIQUE (code)
);
COMMENT ON TABLE approval_flows IS
    '审批流定义头（FR-APS-01）：一种单据类型一条流程；节点/条件/阈值/会签或签在 approval_steps 的定义行里';
COMMENT ON COLUMN approval_flows.biz_type IS '只允许进货单/出货单/退货单三种，不做通用流程引擎';

-- 一个单据类型最多只能有一条启用中的流程 ——
-- 否则"提交审批"时该用哪条流就没有确定答案，而这属于配置错误，
-- 必须在库层拒绝，而不是在应用层随机挑一条。
CREATE UNIQUE INDEX uq_approval_flow_enabled_biz ON approval_flows (biz_type)
    WHERE enabled;

-- ─────────────────────────── ④ 审批实例 ───────────────────────────
--
-- ★ 必须先于 approval_steps 建：steps 有指向 instances 的外键。
--   PostgreSQL 的 FK 在 CREATE TABLE 时就要求目标表存在（不像 Oracle 要先建后加）。

CREATE TABLE approval_instances (
    id           BIGSERIAL   PRIMARY KEY,
    flow_id      BIGINT      NOT NULL REFERENCES approval_flows (id),
    biz_type     VARCHAR(32) NOT NULL CHECK (biz_type IN (
                     'PURCHASE_ORDER', 'SALES_ORDER', 'RETURN_ORDER')),
    biz_id       BIGINT      NOT NULL,
    biz_ref_no   VARCHAR(64) NOT NULL,
    -- 金额（分）：阈值判定与"上级审批"都基于它；由审批提交方按单据真实金额传入
    amount       BIGINT      NOT NULL DEFAULT 0 CHECK (amount >= 0),
    -- 当前节点序号。完成一个节点后 +1；终态时保留最后的值便于回溯
    current_step INTEGER     NOT NULL DEFAULT 1 CHECK (current_step > 0),
    -- 七态（与 ApprovalStatus 枚举逐字一致）：
    --   DRAFT 草稿 / PENDING 待审批 / TRANSFERRED 已转办 / APPROVED / REJECTED /
    --   WITHDRAWN 发起人撤回 / CANCELLED 单据作废导致取消
    status       VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                     'DRAFT', 'PENDING', 'TRANSFERRED', 'APPROVED', 'REJECTED',
                     'WITHDRAWN', 'CANCELLED')),
    initiator_id BIGINT      NOT NULL REFERENCES app_users (id),
    request_id   VARCHAR(64),
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE approval_instances IS
    '审批实例（FR-APS-02）：一张单据同时只能有一个未结实例；状态是审批自己的七态，与单据七态无关';
COMMENT ON COLUMN approval_instances.biz_ref_no IS '来源单据号（冗余存放，便于按单号查审批而不跨模块 join）';

-- 提交审批的幂等键（与单据建单同一做法：数据库唯一索引兜底）
CREATE UNIQUE INDEX uq_approval_instance_request ON approval_instances (request_id)
    WHERE request_id IS NOT NULL;
-- 同一张单据同时只能有一个未结审批：防止连点"提交审批"造出两个并行实例，
-- 也防止"已通过"的实例旁边长出一个新的待审实例（重复审批的经典漏洞）
CREATE UNIQUE INDEX uq_approval_instance_open ON approval_instances (biz_type, biz_id)
    WHERE status IN ('DRAFT', 'PENDING', 'TRANSFERRED');
CREATE INDEX idx_approval_instance_biz ON approval_instances (biz_type, biz_id);
CREATE INDEX idx_approval_instance_status ON approval_instances (status);

-- ─────────────────────────── ⑤ 审批节点（定义 + 实例快照） ───────────────────────────

CREATE TABLE approval_steps (
    id                  BIGSERIAL   PRIMARY KEY,
    flow_id             BIGINT      NOT NULL REFERENCES approval_flows (id) ON DELETE CASCADE,
    -- NULL = 流程定义节点；非 NULL = 某次审批实例的快照节点
    instance_id         BIGINT      REFERENCES approval_instances (id) ON DELETE CASCADE,
    step_no             INTEGER     NOT NULL CHECK (step_no > 0),
    name                VARCHAR(64) NOT NULL,
    -- 审批所需的权限码（如 pur:approve / set:monthly），与 @RequirePermission 同一套字符串
    approver_permission VARCHAR(64) NOT NULL,
    -- SINGLE=单人审批；ALL_SIGN=会签（required_approvals 人全部同意）；ANY_SIGN=或签（任一人同意）
    approve_mode        VARCHAR(16) NOT NULL CHECK (approve_mode IN ('SINGLE', 'ALL_SIGN', 'ANY_SIGN')),
    required_approvals  INTEGER     NOT NULL DEFAULT 1 CHECK (required_approvals > 0),
    -- 金额阈值（分，ADR-0005）：实例金额 >= 本值才实例化该节点（0 = 无条件必经）
    threshold_amount    BIGINT      NOT NULL DEFAULT 0 CHECK (threshold_amount >= 0),
    status              VARCHAR(16) NOT NULL DEFAULT 'DEFINED'
                            CHECK (status IN ('DEFINED', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED')),
    -- 已同意的审批人（去重）。用 int8[] 是为了让"同一人不能重复会签计数"
    -- 由 array_append + @> 在一条 UPDATE 里原子保证，而不是应用层先查后写
    approved_by         BIGINT[]    NOT NULL DEFAULT '{}'::BIGINT[],
    decided_at          TIMESTAMPTZ,
    decision_by         BIGINT,
    remark              VARCHAR(200),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 定义态与实例态的字段组合必须自洽，杜绝"半定义半实例"的脏行
    CONSTRAINT ck_approval_step_form CHECK (
        (instance_id IS NULL     AND status = 'DEFINED' AND decided_at IS NULL
                                 AND decision_by IS NULL AND approved_by = '{}'::BIGINT[])
     OR (instance_id IS NOT NULL AND status <> 'DEFINED'))
);
COMMENT ON TABLE approval_steps IS
    '审批节点：instance_id IS NULL 为流程定义，非 NULL 为实例快照（阈值跳过的节点不实例化）';
COMMENT ON COLUMN approval_steps.threshold_amount IS
    '金额阈值（分）：实例金额 >= 本值才进入该节点，这就是 FR-IAM-07「金额阈值触发上级」';
COMMENT ON COLUMN approval_steps.approved_by IS
    '已同意的审批人 id 数组（去重）；会签 required_approvals>1 时逐个累加';

CREATE UNIQUE INDEX uq_approval_step_def ON approval_steps (flow_id, step_no)
    WHERE instance_id IS NULL;
CREATE UNIQUE INDEX uq_approval_step_instance ON approval_steps (instance_id, step_no)
    WHERE instance_id IS NOT NULL;
CREATE INDEX idx_approval_step_flow ON approval_steps (flow_id) WHERE instance_id IS NULL;

-- ─────────────────────────── ⑥ 权限码种子 ───────────────────────────
--
-- 追加式：V3/V5/V7 已种的角色与权限一行不改，这里只补 V2-6 需要的码。
-- ★ ADMIN 仍然按 PermissionAspect 的 ROLE_ADMIN 短路全权；这里补 iam:*/aps:*
--   是为了让"权限清单"这件事在数据上也是完整的（界面/审计要看得到）。

INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    -- 管理员：用户/角色/数据权限 + 审批流定义与运维
    ('ADMIN', 'iam:*'), ('ADMIN', 'aps:*'),
    -- 采购/销售：能发起审批（提交自己的单据）、能看审批进度；不给他们 aps:approve
    ('PURCHASER',   'aps:submit'), ('PURCHASER',   'aps:read'),
    ('SALESPERSON', 'aps:submit'), ('SALESPERSON', 'aps:read'),
    ('PURCHASER',   'iam:read'),   ('SALESPERSON', 'iam:read'),
    -- 财务：上级审批人（金额阈值触发到财务），可看审批与账号清单（只读）
    ('FINANCE', 'aps:read'), ('FINANCE', 'aps:approve'), ('FINANCE', 'iam:read'),
    -- 只读用户：能看审批进度，不能发起
    ('VIEWER', 'aps:read'), ('VIEWER', 'iam:read')
) AS p(role_code, permission) ON r.code = p.role_code
ON CONFLICT DO NOTHING;

-- ─────────────────────────── 种子审批流（开发/测试用，★★ 默认停用） ───────────────────────────
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
    ('RO-STD', '退货单标准审批', 'RETURN_ORDER',   '客服主管 → （金额超阈值）财务', FALSE);

INSERT INTO approval_steps
    (flow_id, instance_id, step_no, name, approver_permission, approve_mode,
     required_approvals, threshold_amount)
SELECT f.id, NULL, s.step_no, s.name, s.permission, s.mode, s.required, s.threshold
  FROM approval_flows f
  JOIN (VALUES
    ('PO-STD', 1, '采购主管审批', 'pur:approve', 'SINGLE', 1, 0),
    ('PO-STD', 2, '财务审批（金额超阈值）', 'set:monthly', 'SINGLE', 1, 500000),
    ('SO-STD', 1, '销售主管审批', 'sal:approve', 'SINGLE', 1, 0),
    ('SO-STD', 2, '财务审批（金额超阈值）', 'set:monthly', 'SINGLE', 1, 500000),
    ('RO-STD', 1, '客服主管审批', 'rma:approve', 'SINGLE', 1, 0),
    ('RO-STD', 2, '财务审批（金额超阈值）', 'set:monthly', 'SINGLE', 1, 500000)
  ) AS s(flow_code, step_no, name, permission, mode, required, threshold)
    ON f.code = s.flow_code;
