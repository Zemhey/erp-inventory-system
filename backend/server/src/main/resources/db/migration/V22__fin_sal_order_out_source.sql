-- V3-5 · 给"出货单直接出货"这条路径补会计凭证来源类型。
--
-- 背景：销售履约在系统里有两条真实入口 ——
--   ① POST /shipments/{id}/confirm        发货单执行（扫码拣货→确认）
--   ② POST /sales-orders/{id}/ship        出货单直接出货
-- V3-2 的自动凭证只钩了 ①（SAL_OUT，来源 id = 发货单 id）。用户从出货单页面点"出货"
-- 走的是 ②，库存扣了、应收记了，**但不产生任何凭证** —— 收入侧总账缺一整块。
--
-- 这里加第 ② 条的来源类型。不复用 SAL_OUT：幂等锚点是
-- uq_fin_voucher_source(tenant_id, source_type, source_id)，而发货单 id 与出货单 id
-- 来自两个不同的序列，同号是常态 —— 复用会让第二张单被当成重复而静默吞掉。
--
-- 原约束是建表时的内联 CHECK，Postgres 自动命名为 fin_voucher_source_type_check。

ALTER TABLE fin_voucher
    DROP CONSTRAINT fin_voucher_source_type_check;

ALTER TABLE fin_voucher
    ADD CONSTRAINT ck_fin_voucher_source_type CHECK (source_type IN
        ('MANUAL','PUR_IN','SAL_OUT','SAL_ORDER_OUT','PUR_PAY','SAL_RCV','EXPENSE','INVOICE'));

COMMENT ON CONSTRAINT ck_fin_voucher_source_type ON fin_voucher IS
    '凭证来源类型。SAL_OUT=发货单确认，SAL_ORDER_OUT=出货单直接出货，二者互斥不重复入账';
