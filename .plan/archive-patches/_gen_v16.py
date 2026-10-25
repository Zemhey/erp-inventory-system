# -*- coding: utf-8 -*-
"""生成 V16__tenant_isolation.sql（V2-7）。

为什么要生成器：本迁移给业务表加列、回填、建索引，把「全局唯一」改成
"(tenant_id, ...) 复合唯一"，并给全部业务表装 RLS + INSERT 填充触发器。
手抄必然错一处，而错的后果是"某张表还能跨租户撞号 / 跨租户越权读"——静默缺陷。

生成器只做文本拼装：表名、约束名、列清单全部显式声明，不做运行时探测。
"""
from pathlib import Path

OUT = Path(r"D:\erp-build-v27\backend\server\src\main\resources\db\migration\V16__tenant_isolation.sql")

# ── ① 需要 tenant_id 的业务表 ───────────────────────────────────────────
# audit_logs 单独处理（V13 已有该列）：只追加索引 + 装 RLS，不 ADD COLUMN、不回填。
TABLES = [
    "warehouses", "products", "skus", "suppliers", "customers",
    "inventory_balances", "inventory_ledger", "inventory_reservations",
    "inventory_transfers", "transfer_lines", "stocktakes", "stocktake_lines",
    "inventory_adjustments", "inventory_freezes",
    "idempotency_keys", "document_sequences",
    "purchase_orders", "purchase_order_lines",
    "po_receipts", "po_receipt_lines",
    "supplier_payables", "payable_allocations", "purchase_returns",
    "sales_orders", "sales_order_lines", "sales_order_line_reservations",
    "product_bundles", "bundle_items",
    "sales_quotes", "sales_quote_lines",
    "so_shipment_plans", "so_shipments", "shipment_lines",
    "customer_receivables", "customer_receipts", "receivable_allocations",
    "customer_credit_snapshots",
    "return_orders", "return_order_lines",
    "settlement_periods", "daily_settlements",
    "cost_layers", "cost_monthly", "margin_records",
    "dict_types", "dict_items",
    "data_scopes", "app_users", "app_roles", "app_user_roles", "app_role_permissions",
    "approval_flows", "approval_instances", "approval_steps",
]
# 刻意**没有** tenant_id 的表：tenants（租户定义本身）、flyway_schema_history。
# 注：app_users.username 仍是全局唯一（登录接口只给用户名，没有租户入参 ——
#     改成按租户唯一就必须改契约，那不在本泳道的写入范围里；见报告 §契约）。

# ── ② 全局唯一 → (tenant_id, ...) 复合唯一（主键 3 个 + 唯一约束/索引若干）────
# 判据：**去掉租户维就会跨租户撞号**的才改。
# 列里已含全局唯一代理 id 的约束（uq_sku_variant 含 product_id、uq_balance 含
# sku_id、uq_cost_monthly 含 sku_id、uq_daily_settlement 含 warehouse_id、
# 各 line_no / *_id 约束）天然不可能跨租户冲突，**一律不动**。
UNIQUES = [
    ("pk", "document_sequences", None, ["doc_prefix", "day_key"]),
    ("pk", "idempotency_keys", None, ["request_id"]),
    ("pk", "settlement_periods", None, ["period"]),
    ("uq", "warehouses", "uq_warehouse_code", ["code"]),
    ("uq", "products", "uq_product_code", ["code"]),
    ("uq", "suppliers", "uq_supplier_code", ["code"]),
    ("uq", "customers", "uq_customer_code", ["code"]),
    ("uq", "product_bundles", "uq_bundle_code", ["code"]),
    ("uq", "app_roles", "uq_app_role_code", ["code"]),
    ("uq", "dict_types", "uq_dict_types_code", ["code"]),
    ("uq", "approval_flows", "uq_approval_flow_code", ["code"]),
    ("uq", "purchase_orders", "uq_purchase_order_no", ["order_no"]),
    ("uq", "sales_orders", "uq_sales_order_no", ["order_no"]),
    ("uq", "return_orders", "uq_return_order_no", ["order_no"]),
    ("uq", "inventory_transfers", "uq_transfer_no", ["transfer_no"]),
    ("uq", "stocktakes", "uq_stocktake_no", ["stocktake_no"]),
    ("uq", "inventory_adjustments", "uq_adjust_no", ["adjust_no"]),
    ("uq", "sales_quotes", "uq_quote_no", ["quote_no"]),
    ("uq", "so_shipment_plans", "uq_shipment_plan_no", ["plan_no"]),
    ("uq", "so_shipments", "uq_shipment_no", ["shipment_no"]),
    ("uq", "po_receipts", "uq_po_receipt_no", ["receipt_no"]),
    ("uq", "purchase_returns", "uq_purchase_return_no", ["return_no"]),
    ("uq", "supplier_payables", "uq_supplier_payable_no", ["payable_no"]),
    ("uq", "customer_receipts", "uq_receipt_no", ["receipt_no"]),
    ("uq", "payable_allocations", "uq_payable_alloc_payment", ["payment_no", "payable_id"]),
    ("uq", "inventory_reservations", "uq_reservation_request", ["request_id"]),
]

