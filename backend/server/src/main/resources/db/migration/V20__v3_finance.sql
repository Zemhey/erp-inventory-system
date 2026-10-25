-- ============================================================================
-- V20 · V3-2 财务中心（简化复式记账）FR-V3-06 ~ FR-V3-13
-- 泳道：W2（v3-2-finance）。依据 .plan/V3-2-财务中心-任务简报.md §3。
-- 范围：
--   ① fin_account          —— 会计科目（树形；category 六大类）
--   ② fin_voucher          —— 凭证头（DRAFT → AUDITED → POSTED；来源幂等锚点）
--   ③ fin_voucher_line     —— 分录（借贷平衡由应用层校验，DB 存合计）
--   ④ fin_bank_account     —— 银行账户
--   ⑤ fin_cash_journal     —— 现金/银行日记账（当日余额 = 上日余额 + 借 − 贷）
--   ⑥ fin_expense(+line)   —— 费用报销（DRAFT→SUBMITTED→APPROVED→PAID，驳回回 DRAFT）
--   ⑦ fin_invoice          —— 发票台账（OUTPUT 销项 / INPUT 进项）
--   ⑧ 标准科目种子（≥25 个，按现存租户各一套）+ fin:* 权限种子
--
-- ★ 金额口径（简报 §5）：财务域用 numeric(18,2) + BigDecimal（元），
--   与业务域「整数分」（ADR-0005）在生成凭证处以 movePointLeft(2) 精确换算，
--   不存在浮点误差。Java 侧禁用 double，舍入一律 HALF_UP。
-- ★ 多租户（FR-TNT-02）：每张表都带 tenant_id NOT NULL + erp_tenant_fill 触发器，
--   唯一约束一律 (tenant_id, ...) 开头；表名已同步进 TenantTables.ALL。
-- ★ 已应用的迁移不改内容；本文件是本泳道独占的 V20。
-- ============================================================================

-- ─────────────────────────── ① 会计科目 ───────────────────────────
CREATE TABLE fin_account (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    parent_id     BIGINT       REFERENCES fin_account (id),
    category      VARCHAR(16)  NOT NULL CHECK (category IN
                    ('ASSET','LIABILITY','EQUITY','COST','REVENUE','EXPENSE')),
    direction     VARCHAR(8)   NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    is_leaf       BOOLEAN      NOT NULL DEFAULT TRUE,
    init_balance  NUMERIC(18,2) NOT NULL DEFAULT 0,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    remark        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_account_code UNIQUE (tenant_id, code),
    -- 余额与期初一律非负：借贷符号由 direction 决定，落库的是"方向金额"
    CONSTRAINT ck_fin_account_init CHECK (init_balance >= 0)
);
COMMENT ON TABLE fin_account IS
    '会计科目（树形，三级够用）。category 六大类；direction 决定余额正负（借/贷）';
COMMENT ON COLUMN fin_account.is_leaf IS
    '是否末级科目；只有末级科目允许出现在分录行（非末级只用于汇总）';

CREATE INDEX idx_fin_account_parent ON fin_account (tenant_id, parent_id);
CREATE INDEX idx_fin_account_category ON fin_account (tenant_id, category);

CREATE TRIGGER trg_fin_account_tenant_fill BEFORE INSERT ON fin_account
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ② 凭证头 ───────────────────────────
CREATE TABLE fin_voucher (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    voucher_no    VARCHAR(32)  NOT NULL,
    voucher_date  DATE         NOT NULL,
    period        VARCHAR(7)   NOT NULL CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    source_type   VARCHAR(16)  NOT NULL CHECK (source_type IN
                    ('MANUAL','PUR_IN','SAL_OUT','PUR_PAY','SAL_RCV','EXPENSE','INVOICE')),
    source_id     BIGINT,
    source_no     VARCHAR(64),
    status        VARCHAR(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                    ('DRAFT','AUDITED','POSTED')),
    total_debit   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (total_debit >= 0),
    total_credit  NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (total_credit >= 0),
    created_by    BIGINT,
    audited_by    BIGINT,
    posted_at     TIMESTAMPTZ,
    remark        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_voucher_no UNIQUE (tenant_id, voucher_no),
    -- ★ 自动生成凭证的幂等锚点：同一 (来源类型, 来源 id) 只允许一张凭证。
    --   MANUAL 行两列皆 NULL，Postgres 唯一索引视 NULL 互异，不受约束。
    CONSTRAINT uq_fin_voucher_source UNIQUE (tenant_id, source_type, source_id),
    -- 借贷平衡在结构上兜底（应用层先校验并给差额消息，这里是最后防线）
    CONSTRAINT ck_fin_voucher_balanced CHECK (total_debit = total_credit)
);
COMMENT ON TABLE fin_voucher IS
    '会计凭证头。source_type 非 MANUAL 的凭证由业务单据确认时自动生成，幂等';
