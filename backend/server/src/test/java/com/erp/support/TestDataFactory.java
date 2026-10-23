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
        // ★ M2 新增表必须同时进这条清单：return_order_lines 有指向 sales_order_lines 的外键，
        //   顺序错了会因 CASCADE 之外的约束失败，表现为"第一条用例绿、第二条随机红"。
        //   settlement_periods 也在列 —— 残留一行 CLOSED 会让后续所有进货/出货/退货用例
        //   被 PeriodGuard 拦成 SET_PERIOD_CLOSED（计划书 §6 坑 3 的同一条防线）。
        jdbc.execute("""
                TRUNCATE margin_records, cost_monthly, cost_layers,
                         transfer_lines, inventory_transfers,
                         stocktake_lines, stocktakes,
                         inventory_adjustments, inventory_freezes,
                         inventory_ledger, inventory_reservations, inventory_balances,
                         sales_order_line_reservations, return_order_lines,
                         purchase_order_lines, sales_order_lines,
                         return_orders, purchase_orders, sales_orders,
                         daily_settlements, settlement_periods,
                         document_sequences, idempotency_keys,
                         skus, products, warehouses, suppliers, customers,
                         app_user_roles, app_users
                RESTART IDENTITY CASCADE
                """);
    }

    /**
     * 建测试账号并赋予角色。
     *
     * <p>用调用方传入的 {@code PasswordEncoder} 而不是这里自己 new 一个 BCrypt：
     * 测试里的哈希必须与生产校验逻辑**同一个编码器**，
     * 否则会出现"测试用 A 加密、生产用 B 校验"的假绿。
     */
    public long user(String username, String rawPassword, String roleCode,
                     org.springframework.security.crypto.password.PasswordEncoder encoder) {
        jdbc.update("""
                INSERT INTO app_users (username, password_hash, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT (username) DO NOTHING
                """, username, encoder.encode(rawPassword), "测试用户-" + username);
        Long id = jdbc.queryForObject("SELECT id FROM app_users WHERE username = ?",
                Long.class, username);
        if (roleCode != null && id != null) {
            jdbc.update("""
                    INSERT INTO app_user_roles (user_id, role_id)
                    SELECT ?, id FROM app_roles WHERE code = ?
                    ON CONFLICT DO NOTHING
                    """, id, roleCode);
        }
        return id == null ? -1 : id;
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

    public int frozen(long skuId, long warehouseId, String batchNo) {
        return balanceColumn(skuId, warehouseId, batchNo, "frozen_qty");
    }

    /** 设置 SKU 的补货阈值（FR-INV-12/13）。 */
    public void skuLevels(long skuId, int minLevel, int maxLevel) {
        jdbc.update("UPDATE skus SET min_level = ?, max_level = ? WHERE id = ?",
                minLevel, maxLevel, skuId);
    }

    /** 设置仓库优先级（数值越大越优先）。 */
    public void warehousePriority(long warehouseId, int priority) {
        jdbc.update("UPDATE warehouses SET priority = ? WHERE id = ?", priority, warehouseId);
    }

    /** 按业务类型数流水条数（断言"差异为 0 的行不产生调整流水"）。 */
    public int ledgerCountByType(String bizRefNo, String bizType) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger WHERE biz_ref_no = ? AND biz_type = ?
                """, Integer.class, bizRefNo, bizType);
        return n == null ? 0 : n;
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