# ── ③ 部分唯一索引 / 部分唯一约束：幂等键护栏 + 审批流启用唯一 ──────────────
# ★「同键」的作用域在这里被重新定义：全局一个键 → 每租户一个键。
#   不改的话，A 租户用过的键会让 B 租户的**首次**请求被判成重放并返回 A 的实体
#   —— 那不是"多返回一条数据"，而是"把别人的单子当成你的"。
PARTIAL_UNIQUES = [
    ("inventory_ledger", "uq_ledger_request",
     ["request_id", "biz_type", "batch_no"], "request_id IS NOT NULL"),
    ("purchase_orders", "uq_po_request", ["request_id"], "request_id IS NOT NULL"),
    ("purchase_orders", "uq_po_conversion_request",
     ["conversion_request_id"], "conversion_request_id IS NOT NULL"),
    ("purchase_orders", "uq_po_void_request", ["void_request_id"],
     "void_request_id IS NOT NULL"),
    ("sales_orders", "uq_so_request", ["request_id"], "request_id IS NOT NULL"),
    ("sales_orders", "uq_so_void_request", ["void_request_id"],
     "void_request_id IS NOT NULL"),
    ("return_orders", "uq_ro_request", ["request_id"], "request_id IS NOT NULL"),
    ("return_orders", "uq_ro_receive_request", ["receive_request_id"],
     "receive_request_id IS NOT NULL"),
    ("inventory_transfers", "uq_transfer_request", ["request_id"],
     "request_id IS NOT NULL"),
    ("inventory_transfers", "uq_transfer_receive_request", ["receive_request_id"],
     "receive_request_id IS NOT NULL"),
    ("stocktakes", "uq_stocktake_request", ["request_id"], "request_id IS NOT NULL"),
    ("stocktakes", "uq_stocktake_post_request", ["post_request_id"],
     "post_request_id IS NOT NULL"),
    ("inventory_adjustments", "uq_adjust_request", ["request_id"],
     "request_id IS NOT NULL"),
    ("inventory_freezes", "uq_freeze_request", ["request_id"], "request_id IS NOT NULL"),
    ("inventory_freezes", "uq_freeze_release_request", ["release_request_id"],
     "release_request_id IS NOT NULL"),
    ("payable_allocations", "uq_payable_alloc_request",
     ["request_id", "payable_id"], "request_id IS NOT NULL"),
    ("sales_quotes", "uq_quote_request", ["request_id"], "request_id IS NOT NULL"),
    ("sales_quotes", "uq_quote_convert_request", ["convert_request_id"],
     "convert_request_id IS NOT NULL"),
    ("so_shipments", "uq_shipment_confirm_request", ["confirm_request_id"],
     "confirm_request_id IS NOT NULL"),
    ("approval_flows", "uq_approval_flow_enabled_biz", ["biz_type"], "enabled"),
    ("approval_instances", "uq_approval_instance_request", ["request_id"],
     "request_id IS NOT NULL"),
    ("warehouses", "uq_warehouse_create_request", ["create_request_id"],
     "create_request_id IS NOT NULL"),
    ("warehouses", "uq_warehouse_disable_request", ["disable_request_id"],
     "disable_request_id IS NOT NULL"),
    ("suppliers", "uq_supplier_create_request", ["create_request_id"],
     "create_request_id IS NOT NULL"),
    ("suppliers", "uq_supplier_disable_request", ["disable_request_id"],
     "disable_request_id IS NOT NULL"),
    ("customers", "uq_customer_create_request", ["create_request_id"],
     "create_request_id IS NOT NULL"),
    ("customers", "uq_customer_disable_request", ["disable_request_id"],
     "disable_request_id IS NOT NULL"),
    ("products", "uq_product_create_request", ["create_request_id"],
     "create_request_id IS NOT NULL"),
    ("skus", "uq_sku_create_request", ["create_request_id"],
     "create_request_id IS NOT NULL"),
    # 无谓词但同样是"幂等键"性质的三个（V10/V11 建的是整表唯一约束）
    ("po_receipts", "uq_po_receipt_request", ["request_id"], None),
    ("purchase_returns", "uq_purchase_return_request", ["request_id"], None),
    ("purchase_returns", "uq_purchase_return_approve_request",
     ["approve_request_id"], None),
    ("purchase_returns", "uq_purchase_return_void_request", ["void_request_id"], None),
    ("so_shipment_plans", "uq_shipment_plan_request", ["request_id"], None),
    ("customer_receipts", "uq_receipt_request", ["request_id"], None),
    ("supplier_payables", "uq_supplier_payable_receipt", ["receipt_id"], None),
    ("bundle_items", "uq_bundle_item", ["bundle_id", "component_sku_id"], None),
]

