-- ============================================================================
-- V13 · 审计与可观测（FR-SYS-06 / FR-SYS-07 / FR-SYS-08）+ 参数字典表
-- 范围：新增 audit_logs / dict_types / dict_items 三张表 + 只读权限种子
--       不动 V1~V7 的任何已合并文件
--
-- 编号说明：V8~V12 由其它泳道/后续期占用（详见 .plan/V2-5-计划书.md §2），
--           本泳道按批次约定从 V13 起排号，Flyway 允许版本号空洞。
--
-- 设计要点（每一条都对应一条验收）：
--   ① ★ audit_logs 是【追加型】表：应用层没有 update/delete 方法（ArchUnit 守），
--      表上【没有 updated_at】，并用触发器硬拒绝 UPDATE / DELETE。
--      "审计可改"等于"审计不可信"，光靠代码约定守不住 —— 一次手写 SQL 的运维操作
--      就能把痕迹抹掉。触发器让篡改在数据库层就失败。
--   ② 索引按三种真实查法建：(时间倒序) / (谁在什么时间做了什么) / (按单据号追溯)。
--   ③ tenant_id 为多租户预留（可空默认 1），本期【不实现】任何租户过滤逻辑 ——
--      提前埋列的成本近乎为零，而事后给 61 张表加列并回填是灾难。
--   ④ before_json / after_json 用 JSONB 而不是 TEXT：审计查询要能按字段值过滤与
--      还原变更前后内容，JSONB 支持索引与 -> 取值，TEXT 只能整段字符串比较。
-- ============================================================================

-- ─────────────────────────── 审计日志（追加型） ───────────────────────────

CREATE TABLE audit_logs (
    id            BIGSERIAL    PRIMARY KEY,
    -- 多租户预留：本期恒为 1，不做任何租户过滤（V2-7 才实现隔离）
    tenant_id     BIGINT       NOT NULL DEFAULT 1,
    -- 操作者：登录失败等匿名场景允许为空
    actor_id      BIGINT,
    actor_name    VARCHAR(64),
    -- 动作语义（CREATE / UPDATE / DISABLE / SUBMIT / APPROVE / RECEIVE / SHIP / ...）
    action        VARCHAR(32)  NOT NULL,
    -- 被操作的资源类型（PRODUCT / PURCHASE_ORDER / INVENTORY / ...）
    resource_type VARCHAR(64)  NOT NULL,
    -- 资源主键；本表不建外键 —— 审计必须能在业务行被物理删除后依然完整
    resource_id   VARCHAR(64),
    -- 业务单据号：按单据追溯"这张单被谁动过"的唯一入口
    biz_ref_no    VARCHAR(64),
    before_json   JSONB,
    after_json    JSONB,
    -- traceId 贯穿：请求头 → MDC → 响应头 → 本列，四者同值（FR-SYS-08）
    trace_id      VARCHAR(64),
    ip            VARCHAR(64),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 结果：成功的写操作也要留痕（"谁在什么时候改了什么"），失败更要留
    result        VARCHAR(16)  NOT NULL,
    error_code    VARCHAR(64),

    CONSTRAINT ck_audit_logs_result CHECK (result IN ('SUCCESS', 'FAIL'))
);

COMMENT ON TABLE audit_logs IS '操作审计（FR-SYS-06）。★追加型：无 updated_at，触发器禁止 UPDATE/DELETE';
COMMENT ON COLUMN audit_logs.tenant_id IS '多租户预留，本期恒为 1（V2-7 才实现隔离）';
COMMENT ON COLUMN audit_logs.before_json IS '变更前字段值快照（取自该资源上一次审计的 after_json）';
COMMENT ON COLUMN audit_logs.after_json IS '变更后字段值快照（取自本次响应 data）';
COMMENT ON COLUMN audit_logs.trace_id IS '与请求头 X-Trace-Id / MDC / 响应头同值，用于按 traceId 捞全部日志';

-- 三种真实查法各一条索引（不要为"可能会查"乱建索引：审计表是增长最快的表）
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_logs_actor       ON audit_logs (actor_id, occurred_at);
CREATE INDEX idx_audit_logs_biz_ref_no  ON audit_logs (biz_ref_no);

-- ★ 不可篡改的数据库层保证：任何 UPDATE / DELETE 直接报错，而不是静默生效。
CREATE OR REPLACE FUNCTION audit_logs_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs 是追加型表，禁止 % （审计不可篡改，见 FR-SYS-06）', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();

-- ─────────────────────────── 参数字典（FR-SYS-07 的配套读模型） ───────────────────────────

CREATE TABLE dict_types (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL DEFAULT 1,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(200),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_dict_types_code UNIQUE (code)
);
COMMENT ON TABLE dict_types IS '字典类型（如 COLOR / SIZE / SEASON / UNIT）';

CREATE TABLE dict_items (
    id        BIGSERIAL    PRIMARY KEY,
    type_id   BIGINT       NOT NULL REFERENCES dict_types (id) ON DELETE CASCADE,
    code      VARCHAR(64)  NOT NULL,
    label     VARCHAR(128) NOT NULL,
    sort_no   INTEGER      NOT NULL DEFAULT 0,
    enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_dict_items_type_code UNIQUE (type_id, code)
);
COMMENT ON TABLE dict_items IS '字典项。按 (type_id, sort_no) 排序返回';

CREATE INDEX idx_dict_items_type ON dict_items (type_id, sort_no);

-- 种子：只种系统自带的通用维度，业务字典由运营在界面维护
INSERT INTO dict_types (code, name, description) VALUES
    ('COLOR',  '颜色', '商品 SKU 的颜色维度'),
    ('SIZE',   '尺码', '商品 SKU 的尺码维度'),
    ('SEASON', '季节', '商品季节/波段维度'),
    ('UNIT',   '单位', '数量计量单位');

INSERT INTO dict_items (type_id, code, label, sort_no)
SELECT t.id, v.code, v.label, v.sort_no
FROM dict_types t
JOIN (VALUES
    ('COLOR',  'BLACK',  '黑色', 10),
    ('COLOR',  'WHITE',  '白色', 20),
    ('COLOR',  'RED',    '红色', 30),
    ('COLOR',  'BLUE',   '蓝色', 40),
    ('SIZE',   'XS',     'XS',   10),
    ('SIZE',   'S',      'S',    20),
    ('SIZE',   'M',      'M',    30),
    ('SIZE',   'L',      'L',    40),
    ('SIZE',   'XL',     'XL',   50),
    ('SEASON', 'SS',     '春夏', 10),
    ('SEASON', 'AW',     '秋冬', 20),
    ('UNIT',   'PCS',    '件',   10),
    ('UNIT',   'PAIR',   '双',   20),
    ('UNIT',   'SET',    '套',   30)
) AS v(type_code, code, label, sort_no) ON t.code = v.type_code;

-- ─────────────────────────── 权限种子（只追加，不新建账号） ───────────────────────────
-- ADMIN 的 sys:* 只是数据完备；真正的放行由 PermissionAspect 的 ROLE_ADMIN 短路决定。
-- 审计是"谁都能看自己的、但不是谁都能看全公司的"：本期只给 ADMIN 与只读角色 sys:read，
-- 数据权限细化留给 V2-6（数据权限按仓/本人）。
INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN',  'sys:*'),
    ('VIEWER', 'sys:read'),
    ('FINANCE','sys:read')
) AS p(role_code, permission) ON r.code = p.role_code;
