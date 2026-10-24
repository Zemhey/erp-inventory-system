-- ============================================================================
-- V15 · MDM 写端点幂等键落地（V2-C1 泳道）
-- 范围：8 个契约已声明 X-Request-Id 但实现不消费的 MDM 写端点
--   创建型 5 个：POST /warehouses /suppliers /customers /products /skus
--   纯开关 3 个：POST /warehouses/{id}/disable /suppliers/{id}/disable /customers/{id}/disable
-- 依据：.plan/V2-C1-计划书.md §2
--
-- ── 设计选择：业务表自带 request_id 列（方案 b），不用通用 idempotency_keys 表（方案 a）──
-- 三条理由：
--   1) **重放必须返回资源的当前状态，不是当时的响应快照**。
--      方案 a 的 response_body 缓存到重放时会把旧内容原样吐回去：
--      建好仓库 → PUT 改名 → 用原幂等键重放 POST，方案 a 返回改名前的 body，
--      调用方据此以为"名字没改成功"。方案 b 回查实体表，永远返回当前值。
--   2) **守卫与它保护的数据同生共死**。守卫行与业务行分处两表时，
--      "守卫写了、业务没写"（或反之）需要额外的事后才可发现；同表同事务则不存在这个缝。
--   3) 与既有实现同构：purchase_orders.request_id（V2）、inventory_transfers.request_id（V9）、
--      purchase_orders.conversion_request_id（V10）都是"业务表列 + 局部唯一索引"。
--      现成的休眠表 idempotency_keys（V1）主键是全局 request_id，
--      会让"同一个键打到两个不同端点"变成硬冲突，与本项目的按端点生成键习惯不符，故不复用它。
--
-- ── 为什么用「局部」唯一索引（WHERE ... IS NOT NULL）──
-- 这三列对绝大多数历史行都是 NULL（迁移前的存量数据、以及不经 HTTP 的内部写入）。
-- PostgreSQL 的普通唯一索引虽然默认放行 NULL，但用 IS NOT NULL 谓词把范围写死有两个好处：
-- 索引只覆盖真正参与幂等的少量行（体积小），且"NULL 不参与唯一性"是声明而非依赖默认行为。
--
-- ── 为什么创建与停用分列 ──
-- 同一行的"建它"和"停它"是两个不同的幂等动作，各用自己的键。
-- 合用一列会让"建完立刻停"撞自己的键。
--
-- 设计原则（延续 V1~V14）：**追加式迁移**。V1~V14 一行不改。
-- 本表可能被后续泳道并发改动，故用 IF NOT EXISTS（与 V14 同一做法：谁先合谁建，后合方不报错）。
-- ============================================================================

-- ─────────────────────────── ① 创建型幂等键（5 张表） ───────────────────────────

ALTER TABLE warehouses
    ADD COLUMN IF NOT EXISTS create_request_id VARCHAR(64);
COMMENT ON COLUMN warehouses.create_request_id IS
    '建仓库的幂等键（X-Request-Id）。同键重放返回首次建成的那一行，不再插入第二行；不变量 INV-9 同构';
CREATE UNIQUE INDEX IF NOT EXISTS uq_warehouse_create_request
    ON warehouses (create_request_id) WHERE create_request_id IS NOT NULL;

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS create_request_id VARCHAR(64);
COMMENT ON COLUMN suppliers.create_request_id IS
    '建供应商的幂等键（X-Request-Id）。同键重放返回首次建成的那一行，不再插入第二行';
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_create_request
    ON suppliers (create_request_id) WHERE create_request_id IS NOT NULL;

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS create_request_id VARCHAR(64);
COMMENT ON COLUMN customers.create_request_id IS
    '建客户的幂等键（X-Request-Id）。同键重放返回首次建成的那一行，不再插入第二行';
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_create_request
    ON customers (create_request_id) WHERE create_request_id IS NOT NULL;

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS create_request_id VARCHAR(64);
COMMENT ON COLUMN products.create_request_id IS
    '建商品的幂等键（X-Request-Id）。同键重放返回首次建成的那一行，不再插入第二行';
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_create_request
    ON products (create_request_id) WHERE create_request_id IS NOT NULL;

ALTER TABLE skus
    ADD COLUMN IF NOT EXISTS create_request_id VARCHAR(64);
COMMENT ON COLUMN skus.create_request_id IS
    '建 SKU 的幂等键（X-Request-Id）。同键重放返回首次建成的那一行，不再插入第二行；
     注意与 uq_sku_variant 是两回事：后者管"商品下颜色×尺码唯一"，前者管"请求唯一"';
CREATE UNIQUE INDEX IF NOT EXISTS uq_sku_create_request
    ON skus (create_request_id) WHERE create_request_id IS NOT NULL;

-- ─────────────────────────── ② 停用型幂等键（3 张表） ───────────────────────────
-- products / skus 的 {id}/disable 契约里没有声明 X-Request-Id（纯开关由状态自身幂等），
-- 因此不建列 —— 建了也没人写，只会留下一列永远为 NULL 的死字段。

ALTER TABLE warehouses
    ADD COLUMN IF NOT EXISTS disable_request_id VARCHAR(64);
COMMENT ON COLUMN warehouses.disable_request_id IS
    '最近一次"由某个幂等键触发的停用"的键。同键重放不再写第二次';
CREATE UNIQUE INDEX IF NOT EXISTS uq_warehouse_disable_request
    ON warehouses (disable_request_id) WHERE disable_request_id IS NOT NULL;

ALTER TABLE suppliers
    ADD COLUMN IF NOT EXISTS disable_request_id VARCHAR(64);
COMMENT ON COLUMN suppliers.disable_request_id IS
    '最近一次"由某个幂等键触发的停用"的键。同键重放不再写第二次';
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_disable_request
    ON suppliers (disable_request_id) WHERE disable_request_id IS NOT NULL;

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS disable_request_id VARCHAR(64);
COMMENT ON COLUMN customers.disable_request_id IS
    '最近一次"由某个幂等键触发的停用"的键。同键重放不再写第二次';
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_disable_request
    ON customers (disable_request_id) WHERE disable_request_id IS NOT NULL;
