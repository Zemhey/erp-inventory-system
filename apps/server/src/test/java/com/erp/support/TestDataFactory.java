package com.erp.support;

import java.time.LocalDate;
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
                         idempotency_keys, skus, products, warehouses
                RESTART IDENTITY CASCADE
                """);
    }

    public int onHand(long skuId, long warehouseId, String batchNo) {
        Integer v = jdbc.queryForObject("""
                SELECT on_hand_qty FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return v == null ? 0 : v;
    }

    public int reserved(long skuId, long warehouseId, String batchNo) {
        Integer v = jdbc.queryForObject("""
                SELECT reserved_qty FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return v == null ? 0 : v;
    }

    public int available(long skuId, long warehouseId, String batchNo) {
        Integer v = jdbc.queryForObject("""
                SELECT available_qty FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return v == null ? 0 : v;
    }
}