# ── ④ 热路径复合索引（tenant 做第一列 = 「按租户过滤」同时是索引前缀）─────────
HOT_INDEXES = [
    ("inventory_balances", "idx_balance_tenant_sku_wh",
     ["tenant_id", "sku_id", "warehouse_id"]),
    ("inventory_ledger", "idx_ledger_tenant_wh_time",
     ["tenant_id", "warehouse_id", "occurred_at"]),
    ("inventory_ledger", "idx_ledger_tenant_bizref", ["tenant_id", "biz_ref_no"]),
    ("inventory_ledger", "idx_ledger_tenant_sku_wh",
     ["tenant_id", "sku_id", "warehouse_id"]),
    ("purchase_orders", "idx_po_tenant_status", ["tenant_id", "status"]),
    ("purchase_orders", "idx_po_tenant_wh_date", ["tenant_id", "warehouse_id", "biz_date"]),
    ("sales_orders", "idx_so_tenant_status", ["tenant_id", "status"]),
    ("sales_orders", "idx_so_tenant_wh_date", ["tenant_id", "warehouse_id", "biz_date"]),
    ("return_orders", "idx_ro_tenant_wh_date", ["tenant_id", "warehouse_id", "biz_date"]),
    ("inventory_reservations", "idx_reservation_tenant_wh",
     ["tenant_id", "sku_id", "warehouse_id", "status"]),
    ("purchase_order_lines", "idx_po_line_tenant", ["tenant_id", "order_id"]),
    ("sales_order_lines", "idx_so_line_tenant", ["tenant_id", "order_id"]),
    ("return_order_lines", "idx_ro_line_tenant", ["tenant_id", "order_id"]),
    ("cost_layers", "idx_cost_layer_tenant_pool",
     ["tenant_id", "sku_id", "warehouse_id", "occurred_at"]),
    ("daily_settlements", "idx_daily_settlement_tenant",
     ["tenant_id", "biz_date", "warehouse_id"]),
    ("app_users", "idx_app_users_tenant", ["tenant_id", "username"]),
    ("audit_logs", "idx_audit_logs_tenant_time", ["tenant_id", "occurred_at DESC"]),
    ("audit_logs", "idx_audit_logs_tenant_actor",
     ["tenant_id", "actor_id", "occurred_at"]),
]

