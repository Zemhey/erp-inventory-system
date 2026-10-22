-- ============================================================================
-- V5 · M2-S2 结算（SET）：日清 / 月结锁账 / 总账明细对账
-- 范围：
--   ① settlement_periods  —— 账期状态与期间汇总（锁账的唯一事实源）
--   ② daily_settlements   —— 按 (业务日期, 仓库) 的日清行
--   ③ set:* / rpt:* 权限种子 + FINANCE 角色
-- 依据：.plan/M2-计划书.md §3.2 / §3.4 / §3.6，docs/02 不变量 INV-5
--
-- 设计原则（延续 V1~V4）：**不变量写在数据库层**。
--   日清的幂等不依赖应用层"先查再插"，而是依赖 uq_daily_settlement ——
--   并发日清只有唯一索引能保证"同一天同一仓永远只有一行"。
-- ============================================================================

-- ─────────────────────────── 账期（月结锁账） ───────────────────────────

CREATE TABLE settlement_periods (
    period          VARCHAR(7)  PRIMARY KEY,          -- yyyy-MM
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN','CLOSED')),
    purchase_qty    BIGINT      NOT NULL DEFAULT 0,
    purchase_amount BIGINT      NOT NULL DEFAULT 0,
    sales_qty       BIGINT      NOT NULL DEFAULT 0,
    sales_amount    BIGINT      NOT NULL DEFAULT 0,
    return_qty      BIGINT      NOT NULL DEFAULT 0,
    return_amount   BIGINT      NOT NULL DEFAULT 0,
    net_amount      BIGINT      NOT NULL DEFAULT 0,
    closed_at       TIMESTAMPTZ,
    closed_by       BIGINT,
    version         INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT ck_settlement_period_format CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    -- net_amount 恒等于 sales_amount − return_amount。
    -- 这条等式若只写在 Java 里，一次手工修数据就会让对账永远平不了 ——
    -- 不变量 INV-5 的起点是"总账自己先自洽"。
    CONSTRAINT ck_period_net_amount CHECK (net_amount = sales_amount - return_amount)
);
COMMENT ON TABLE settlement_periods IS
    '账期汇总与锁账状态。status=CLOSED 时 PUR/SAL/RMA 的写操作一律被 PeriodGuard 拒绝';
COMMENT ON COLUMN settlement_periods.period IS '账期 yyyy-MM，由单据 biz_date 归属（计划书 §7 决策 1）';
COMMENT ON COLUMN settlement_periods.status IS 'OPEN=可继续记账；CLOSED=已月结锁账（不可逆，本期不提供解锁）';
COMMENT ON COLUMN settlement_periods.net_amount IS '期间净额 = sales_amount − return_amount（分）';
COMMENT ON COLUMN settlement_periods.version IS '乐观锁：并发月结时防丢更新（计划书 §3.7）';
-- 锁账只有 CLOSED 才拦写操作；这条部分索引就是 PeriodGuard 每次查询命中的形状
CREATE INDEX idx_settlement_period_closed ON settlement_periods (period) WHERE status = 'CLOSED';

-- ─────────────────────────── 日清行 ───────────────────────────

CREATE TABLE daily_settlements (
    id              BIGSERIAL   PRIMARY KEY,
    biz_date        DATE        NOT NULL,
    warehouse_id    BIGINT      NOT NULL REFERENCES warehouses (id),
    purchase_qty    BIGINT      NOT NULL DEFAULT 0,
    purchase_amount BIGINT      NOT NULL DEFAULT 0,
    sales_qty       BIGINT      NOT NULL DEFAULT 0,
    sales_amount    BIGINT      NOT NULL DEFAULT 0,
    return_qty      BIGINT      NOT NULL DEFAULT 0,
    return_amount   BIGINT      NOT NULL DEFAULT 0,
    net_amount      BIGINT      NOT NULL DEFAULT 0,
    request_id      VARCHAR(64),
    settled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ★ 日清幂等的真正实现：一个 (业务日期, 仓库) 只能有一行。
    --   应用层用 INSERT .. ON CONFLICT DO UPDATE 命中它，
    --   因此并发日清既不会报唯一冲突，也不会把金额累加成两倍。
    CONSTRAINT uq_daily_settlement UNIQUE (biz_date, warehouse_id),
    CONSTRAINT ck_daily_net_amount CHECK (net_amount = sales_amount - return_amount)
);
COMMENT ON TABLE daily_settlements IS
    '日清行。口径是单据 biz_date（不是流水 occurred_at），见计划书 §3.4 与 §7 决策 1';
COMMENT ON COLUMN daily_settlements.request_id IS
    '最近一次执行日清的幂等键，仅用于排障追踪；幂等本身由 uq_daily_settlement 保证';
COMMENT ON COLUMN daily_settlements.purchase_qty IS '当日该仓累计入库量（进货单 PARTIAL/COMPLETED 的 received_qty 合计）';
COMMENT ON COLUMN daily_settlements.sales_amount IS '当日出库金额，按行重算 round(unit_price × shipped_qty × discount_rate / 10000)';

CREATE INDEX idx_daily_settlement_date ON daily_settlements (biz_date, warehouse_id);

-- ─────────────────────────── 权限种子（FR-IAM-02） ───────────────────────────
-- 沿用 M1 规则：**迁移里绝不创建账号**，只种角色与角色→权限。
-- ADMIN 的 set:*/rpt:* 只是数据完备；真正的放行由 PermissionAspect 的 ROLE_ADMIN 短路决定。

INSERT INTO app_roles (code, name, description) VALUES
    ('FINANCE', '财务', '可查看退货/结算/报表，可执行日清、月结与对账，但不能开单');

-- rpt:* 一并种在这里（计划书 §3.2：V5 = SET 与 RPT 权限；S3 只补索引与实现）
INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN', 'set:*'), ('ADMIN', 'rpt:*'),
    ('FINANCE', 'rma:read'),
    ('FINANCE', 'set:read'), ('FINANCE', 'set:daily'), ('FINANCE', 'set:monthly'),
    ('FINANCE', 'set:reconcile'), ('FINANCE', 'rpt:read'),
    ('VIEWER', 'set:read'), ('VIEWER', 'rpt:read')
) AS p(role_code, permission) ON r.code = p.role_code;