COMMENT ON COLUMN fin_voucher.status IS
    'DRAFT 草稿（可改）→ AUDITED 已审核 → POSTED 已过账（不可改，只能红冲）';

CREATE INDEX idx_fin_voucher_period ON fin_voucher (tenant_id, period);
CREATE INDEX idx_fin_voucher_date ON fin_voucher (tenant_id, voucher_date);

CREATE TRIGGER trg_fin_voucher_tenant_fill BEFORE INSERT ON fin_voucher
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ③ 分录 ───────────────────────────
CREATE TABLE fin_voucher_line (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    voucher_id    BIGINT       NOT NULL REFERENCES fin_voucher (id) ON DELETE CASCADE,
    line_no       INTEGER      NOT NULL CHECK (line_no > 0),
    account_id    BIGINT       NOT NULL REFERENCES fin_account (id),
    account_code  VARCHAR(32)  NOT NULL,
    summary       VARCHAR(128) NOT NULL,
    debit         NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit        NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_voucher_line_no UNIQUE (tenant_id, voucher_id, line_no),
    -- 一条分录只允许单边有值且非零：0 金额分录是垃圾数据，直接进不了表
    CONSTRAINT ck_fin_voucher_line_side CHECK (
        (debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)
    )
);
COMMENT ON TABLE fin_voucher_line IS
    '凭证分录。account_code 冗余存储，报表与审计不依赖科目表现状';

CREATE INDEX idx_fin_voucher_line_account ON fin_voucher_line (tenant_id, account_id);

CREATE TRIGGER trg_fin_voucher_line_tenant_fill BEFORE INSERT ON fin_voucher_line
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ④ 银行账户 ───────────────────────────
CREATE TABLE fin_bank_account (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    bank_name     VARCHAR(64),
    account_no    VARCHAR(64),
    currency      VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    balance       NUMERIC(18,2) NOT NULL DEFAULT 0,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    remark        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_bank_account_code UNIQUE (tenant_id, code)
);
COMMENT ON COLUMN fin_bank_account.is_default IS
    '收付款自动生成凭证/日记账时的默认入账账户；每租户至多一个（部分唯一索引兜底）';

CREATE UNIQUE INDEX uq_fin_bank_account_default
    ON fin_bank_account (tenant_id) WHERE is_default;

CREATE TRIGGER trg_fin_bank_account_tenant_fill BEFORE INSERT ON fin_bank_account
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑤ 现金日记账 ───────────────────────────
CREATE TABLE fin_cash_journal (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    account_id    BIGINT       NOT NULL REFERENCES fin_bank_account (id),
    biz_date      DATE         NOT NULL,
    direction     VARCHAR(8)   NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount        NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    balance       NUMERIC(18,2) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    source_id     BIGINT,
    summary       VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE fin_cash_journal IS
    '银行/现金日记账：当日余额 = 上日余额 + 借方 − 贷方（账户是资产，借增贷减）';

CREATE INDEX idx_fin_cash_journal_account_date
    ON fin_cash_journal (tenant_id, account_id, biz_date, id);

CREATE TRIGGER trg_fin_cash_journal_tenant_fill BEFORE INSERT ON fin_cash_journal
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑥ 费用报销 ───────────────────────────
CREATE TABLE fin_expense (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL,
    expense_no      VARCHAR(32) NOT NULL,
    applicant_id    BIGINT,
    applicant_name  VARCHAR(64) NOT NULL,
    department_id   BIGINT,
    department_name VARCHAR(64),
    expense_type    VARCHAR(32) NOT NULL,
    amount          NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN
                      ('DRAFT','SUBMITTED','APPROVED','PAID','REJECTED')),
    apply_date      DATE        NOT NULL,
    reject_reason   VARCHAR(255),
    paid_at         TIMESTAMPTZ,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_expense_no UNIQUE (tenant_id, expense_no)
);
COMMENT ON TABLE fin_expense IS
    '费用报销单。状态机 DRAFT→SUBMITTED→APPROVED→PAID；驳回回 DRAFT 并留原因。'
    || '简化两级审批（申请人 → 财务），不接 APS（简报 FR-V3-10：做不了就不卡住）';

CREATE TRIGGER trg_fin_expense_tenant_fill BEFORE INSERT ON fin_expense
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