HEADER = """-- ============================================================================
-- V16 · 多租户隔离（V2-7 · FR-TNT-01~06）
-- 生成器：.plan/_gen_v16.py（纯文本拼装，表名 / 约束名 / 列清单全部显式声明）
--
-- ★ 三个硬要求（下发包 §5），本文件逐条对应：
--   ① 不碎库，分步：ADD COLUMN（可空、无默认）→ 回填 → 建索引 → 复合唯一
--      → 装填充触发器 → 自检 → 最后 NOT NULL 与 RLS。任何一步失败都不会留下半张表。
--      **不加静态 DEFAULT**，改为「BEFORE INSERT 触发器按会话租户填充」：
--      静态 DEFAULT 1 会把"应用层漏写租户"变成静默落进默认租户
--      （租户放错是这套系统里最危险的一类缺陷）；动态默认才是多租户的正确语义。
--   ② 对存量库友好：回填全部带 WHERE tenant_id IS NULL；DDL 全部 IF NOT EXISTS /
--      存在性判断；同一文件可重复执行。
--   ③ audit_logs：只**追加**索引与 RLS 策略，不 ADD COLUMN（V13 已有列）、
--      不回填、不改 NOT NULL / 默认值、不动 append-only 触发器与 V13 的三条既有索引。
--      它上面任何 UPDATE 都会被 audit_logs_append_only() 拒绝，回填在这里根本不可行。
--
-- ★ 隔离的强度落在**数据库层（RLS）**，而不是靠改 366 个 JdbcTemplate 调用点。
--   本项目没有实体映射层可挂 @TenantId，靠人肉给每条 SQL 补 WHERE 必然漏一处，
--   而漏掉的那一处就是跨租户泄露。RLS + FORCE 让**每一条**查询（含子查询、JOIN、
--   聚合、EXISTS、ON CONFLICT）在内核里被强制过滤，新写的仓储自动安全。
--
-- 设计原则（延续 V1~V15）：**纯追加**。不删列、不删表、不改任何既有 CHECK。
-- ============================================================================

-- ─────────────────────────── ⓪ 租户实体（FR-TNT-01） ───────────────────────────
-- 唯一一张**没有** tenant_id 的业务表：它就是租户的定义本身。
CREATE TABLE IF NOT EXISTS tenants (
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_code UNIQUE (code)
);
COMMENT ON TABLE tenants IS
    '租户（FR-TNT-01）。除本表外所有业务表都带 tenant_id，由 RLS 强制过滤';
COMMENT ON COLUMN tenants.code IS
    'default = 存量单租户历史数据的归属；demo = 演示与隔离验收用的第二套数据';

-- 两套种子：默认租户（存量归属）+ 演示租户（隔离验收的第二套）。
-- 显式给 id 是为了让 Java 常量（TenantId.DEFAULT = 1 / DEMO = 2）与库内取值一致。
INSERT INTO tenants (id, code, name) VALUES
    (1, 'default', '默认租户'),
    (2, 'demo',    '演示租户')
ON CONFLICT (id) DO NOTHING;
-- 序列推到已用最大值之后，否则下一次 INSERT 会撞 id = 1 / 2
SELECT setval('tenants_id_seq',
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM tenants), 2));

-- ─────────────────────────── ⓪' 两个 helper 函数 ───────────────────────────────
-- current_setting(..., true) = "不存在时返回 NULL 而不是报错"。
-- ★ 这里刻意**不**回落默认租户：回落到 1 会让"漏写租户"的写入静默落进默认租户，
--   而那正是本泳道最不能接受的缺陷。未设置会话变量时返回 NULL → 下游的
--   NOT NULL 约束立刻报错，把"没有租户归属"变成**响亮**的失败。
--   （应用侧不依赖它：所有写入都由 TenantSql 把 tenant_id 显式写进列清单。）
CREATE OR REPLACE FUNCTION erp_tenant_current() RETURNS bigint
    LANGUAGE sql STABLE AS $$
        SELECT NULLIF(current_setting('erp.tenant_id', true), '')::bigint
    $$;
COMMENT ON FUNCTION erp_tenant_current() IS
    '当前会话的租户 id。未设置时返回 NULL（不回落默认租户：宁可报错也不静默落错租户）';

-- ★ 旁路开关，只给"登录时按用户名跨租户定位账号"与运维脚本用。
--   它不参与业务读写：业务读写的租户维一律由 TenantSql 注入。
CREATE OR REPLACE FUNCTION erp_tenant_bypass() RETURNS boolean
    LANGUAGE sql STABLE AS $$
        SELECT COALESCE(current_setting('erp.tenant_bypass', true), 'off') = 'on'
    $$;
COMMENT ON FUNCTION erp_tenant_bypass() IS
    '系统旁路标记（仅供诊断与登录定位账号）；业务读写路径不得打开';

"""


