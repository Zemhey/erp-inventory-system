package com.erp.support;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试数据工厂（docs/06-测试策略.md §5）。
 *
 * <p>用<b>语义化构造器</b>代替硬编码 JSON/SQL 片段：
 * 读用例时看到的是"一个上海仓的白色 L 码 T 恤，实存 10 件"，
 * 而不是一堆 INSERT 语句。这让用例可读、可复用、改一处即可全局生效。
 */
public class TestDataFactory {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private final JdbcTemplate jdbc;

    public TestDataFactory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String uniqueCode(String prefix) {
        return prefix + "-" + SEQ.getAndIncrement();
    }

    public long warehouse(String code) {
        jdbc.update("""
                INSERT INTO warehouses (code, name) VALUES (?, ?)
                ON CONFLICT (code) DO NOTHING
                """, code, "测试仓-" + code);
        return jdbc.queryForObject("SELECT id FROM warehouses WHERE code = ?", Long.class, code);
    }

    public long product(String code, String name) {
        jdbc.update("""
                INSERT INTO products (code, name) VALUES (?, ?)
                ON CONFLICT (code) DO NOTHING
                """, code, name);
        return jdbc.queryForObject("SELECT id FROM products WHERE code = ?", Long.class, code);
    }

    public long sku(long productId, String color, String size) {
        jdbc.update("""
                INSERT INTO skus (product_id, color, size, retail_price_amount)
                VALUES (?, ?, ?, 12900)
                ON CONFLICT (product_id, color, size) DO NOTHING
                """, productId, color, size);
        return jdbc.queryForObject(
                "SELECT id FROM skus WHERE product_id = ? AND color = ? AND size = ?",
                Long.class, productId, color, size);
    }

    /** 建库存余额行（实存 qty，预占 0）。 */
    public void stock(long skuId, long warehouseId, String batchNo,
                      LocalDate expiryDate, int qty) {
        jdbc.update("""
                INSERT INTO inventory_balances
                    (sku_id, warehouse_id, batch_no, expiry_date, on_hand_qty, reserved_qty)
                VALUES (?, ?, ?, ?, ?, 0)
                ON CONFLICT (sku_id, warehouse_id, batch_no)
                DO UPDATE SET on_hand_qty = EXCLUDED.on_hand_qty,
                              expiry_date = EXCLUDED.expiry_date
                """, skuId, warehouseId, batchNo, expiryDate, qty);
    }

    /** 直接写一条流水（用于前置构造，绕过应用层）。 */
    public void ledger(long skuId, long warehouseId, String batchNo, int changeQty,
                       String bizType, String bizRefNo) {
        jdbc.update("""
                INSERT INTO inventory_ledger
                    (sku_id, warehouse_id, batch_no, change_qty, biz_type,
                     biz_ref_no, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """, skuId, warehouseId, batchNo, changeQty, bizType, bizRefNo);
    }

    /** 清空业务表（每个测试用例前调用，保证用例间隔离）。 */
    public void truncateAll() {
        jdbc.execute("""
                TRUNCATE inventory_ledger, inventory_reservations, inventory_balances,
                         purchase_order_lines, sales_order_lines,
                         purchase_orders, sales_orders,
                         document_sequences, idempotency_keys,
                         skus, products, warehouses, suppliers, customers
                RESTART IDENTITY CASCADE
                """);
    }

    public long supplier(String code) {
        jdbc.update("""
                INSERT INTO suppliers (code, name) VALUES (?, ?)
                ON CONFLICT (code) DO NOTHING
                """, code, "测试供应商-" + code);
        return jdbc.queryForObject("SELECT id FROM suppliers WHERE code = ?", Long.class, code);
    }

    public long customer(String code) {
        return customer(code, 10000);
    }

    public long customer(String code, int discountRate) {
        jdbc.update("""
                INSERT INTO customers (code, name, discount_rate) VALUES (?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, code, "测试客户-" + code, discountRate);
        return jdbc.queryForObject("SELECT id FROM customers WHERE code = ?", Long.class, code);
    }

    /**
     * 读库存余额的某个数量列。<b>行不存在时返回 0</b>，不抛异常。
     *
     * <p>为什么要单独处理"行不存在"：余额行是<b>懒创建</b>的 ——
     * 只有真正入库才会出现 {@code (sku, warehouse, batch)} 这一行。
     * 断言"被拒绝的入库不得产生库存变动"时，正确结果恰恰是"行根本不存在"。
     * 若这里用 {@code queryForObject}，零行会抛
     * {@code EmptyResultDataAccessException}，测试就会以"辅助方法崩了"的
     * 形式失败，掩盖真正想验证的业务语义。
     *
     * <p>（M1 开发中真实踩到：4 条"失败必须无副作用"的用例因为这个辅助方法而红。）
     */
    private int balanceColumn(long skuId, long warehouseId, String batchNo, String column) {
        List<Integer> rows = jdbc.queryForList("""
                SELECT %s FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """.formatted(column), Integer.class, skuId, warehouseId, batchNo);
        return rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
    }

    public int onHand(long skuId, long warehouseId, String batchNo) {
        return balanceColumn(skuId, warehouseId, batchNo, "on_hand_qty");
    }

    public int reserved(long skuId, long warehouseId, String batchNo) {
        return balanceColumn(skuId, warehouseId, batchNo, "reserved_qty");
    }

    public int available(long skuId, long warehouseId, String batchNo) {
        return balanceColumn(skuId, warehouseId, batchNo, "available_qty");
    }

    /** 余额行是否存在（用于断言"失败不得产生任何库存痕迹"）。 */
    public boolean balanceRowExists(long skuId, long warehouseId, String batchNo) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return n != null && n > 0;
    }

    /** 某张业务单据的流水条数（用于验证"没有产生流水"）。 */
    public int ledgerCount(String bizRefNo) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_ref_no = ?", Integer.class, bizRefNo);
        return n == null ? 0 : n;
    }
}