CREATE TABLE fin_expense_line (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    expense_id    BIGINT       NOT NULL REFERENCES fin_expense (id) ON DELETE CASCADE,
    line_no       INTEGER      NOT NULL CHECK (line_no > 0),
    expense_item  VARCHAR(64)  NOT NULL,
    amount        NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    invoice_no    VARCHAR(64),
    remark        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_expense_line_no UNIQUE (tenant_id, expense_id, line_no)
);
COMMENT ON TABLE fin_expense_line IS '报销明细行；Σamount = 主表 amount（应用层校验）';

CREATE TRIGGER trg_fin_expense_line_tenant_fill BEFORE INSERT ON fin_expense_line
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑦ 发票台账 ───────────────────────────
CREATE TABLE fin_invoice (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    invoice_no    VARCHAR(64)  NOT NULL,
    invoice_type  VARCHAR(8)   NOT NULL CHECK (invoice_type IN ('OUTPUT','INPUT')),
    partner_id    BIGINT,
    partner_name  VARCHAR(64)  NOT NULL,
    amount        NUMERIC(18,2) NOT NULL CHECK (amount >= 0),
    tax_amount    NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    total_amount  NUMERIC(18,2) NOT NULL CHECK (total_amount >= 0),
    invoice_date  DATE         NOT NULL,
    source_type   VARCHAR(16),
    source_id     BIGINT,
    status        VARCHAR(8)   NOT NULL DEFAULT 'NORMAL' CHECK (status IN ('NORMAL','VOID')),
    remark        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_invoice_no UNIQUE (tenant_id, invoice_no, invoice_type),
    -- 价税分离恒等式：不含税 + 税额 = 价税合计，写错直接进不了表
    CONSTRAINT ck_fin_invoice_sum CHECK (amount + tax_amount = total_amount),
    -- 简化口径：源单据（进货单/出货单）只允许 INPUT/OUTPUT 之一挂载
    CONSTRAINT ck_fin_invoice_source CHECK (
        source_type IS NULL OR source_type IN ('PURCHASE_ORDER','SALES_ORDER')
    )
);
COMMENT ON TABLE fin_invoice IS
    '发票台账：OUTPUT 销项 / INPUT 进项（页面两个 Tab）。可关联进货/出货单；'
    || '同一单据累计开票不得超过单据金额（超额 409，应用层校验）';
COMMENT ON COLUMN fin_invoice.invoice_type IS
    '简报 §3 的 OUTPUT/INPUT 落在本列（Tab 分组键）；简报里重复的 direction 列被合并删除，'
    || '理由见 .plan/V3-2-财务中心-报告.md「踩的坑」';

CREATE INDEX idx_fin_invoice_source ON fin_invoice (tenant_id, source_type, source_id);

CREATE TRIGGER trg_fin_invoice_tenant_fill BEFORE INSERT ON fin_invoice
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑧ 标准科目种子 ───────────────────────────
-- 28 个标准科目 × 现存租户（V16 default=1、demo=2 及其后平台租户管理新建的租户）。
-- ★ 迁移时刻意**不**依赖 erp_tenant_current()（会话变量在 Flyway 连接里是 NULL），
--   而是显式给 tenant_id —— 与简报"漏租户是响亮失败而非静默错位"同一口径。
-- 之后新建的租户由应用层 FinAccountApplicationService.ensureSeeded 懒补同一套模板
-- （模板常量与这里的 VALUES 逐字一致，两处都是 28 行）。
INSERT INTO fin_account (tenant_id, code, name, category, direction, is_leaf, init_balance)
SELECT t.id, v.code, v.name, v.category, v.direction, v.is_leaf, 0
FROM tenants t
CROSS JOIN (VALUES
    -- 资产（借方余额）
    ('1001', '库存现金',       'ASSET',     'DEBIT',  TRUE),
    ('1002', '银行存款',       'ASSET',     'DEBIT',  TRUE),
    ('1012', '其他货币资金',   'ASSET',     'DEBIT',  TRUE),
    ('1122', '应收账款',       'ASSET',     'DEBIT',  TRUE),
    ('1123', '预付账款',       'ASSET',     'DEBIT',  TRUE),
    ('1221', '其他应收款',     'ASSET',     'DEBIT',  TRUE),
    ('1401', '原材料',         'ASSET',     'DEBIT',  TRUE),
    ('1405', '库存商品',       'ASSET',     'DEBIT',  TRUE),
    ('1411', '周转材料',       'ASSET',     'DEBIT',  TRUE),
    -- 负债（贷方余额）
    ('2202', '应付账款',       'LIABILITY', 'CREDIT', TRUE),
    ('2203', '预收账款',       'LIABILITY', 'CREDIT', TRUE),
    ('2211', '应付职工薪酬',   'LIABILITY', 'CREDIT', TRUE),
    ('2221', '应交税费',       'LIABILITY', 'CREDIT', FALSE),
    ('222101', '应交税费-进项税额', 'LIABILITY', 'DEBIT',  TRUE),
    ('222102', '应交税费-销项税额', 'LIABILITY', 'CREDIT', TRUE),
    -- 权益（贷方余额）
    ('4001', '实收资本',       'EQUITY',    'CREDIT', TRUE),
    ('4002', '资本公积',       'EQUITY',    'CREDIT', TRUE),
    ('4104', '利润分配',       'EQUITY',    'CREDIT', TRUE),
    -- 收入（贷方余额）
    ('6001', '主营业务收入',   'REVENUE',   'CREDIT', TRUE),
    ('6051', '其他业务收入',   'REVENUE',   'CREDIT', TRUE),
    ('6301', '营业外收入',     'REVENUE',   'CREDIT', TRUE),
    -- 成本（借方余额）
    ('6401', '主营业务成本',   'COST',      'DEBIT',  TRUE),
    ('6402', '其他业务成本',   'COST',      'DEBIT',  TRUE),
    ('6403', '税金及附加',     'COST',      'DEBIT',  TRUE),
    -- 费用（借方余额）
    ('6601', '销售费用',       'EXPENSE',   'DEBIT',  TRUE),
    ('6602', '管理费用',       'EXPENSE',   'DEBIT',  TRUE),
    ('6603', '财务费用',       'EXPENSE',   'DEBIT',  TRUE),
    ('6711', '营业外支出',     'EXPENSE',   'DEBIT',  TRUE)
) AS v(code, name, category, direction, is_leaf)
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 222101/222102 是 2221 的子科目：按租户回填 parent_id（父行同租户同码才连，跨租户不串）。
UPDATE fin_account c
   SET parent_id = p.id
  FROM fin_account p
 WHERE p.tenant_id = c.tenant_id
   AND c.code IN ('222101', '222102')
   AND p.code = '2221'
   AND c.parent_id IS NULL;