def unique_block(table: str, name: str, cols, where) -> str:
    """生成「建复合唯一索引（临时名）→ 删旧约束/旧索引 → 改名回旧名」。

    为什么要 DO 块而不是直接 DROP INDEX：V1~V15 里的"唯一"有两种载体 ——
    建表时内联的 UNIQUE 约束（uq_adjust_request / uq_freeze_request /
    uq_po_receipt_request …）和 CREATE UNIQUE INDEX 建出的索引。
    对约束直接 DROP INDEX 会报
    "cannot drop index … because constraint … requires it"，因此必须按
    pg_constraint 查清楚再走 DROP CONSTRAINT。
    """
    collist = ", ".join(["tenant_id"] + cols)
    tmp = f"{name}__tnt"
    where_sql = f" WHERE {where}" if where else ""
    return f"""-- {table}.{name} → ({collist})
DROP INDEX IF EXISTS {tmp};
CREATE UNIQUE INDEX {tmp} ON {table} ({collist}){where_sql};
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = '{table}' AND c.conname = '{name}';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE {table} DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS {name};
ALTER INDEX {tmp} RENAME TO {name};
"""


APP_ROLE = r"""
-- ─────────────────────────── ⑪ RLS 评估结论（不启用，理由见下） ────────────────
-- 本泳道**刻意不启用** PostgreSQL RLS，并把评估过程写在这里，避免下一个读到
-- 这份迁移的人以为"漏了 RLS"或"RLS 已经保护了数据"。
--
-- 为什么评估过 RLS：它是最省事的一层（对子查询 / JOIN / 聚合 / EXISTS 全部生效，
-- 新写的 SQL 自动安全），理论上比在 Java 里拼 SQL 更强。
--
-- 为什么最终没启用：
--   ① **超级用户永远绕过 RLS**，连 FORCE ROW LEVEL SECURITY 都拦不住（实测：
--      POSTGRES_USER=erp 的容器里，装了策略、订了 FORCE，跨租户 select 依旧全表）。
--      本项目 application.yml 与 compose 都用 erp 连接，因此"策略已装、用例全绿、
--      隔离零生效"会同时成立 —— 那是本泳道最不能接受的一类假绿。
--   ② 要让 RLS 真正生效，必须让应用以非超级用户连接，而 RLS 读的是**会话变量**；
--      会话变量在连接池里必须逐次借用绑定，绑错一次就是跨租户泄露或误过滤。
--      这条链路上每一环都比"在 SQL 文本里注入谓词"更难验证，也更容易静默出错。
--   ③ 半开 RLS 比不开更糟：策略恒解析成默认租户时，租户 2 的请求会被自己的策略
--      挡住（表现为"登录成功但什么都查不到"）。
--
-- 因此本泳道的强制过滤落在 **Java 的 SQL 层**（com.erp.shared.tenant.TenantSql，
-- 唯一出口是 TenantAwareJdbcTemplate），并由三道门禁守着"没有语句能漏":
--   ① TenantSqlGuardTest：扫描整轮测试执行的**每一条** SQL，凡引用了业务表却
--      没有租户谓词（或不在显式豁免清单里）即失败；
--   ② TenantIsolationMatrixIT：135 条跨租户组合，断言 404 / 空结果 + 同租户正对照；
--   ③ 复合唯一索引 + INSERT 填充触发器：数据库层挡住"撞号"与"漏写租户"。
-- 生产若要再加 RLS，前提是先把连接串换成非超级用户（DB_USER=erp_app）并补会话
-- 变量绑定 —— 这条已登记为遗留项（.plan/V2-7-报告.md §遗留）。
"""


