-- ═══════════════════════════════════════════════════════════════════════════
-- V25 · V4-S5/S6：自然语言操作的「待确认队列」与「执行审计」
--
-- 为什么需要这张表（不是"顺手记个日志"）：
--   ① S4 的确认回执是无状态的（签名即真伪），于是"有多少条在等人确认"无处可查。
--      没有这张表，待确认就只活在前端内存里 —— 用户关掉页面，那条计划就消失了，
--      而它在后端其实什么都没发生。**看不见的待确认 = 没审。**
--   ② 审计要能回答"它当时打算干什么、谁复核的、谁拍的板、最后落成了哪张单"。
--      audit_logs 是 append-only 的"已发生事实"，塞不进 PENDING→EXECUTED 这个生命周期，
--      所以这里单开一张有状态表，而不是往 audit_logs 里硬塞。
--
-- 口径（对齐 V21）：
--   * tenant_id 允许为空（V20 的 sys_login_log NOT NULL 教训）。
--   * 计划与复核结果按**生成时**的 JSON 原样存：审计认证的是"当时看到的东西"，
--     不是"按现在的代码重新算一遍"——后者会让历史记录随着代码演化偷偷改变。
--   * 时间一律 TIMESTAMPTZ。
--
-- 状态机（只增不改语义，任何状态都保留原行）：
--   PENDING  ──人点确认并执行──▶ EXECUTED
--      │        └──执行时业务报错──▶ FAILED
--      ├──超过有效期（默认 5 分钟）──▶ EXPIRED
--      └──人主动驳回──────────────▶ DISMISSED
--   生成计划时即判定：
--      ├──参数不全（executable=false）──▶ BLOCKED
--      └──复核否决（review=REJECT）────▶ VETOED
--
--   只有 PENDING 会进「待确认队列」；其余五种都只出现在审计里。
--   BLOCKED / VETOED 也要留痕：**"它想干什么、被什么挡住"本身就是审计价值**。
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS nl_execution (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT,
    -- 计划那一次请求的 X-Request-Id。执行时的回执里带着它，于是"打算做什么"
    -- 与"最终干了什么"能串成一条线；它同时也是建单服务的幂等键。
    request_id      VARCHAR(64)  NOT NULL,
    actor_id        BIGINT       NOT NULL,
    actor_name      VARCHAR(64),
    -- 调用方：WEB / MCP / API。审计里"是哪个 Agent 发的"目前只能记到这个粒度。
    source          VARCHAR(16)  NOT NULL DEFAULT 'WEB',
    command_text    VARCHAR(500) NOT NULL,
    intent          VARCHAR(40)  NOT NULL,
    write_op        BOOLEAN      NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    verdict         VARCHAR(16)  NOT NULL,
    review_source   VARCHAR(16),
    summary         VARCHAR(500),
    plan_json       TEXT         NOT NULL,
    review_json     TEXT         NOT NULL,
    doc_type        VARCHAR(20),
    doc_id          BIGINT,
    doc_no          VARCHAR(32),
    result_json     TEXT,
    error_message   VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    decided_at      TIMESTAMPTZ,
    decided_by      BIGINT,
    decided_by_name VARCHAR(64),
    CONSTRAINT ck_nl_execution_status CHECK (status IN
        ('PENDING', 'EXECUTED', 'FAILED', 'EXPIRED', 'DISMISSED', 'BLOCKED', 'VETOED'))
);

COMMENT ON TABLE nl_execution IS
    '自然语言操作的计划与执行审计（FR-V4-03 S5/S6）。一行 = 一次"打算做什么"，状态随人工确认推进';
COMMENT ON COLUMN nl_execution.request_id IS
    '计划请求的 X-Request-Id；执行时由确认回执带回，用于把计划与执行串起来，同时作为建单幂等键';
COMMENT ON COLUMN nl_execution.plan_json IS
    '生成当时的 NlPlan 快照。审计认证"当时看到的东西"，不按现在的代码重算';
COMMENT ON COLUMN nl_execution.expires_at IS
    '待确认的截止时间（默认创建后 5 分钟）。过期由读取时惰性结转，不依赖定时任务';
COMMENT ON COLUMN nl_execution.decided_by IS
    '拍板的人（确认或驳回）。系统自动结转（EXPIRED/BLOCKED/VETOED）时为空';

-- 队列的主查询：status=PENDING 按时间倒序
CREATE INDEX IF NOT EXISTS idx_nl_execution_status ON nl_execution (status, created_at DESC);
-- 审计的主查询：某个人最近做了什么
CREATE INDEX IF NOT EXISTS idx_nl_execution_actor ON nl_execution (actor_id, created_at DESC);
-- 执行时按 request_id 回填结果
CREATE INDEX IF NOT EXISTS idx_nl_execution_request ON nl_execution (request_id);
CREATE INDEX IF NOT EXISTS idx_nl_execution_tenant ON nl_execution (tenant_id);