-- ─────────────────────────── ⑧' 权限种子（与 V12 同一写法） ───────────────────────────
INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN',   'fin:*'),
    ('FINANCE', 'fin:read'),  ('FINANCE', 'fin:account:write'),
    ('FINANCE', 'fin:voucher:write'), ('FINANCE', 'fin:bank:write'),
    ('FINANCE', 'fin:expense:write'), ('FINANCE', 'fin:expense:approve'),
    ('FINANCE', 'fin:invoice:write'), ('FINANCE', 'fin:report'),
    ('VIEWER',  'fin:read')
) AS p(role_code, permission) ON r.code = p.role_code
ON CONFLICT DO NOTHING;

-- ─────────────────────────── 自检（V18 同款：把"结构承诺"钉在迁移里） ───────────────────────────
DO $$
DECLARE n INTEGER;
BEGIN
    -- ① 八张新表必须都在，且 tenant_id NOT NULL。
    SELECT COUNT(*) INTO n FROM information_schema.columns
     WHERE table_name IN ('fin_account','fin_voucher','fin_voucher_line','fin_bank_account',
                          'fin_cash_journal','fin_expense','fin_expense_line','fin_invoice')
       AND column_name = 'tenant_id' AND is_nullable = 'YES';
    IF n > 0 THEN
        RAISE EXCEPTION 'V20 自检失败：% 张 fin 表的 tenant_id 允许为空', n;
    END IF;

    SELECT COUNT(*) INTO n FROM information_schema.tables
     WHERE table_name IN ('fin_account','fin_voucher','fin_voucher_line','fin_bank_account',
                          'fin_cash_journal','fin_expense','fin_expense_line','fin_invoice');
    IF n <> 8 THEN
        RAISE EXCEPTION 'V20 自检失败：fin 表应恰好 8 张，实际 %', n;
    END IF;

    -- ② 每个现存租户都必须有一套完整的 28 个标准科目（凭证生成按 code 找科目，缺一即哑）。
    SELECT COUNT(*) INTO n FROM tenants t
     WHERE (SELECT COUNT(*) FROM fin_account a WHERE a.tenant_id = t.id) < 28;
    IF n > 0 THEN
        RAISE EXCEPTION 'V20 自检失败：% 个租户的标准科目模板不足 28 条', n;
    END IF;

    -- ③ 凭证幂等锚点必须真实存在（自动生成凭证的重复提交全靠它兜底）。
    SELECT COUNT(*) INTO n FROM pg_constraint
     WHERE conname = 'uq_fin_voucher_source' AND conrelid = 'fin_voucher'::regclass;
    IF n <> 1 THEN
        RAISE EXCEPTION 'V20 自检失败：fin_voucher 缺少 (tenant_id, source_type, source_id) 唯一约束';
    END IF;
END $$;