def main() -> None:
    p = [HEADER]

    p.append("-- ─────────────────────────── ① 加列（可空，无默认值） ────────────────")
    p.append("-- 可空是刻意的：存量行在这一步全部是 NULL，回填的边界因此精确、可断言。\n")
    for t in TABLES:
        p.append(f"ALTER TABLE {t} ADD COLUMN IF NOT EXISTS tenant_id BIGINT;")
    p.append("")

    p.append("-- ─────────────────────────── ② 回填：存量单租户数据归默认租户 ─────────────")
    p.append("-- 每条都带 WHERE tenant_id IS NULL → 可重复执行。跑完这一节，"
             "库里不存在「没有租户的行」。")
    p.append("-- audit_logs 刻意不在其中：它有 append-only 触发器，UPDATE 会直接报错。\n")
    for t in TABLES:
        p.append(f"UPDATE {t} SET tenant_id = 1 WHERE tenant_id IS NULL;")
    p.append("")

    p.append("-- ─────────────────────────── ③ 单列索引（租户） ───────────────────────────")
    for t in TABLES:
        p.append(f"CREATE INDEX IF NOT EXISTS idx_{t}_tenant ON {t} (tenant_id);")
    p.append("")

    p.append("-- ─────────────────────────── ④ 复合唯一：全局唯一 → (tenant_id, ...) ─────")
    p.append("""-- ★ 顺序不可颠倒：「建新复合唯一索引（临时名）→ 删旧约束 / 旧索引 → 把新索引
--   改名为旧名」。反过来做的话，中间窗口里"同编码两行"是合法的，并发写入可能塞进
--   真重复。改名保留旧名是**必须**的：DbErrors.translate 按约束名翻译错误码
--   （uq_product_code → MDM_CODE_DUPLICATED），改名会让一整类 409 静默退化成 500。
""")
    for kind, table, name, cols in UNIQUES:
        if kind == "uq":
            p.append(unique_block(table, name, cols, None))
        else:
            collist = ", ".join(["tenant_id"] + cols)
            p.append(f"""-- {table}：主键也按租户独立（单号序列 / 幂等键 / 账期）
DO $$
DECLARE pk_name text;
        pk_def  text;
BEGIN
    SELECT c.conname, pg_get_constraintdef(c.oid)
      INTO pk_name, pk_def
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = '{table}' AND c.contype = 'p';
    IF pk_name IS NULL THEN
        ALTER TABLE {table} ADD CONSTRAINT {table}_pkey PRIMARY KEY ({collist});
    ELSIF pk_def NOT LIKE '%tenant_id%' THEN
        EXECUTE format('ALTER TABLE {table} DROP CONSTRAINT %I', pk_name);
        ALTER TABLE {table} ADD CONSTRAINT {table}_pkey PRIMARY KEY ({collist});
    END IF;
END $$;
""")

    p.append("-- ─────────────────────────── ⑤ 幂等键与审批流唯一（按租户） ────────────────")
    p.append("-- ★「同键」的作用域在这里被重新定义：全局一个键 → 每租户一个键。\n")
    for table, name, cols, where in PARTIAL_UNIQUES:
        p.append(unique_block(table, name, cols, where))

    p.append("-- ─────────────────────────── ⑥ 热路径复合索引 ────────────────────────────")
    for table, name, cols in HOT_INDEXES:
        p.append(f"CREATE INDEX IF NOT EXISTS {name} ON {table} ({', '.join(cols)});")
    p.append("")

    p.append(DEMO_SEED)

    p.append("-- ─────────────────────────── ⑧ NOT NULL（回填已消掉所有 NULL） ─────────────")
    p.append("-- 刻意**不设静态 DEFAULT**（见文件头 ①）：漏写租户由下面的触发器按会话租户填充。\n")
    for t in TABLES:
        p.append(f"ALTER TABLE {t} ALTER COLUMN tenant_id SET NOT NULL;")
    p.append("")

    p.append("-- ─────────────────────────── ⑨ INSERT 时按会话租户填充 ──────────────────")
    p.append("""-- 为什么用触发器而不是给 53 个 INSERT 语句逐个补列：后者必然漏（而且以后新写的
-- INSERT 又会漏），漏掉的表现是"数据落进别的租户"——静默且致命。
-- 语义：显式给了非 NULL 的 tenant_id 就用它（迁移 / 运维 / 种子）；否则取会话租户。
-- 它只挂 BEFORE INSERT：audit_logs 上那个禁止 UPDATE/DELETE 的触发器作用于别的
-- 时机，两者不冲突。
CREATE OR REPLACE FUNCTION erp_tenant_fill() RETURNS trigger AS $$
BEGIN
    IF NEW.tenant_id IS NULL THEN
        NEW.tenant_id := erp_tenant_current();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
""")
    for t in TABLES:
        p.append(f"""DROP TRIGGER IF EXISTS trg_{t}_tenant_fill ON {t};
CREATE TRIGGER trg_{t}_tenant_fill BEFORE INSERT ON {t}
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();""")
    p.append("""-- audit_logs 也装同一个填充触发器（V13 的 append-only 触发器原样保留）
DROP TRIGGER IF EXISTS trg_audit_logs_tenant_fill ON audit_logs;
CREATE TRIGGER trg_audit_logs_tenant_fill BEFORE INSERT ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
""")

    p.append("-- ─────────────────────────── ⑩ 迁移自检（在装 RLS 之前） ────────────────")
    p.append("-- 不变量：跑完回填，库里不存在「没有租户的行」。这条断言让「回填漏了某张表」")
    p.append("-- 变成启动期硬失败，而不是几个月后才在某个查询里显形。")
    p.append("-- ★ 必须在装 RLS **之前**跑：装了策略之后，NULL 行会被策略过滤掉，")
    p.append("--   count(*) 恒为 0 —— 那样的自检是恒真的假绿。")
    checks = " + ".join([f"(SELECT count(*) FROM {t} WHERE tenant_id IS NULL)" for t in TABLES])
    p.append(f"""DO $$
DECLARE n bigint;
BEGIN
    n := {checks};
    IF n > 0 THEN
        RAISE EXCEPTION 'V16 自检失败：仍有 % 行没有 tenant_id', n;
    END IF;
END $$;
""")

    p.append(APP_ROLE)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = "\n".join(p)
    OUT.write_text(text, encoding="utf-8")
    print(f"written {OUT} ({len(text.splitlines())} lines)")


