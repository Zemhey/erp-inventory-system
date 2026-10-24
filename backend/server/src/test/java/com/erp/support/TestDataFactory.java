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

    /**
     * 迁移种下的「角色 → 权限」快照（进程内只拍一次）。
     *
     * <p>见 {@link #restoreSeedRolePermissions()} 的说明：跨泳道共享的配置不能手抄清单。
     */
    private static java.util.List<Object[]> seedPermissionsSnapshot;
    /** 迁移种下的角色编码清单（同上）。 */
    private static java.util.List<String> seedRoleCodes = java.util.List.of();

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
                         payable_allocations, supplier_payables, purchase_returns,
                         po_receipt_lines, po_receipts,
                         purchase_order_lines, sales_order_lines,
                         return_orders, purchase_orders, sales_orders,
                         daily_settlements, settlement_periods,
                         document_sequences, idempotency_keys,
                         skus, products, warehouses, suppliers, customers,
                         data_scopes,
                         app_user_roles, app_users,
                         audit_logs
                RESTART IDENTITY CASCADE
                """);
        // ★★ 审批的两张表<b>刻意不放进 TRUNCATE</b>：{@code approval_flows} 与
        //   {@code approval_steps} 的定义行是**配置**（由 V14 迁移种子给出），
        //   而 TRUNCATE 的 CASCADE 会把引用它的表整表清掉 ——
        //   TRUNCATE approval_instances CASCADE 会顺手清空 approval_steps，
        //   连"定义节点"（instance_id IS NULL）一起消失，于是后续用例看到
        //   "流程存在但没有任何节点"（实测：TC-APS-019 报"在金额 10000 下没有任何可执行节点"）。
        //
        //   正确做法是 DELETE：只清实例及其快照节点，定义节点自然保留。
        //   同时把"启用态"复位成迁移里的初始值（全部停用），
        //   因为部分唯一索引只允许同类型有一条启用流，用例改过之后必须还原。
        // ★★ 角色与权限也是**配置**（V3/V5/V7 种子），同样不能 truncate；
        //   但用例会改它们（"撤掉 pur:approve""给角色加 mdm:write"），
        //   若不复位，下一条用例就继承了上一条的改坏状态 ——
        //   症状是"单独跑绿、整套跑红"（实测：TC-IAM-033 之后所有 403 断言全反）。
        //   这里把种子恢复成迁移里的初始授权（与 V3+V5+V7 的三段 INSERT 一致）。
        restoreSeedRolePermissions();
        // 用例自建的流程（如会签/或签）不在种子里，删掉；种子流程保留
        jdbc.update("DELETE FROM approval_flows WHERE code NOT IN ('PO-STD','SO-STD','RO-STD')");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
        jdbc.update("DELETE FROM approval_instances");
        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        ensureApprovalSeed();
    }

    /**
     * 修复/补齐审批流种子（幂等）。
     *
     * <p><b>★ 为什么需要"修复"而不只是"假设种子在"</b>：
     * 测试容器的 PostgreSQL 是 <b>{@code withReuse(true)}</b>（含 {@code PostgresTestBase}
     * 的复用配置），同一台容器会被本机反复的 {@code gradlew test} 共用。
     * 早先版本的 {@code truncateAll} 里写过 {@code TRUNCATE ... approval_instances CASCADE}，
     * 而 CASCADE 会连着清空引用它的 {@code approval_steps} —— <b>连定义节点一起没了</b>。
     * 那一次破坏留在了复用容器里，之后每次跑都报
     * "审批流 PO-STD 在金额 50000 下没有任何可执行节点"（看起来像代码 bug，其实是脏库）。
     *
     * <p>因此这里做两件事：① 缺 flow 就补 flow；② 缺定义节点就补定义节点。
     * 这个修复本身也是幂等的，正常干净的库上不会产生任何改动。
     */
    public void ensureApprovalSeed() {
        jdbc.update("""
                INSERT INTO approval_flows (code, name, biz_type, description, enabled)
                VALUES ('PO-STD','进货单标准审批','PURCHASE_ORDER','采购主管 → （金额超阈值）财务',FALSE),
                       ('SO-STD','出货单标准审批','SALES_ORDER','销售主管 → （金额超阈值）财务',FALSE),
                       ('RO-STD','退货单标准审批','RETURN_ORDER','客服主管 → （金额超阈值）财务',FALSE)
                ON CONFLICT (code) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO approval_steps
                    (flow_id, instance_id, step_no, name, approver_permission, approve_mode,
                     required_approvals, threshold_amount)
                SELECT f.id, NULL, s.step_no, s.name, s.permission, 'SINGLE', 1, s.threshold
                  FROM approval_flows f
                  JOIN (VALUES
                    ('PO-STD',1,'采购主管审批','pur:approve',0),
                    ('PO-STD',2,'财务审批（金额超阈值）','set:monthly',500000),
                    ('SO-STD',1,'销售主管审批','sal:approve',0),
                    ('SO-STD',2,'财务审批（金额超阈值）','set:monthly',500000),
                    ('RO-STD',1,'客服主管审批','rma:approve',0),
                    ('RO-STD',2,'财务审批（金额超阈值）','set:monthly',500000)
                  ) AS s(flow_code, step_no, name, permission, threshold)
                    ON f.code = s.flow_code
                 WHERE NOT EXISTS (
                    SELECT 1 FROM approval_steps d
                     WHERE d.flow_id = f.id AND d.instance_id IS NULL
                       AND d.step_no = s.step_no)
                """);
    }

    /**
     * 把四个种子角色的权限恢复成 V3/V5/V7 的初始授权。
     *
     * <p><b>为什么必须显式恢复而不是"反正用例会自己重建"</b>：
     * 角色是<b>配置</b>而不是业务数据，{@code truncateAll} 不碰它们；
     * 而权限测试必然会改角色权限（撤权、加权限、覆盖式保存）。
     * 只要有一条用例改了不改回来，后续所有 RBAC 断言就会静默反向 ——
     * 那类失败最难查，因为它取决于用例执行顺序。
     *
     * <p>清单与迁移逐条对应：{@code V3__iam.sql}（mdm/pur/sal/inv）、
     * {@code V5__settlement.sql}（set/rpt + FINANCE）、
     * {@code V7__finance_mdm_read.sql}（FINANCE 的 mdm:read）、
     * {@code V14__iam_deepening_and_approval.sql}（iam 与 aps 两组权限码）。
     */
    public void restoreSeedRolePermissions() {
        // ★★ 为什么"自动快照"而不是"手抄一份种子清单"：
        //
        //   角色权限是**跨泳道共享的配置**：V3 种 mdm/pur/sal/inv、V5 种 set/rpt/FINANCE、
        //   V7 给 FINANCE 补 mdm:read、V13 给 VIEWER 种 sys:read、V2-2 种 pur:payable、
        //   V2-4 种 cst:read、V14 种 iam:*/aps:*……
        //   手抄一份清单的后果是：**每来一条并行泳道，这份清单就悄悄过期一次**，
        //   而症状是"别人刚种好的权限在测试里凭空消失，一堆 403 看起来像自己的 bug"。
        //   （实测：抄写的清单漏了 V13 的 sys:read 与 V2-4 的 cst:read，
        //     AuditQueryIT / CostApiIT / ReturnOrderApiIT 一起变红。）
        //
        //   正确做法：第一次调用时把**迁移后的真实种子**拍成快照（进程内静态），
        //   之后每条用例都从快照精确恢复。新增迁移时这份快照自动跟上，无需维护。
        if (seedPermissionsSnapshot == null) {
            seedPermissionsSnapshot = jdbc.query(
                    "SELECT role_id, permission FROM app_role_permissions",
                    (rs, i) -> new Object[]{rs.getLong("role_id"), rs.getString("permission")});
            seedRoleCodes = jdbc.query("SELECT code FROM app_roles",
                    (rs, i) -> rs.getString("code"));
        }
        jdbc.update("DELETE FROM app_role_permissions");
        for (Object[] row : seedPermissionsSnapshot) {
            jdbc.update("INSERT INTO app_role_permissions (role_id, permission) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", (Long) row[0], (String) row[1]);
        }
        // 用例临时建的角色（不在种子里的）删掉，避免 uq_app_role_code 冲突。
        // 注意这里用**快照到的编码清单**，而不是硬编码的四个/五个名字。
        if (!seedRoleCodes.isEmpty()) {
            String placeholders = String.join(", ",
                    java.util.Collections.nCopies(seedRoleCodes.size(), "?"));
            jdbc.update("DELETE FROM app_roles WHERE code NOT IN (" + placeholders + ")",
                    seedRoleCodes.toArray());
        }
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