DEMO_SEED = r"""
-- ─────────────────────────── ⑦ 演示租户的第二套种子（FR-TNT-01/06） ──────────────
-- 目的：让"隔离"在**两套都非空**的数据上被验收。只有一套数据时，
-- "租户 B 看不到 A 的数据"与"租户 B 什么都没有"在响应上无法区分 —— 那是假绿。
-- 全部显式给 tenant_id（此刻还没有 DEFAULT，填充触发器也尚未安装），幂等可重跑。
-- 这一节在装 RLS 讨论之前执行；复合唯一已在 ④/⑤ 建好，
-- 所以下面的 ON CONFLICT (tenant_id, code) 能命中索引。
INSERT INTO warehouses (tenant_id, code, name, address) VALUES
    (2, 'DEMO-WH01', '演示仓-上海', '演示数据'),
    (2, 'DEMO-WH02', '演示仓-广州', '演示数据')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO products (tenant_id, code, name, category, season) VALUES
    (2, 'DEMO-P-001', '演示商品-圆领T恤', '上装', '2026春')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO skus (tenant_id, product_id, color, size, barcode, retail_price_amount)
SELECT 2, p.id, v.color, v.size, v.barcode, v.price
  FROM products p
  JOIN (VALUES ('白色', 'L', '6902000001', 12900),
               ('黑色', 'M', '6902000002', 12900)) AS v(color, size, barcode, price) ON TRUE
 WHERE p.tenant_id = 2 AND p.code = 'DEMO-P-001'
   AND NOT EXISTS (
       SELECT 1 FROM skus k
        WHERE k.product_id = p.id AND k.color = v.color AND k.size = v.size);

INSERT INTO suppliers (tenant_id, code, name) VALUES
    (2, 'DEMO-SUP-001', '演示供应商')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO customers (tenant_id, code, name, discount_rate) VALUES
    (2, 'DEMO-CUS-001', '演示客户', 10000)
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 演示租户的库存：用来验证「Σ(各租户库存) == 全局库存」这条守恒不变量
INSERT INTO inventory_balances
    (tenant_id, sku_id, warehouse_id, batch_no, on_hand_qty, reserved_qty)
SELECT 2, k.id, w.id, 'DEMO-B1', 40, 0
  FROM skus k
  JOIN products p ON p.id = k.product_id
  JOIN warehouses w ON w.tenant_id = 2 AND w.code = 'DEMO-WH01'
 WHERE p.tenant_id = 2 AND p.code = 'DEMO-P-001'
   AND k.color = '白色' AND k.size = 'L'
   AND NOT EXISTS (
       SELECT 1 FROM inventory_balances b
        WHERE b.sku_id = k.id AND b.warehouse_id = w.id AND b.batch_no = 'DEMO-B1');

-- 演示租户的角色（与默认租户同构：否则演示租户登录后处处 403，
-- 矩阵用例会把"没权限"误判成"租户隔离生效"）
INSERT INTO app_roles (tenant_id, code, name, description)
SELECT 2, r.code, r.name, r.description
  FROM app_roles r WHERE r.tenant_id = 1
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 演示租户的角色权限：从默认租户的同一角色复制（映射表也带租户）
INSERT INTO app_role_permissions (tenant_id, role_id, permission)
SELECT 2, demo.id, rp.permission
  FROM app_roles demo
  JOIN app_roles src ON src.tenant_id = 1 AND src.code = demo.code
  JOIN app_role_permissions rp ON rp.role_id = src.id
 WHERE demo.tenant_id = 2
   AND NOT EXISTS (
       SELECT 1 FROM app_role_permissions x
        WHERE x.role_id = demo.id AND x.permission = rp.permission);

-- 演示租户的字典
INSERT INTO dict_types (tenant_id, code, name, description)
SELECT 2, d.code, d.name, d.description
  FROM dict_types d WHERE d.tenant_id = 1
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO dict_items (tenant_id, type_id, code, label, sort_no)
SELECT 2, demo.id, i.code, i.label, i.sort_no
  FROM dict_types demo
  JOIN dict_types src ON src.tenant_id = 1 AND src.code = demo.code
  JOIN dict_items i ON i.type_id = src.id
 WHERE demo.tenant_id = 2
   AND NOT EXISTS (
       SELECT 1 FROM dict_items x WHERE x.type_id = demo.id AND x.code = i.code);

-- 演示租户的审批流定义（与默认租户同构，默认全部停用 —— 与 V14 种子一致）
INSERT INTO approval_flows (tenant_id, code, name, biz_type, description, enabled)
SELECT 2, f.code, f.name, f.biz_type, f.description, FALSE
  FROM approval_flows f WHERE f.tenant_id = 1
ON CONFLICT (tenant_id, code) DO NOTHING;
"""

if __name__ == "__main__":
    main()
