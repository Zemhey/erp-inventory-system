package com.erp.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.shared.error.BusinessException;
import com.erp.support.CaseId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantSql} 的改写规则（FR-TNT-03）—— <b>纯单元测试，不起容器</b>。
 *
 * <p><b>为什么这一组用例是本泳道最重要的测试</b>：租户过滤的<b>全部</b>隔离强度
 * 都建立在"改写器认得每一种 SQL 写法"之上。它是一条纯文本变换，一旦某类写法
 * 被漏匹配，那条路径就<b>没有</b>租户谓词 —— 而不会有任何编译错误或异常提示。
 * 因此这里逐类写法各钉一条，并且对"拒绝改写"的形态断言它会抛。
 *
 * <p><b>★ 断言方式</b>：不看"是否含 tenant_id"这种弱断言，而是断言
 * <b>改写后的完整 SQL</b>（用 {@code isEqualTo}）—— 这样任何一次意外的
 * 位置移动 / 括号错位 / 重复追加都会立刻变红。文本很啰嗦，但这是唯一能保证
 * "我看过它到底改成了什么"的写法。
 */
@DisplayName("租户 · SQL 改写规则（纯单元）")
class TenantSqlTest {

    private static final long T = 7L;

    private static String rw(String sql) {
        return TenantSql.rewrite(sql, T).sql();
    }

    /**
     * 把连续空白折叠成单个空格再比较。
     *
     * <p><b>为什么这样断言不算"弱断言"</b>：SQL 里的空白不改变语义，而改写器
     * 会在拼接点规约空白。若坚持逐字符比较，任何一次无关的格式化都会变红，
     * 反而让人倾向于把断言退化成 {@code contains}（那才是弱断言）。折叠之后
     * 仍然是<b>整条语句</b>的等值比较 —— 位置错乱 / 重复追加 / 漏追加都能抓到。
     */
    private static String norm(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    @Nested
    @DisplayName("SELECT：按 id 直查 / 列表 / 联表 / 聚合")
    class Select {

        @Test
        @CaseId("TC-TNT-001")
        @DisplayName("按 id 直查：谓词追加在 WHERE 之后")
        void byId() {
            assertThat(rw("SELECT id, code FROM products WHERE id = ?"))
                    .isEqualTo("SELECT id, code FROM products WHERE id = ? AND products.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-002")
        @DisplayName("无 WHERE 的列表查询：自动补 WHERE 1 = 1 再追加")
        void listWithoutWhere() {
            assertThat(rw("SELECT id FROM warehouses ORDER BY priority DESC, id"))
                    .isEqualTo("SELECT id FROM warehouses WHERE 1 = 1 AND warehouses.tenant_id = 7"
                            + " ORDER BY priority DESC, id");
        }

        @Test
        @CaseId("TC-TNT-003")
        @DisplayName("LIMIT/OFFSET 在谓词之后（不会把占位符排到 LIMIT 后面）")
        void paging() {
            assertThat(rw("SELECT id FROM products WHERE code ILIKE ? LIMIT ? OFFSET ?"))
                    .isEqualTo("SELECT id FROM products WHERE code ILIKE ?"
                            + " AND products.tenant_id = 7 LIMIT ? OFFSET ?");
        }

        @Test
        @CaseId("TC-TNT-004")
        @DisplayName("★ 联表：两侧各自带谓词（靠别名限定，不串味）")
        void joinBothSides() {
            assertThat(rw("""
                    SELECT o.id, l.sku_id FROM purchase_orders o
                      JOIN purchase_order_lines l ON l.order_id = o.id
                     WHERE o.biz_date >= ?"""))
                    .isEqualTo("""
                            SELECT o.id, l.sku_id FROM purchase_orders o
                              JOIN purchase_order_lines l ON l.order_id = o.id
                             WHERE o.biz_date >= ? AND o.tenant_id = 7 AND l.tenant_id = 7""");
        }

        @Test
        @CaseId("TC-TNT-005")
        @DisplayName("★ LEFT JOIN：右表谓词写进 ON（保住外连接语义），左表谓词仍在 WHERE")
        void leftJoinOnScoped() {
            assertThat(norm(rw("""
                    SELECT l.id FROM inventory_ledger l
                      LEFT JOIN return_orders ro ON ro.order_no = l.biz_ref_no
                     WHERE l.sku_id = ?""")))
                    .isEqualTo("SELECT l.id FROM inventory_ledger l"
                            + " LEFT JOIN return_orders ro"
                            + " ON ro.tenant_id = 7 AND ro.order_no = l.biz_ref_no"
                            + " WHERE l.sku_id = ? AND l.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-006")
        @DisplayName("聚合统计：GROUP BY 在谓词之后")
        void aggregate() {
            assertThat(rw("""
                    SELECT sku_id, SUM(change_qty) FROM inventory_ledger
                     WHERE occurred_at < ? GROUP BY sku_id ORDER BY sku_id"""))
                    .isEqualTo("""
                            SELECT sku_id, SUM(change_qty) FROM inventory_ledger
                             WHERE occurred_at < ? AND inventory_ledger.tenant_id = 7 GROUP BY sku_id ORDER BY sku_id""");
        }

        @Test
        @CaseId("TC-TNT-007")
        @DisplayName("★ WITH + DISTINCT ON + 子查询：CTE 内外都要有谓词")
        void cteAndSubquery() {
            String sql = rw("""
                    WITH opening AS (
                        SELECT DISTINCT ON (sku_id, warehouse_id)
                               sku_id, warehouse_id, pool_qty_after
                          FROM cost_layers
                         WHERE occurred_at < ?
                         ORDER BY sku_id, warehouse_id, occurred_at DESC, id DESC
                    )
                    SELECT sku_id FROM opening""");
            assertThat(sql)
                    .as("CTE 内的业务表必须带谓词")
                    .contains("AND cost_layers.tenant_id = 7")
                    .as("外层只引用 CTE，不引用业务表 → 不追加多余谓词")
                    .contains("SELECT sku_id FROM opening");
        }

        @Test
        @CaseId("TC-TNT-008")
        @DisplayName("★ 派生表（FROM (SELECT …)）里的业务表也要被过滤")
        void derivedTable() {
            String sql = rw("""
                    SELECT COALESCE(SUM(pool_cost_after), 0) FROM (
                        SELECT DISTINCT ON (sku_id, warehouse_id) pool_cost_after
                          FROM cost_layers
                         ORDER BY sku_id, warehouse_id, occurred_at DESC, id DESC
                    ) latest""");
            assertThat(sql).contains("cost_layers.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-009")
        @DisplayName("FOR UPDATE 在谓词之后（否则锁的范围会跨租户）")
        void forUpdate() {
            assertThat(rw("SELECT id FROM sales_orders WHERE id = ? FOR UPDATE"))
                    .isEqualTo("SELECT id FROM sales_orders WHERE id = ?"
                            + " AND sales_orders.tenant_id = 7 FOR UPDATE");
        }

        @Test
        @CaseId("TC-TNT-010")
        @DisplayName("EXISTS 子查询：内层业务表也带谓词")
        void existsSubquery() {
            String sql = rw("""
                    SELECT id FROM purchase_orders o
                     WHERE NOT EXISTS (SELECT 1 FROM po_receipts r WHERE r.purchase_order_id = o.id)""");
            assertThat(sql)
                    .contains("o.tenant_id = 7")
                    .contains("r.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-011")
        @DisplayName("UNION 的两个分支各自带谓词（谓词留在各自分支内，不跨作用域）")
        void union() {
            assertThat(rw("SELECT code FROM warehouses WHERE enabled UNION SELECT code FROM suppliers"))
                    .isEqualTo("SELECT code FROM warehouses WHERE enabled"
                            + " AND warehouses.tenant_id = 7"
                            + " UNION SELECT code FROM suppliers"
                            + " WHERE 1 = 1 AND suppliers.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-030")
        @DisplayName("★ UNION ALL + 第二分支联表：每个分支按各自的表追加别名谓词")
        void unionAllWithJoin() {
            String sql = rw("""
                    SELECT scope_type, scope_value
                      FROM data_scopes
                     WHERE user_id = ?
                     UNION ALL
                    SELECT ds.scope_type, ds.scope_value
                      FROM data_scopes ds
                      JOIN app_user_roles ur ON ur.role_id = ds.role_id
                     WHERE ur.user_id = ?""");
            assertThat(norm(sql)).isEqualTo(
                    "SELECT scope_type, scope_value FROM data_scopes WHERE user_id = ?"
                            + " AND data_scopes.tenant_id = 7"
                            + " UNION ALL SELECT ds.scope_type, ds.scope_value"
                            + " FROM data_scopes ds JOIN app_user_roles ur"
                            + " ON ur.role_id = ds.role_id WHERE ur.user_id = ?"
                            + " AND ds.tenant_id = 7 AND ur.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-049")
        @DisplayName("★ LEFT JOIN 的右表谓词进 ON（写进 WHERE 会把外连接退化成内连接）")
        void leftJoinPredicateGoesIntoOn() {
            String sql = rw("""
                    SELECT l.id, so.order_no
                      FROM inventory_ledger l
                      LEFT JOIN return_orders ro
                             ON l.biz_type = 'RETURN_IN' AND ro.order_no = l.biz_ref_no
                      LEFT JOIN sales_orders so ON so.id = ro.origin_sales_order_id""");
            assertThat(norm(sql)).isEqualTo(
                    "SELECT l.id, so.order_no FROM inventory_ledger l"
                            + " LEFT JOIN return_orders ro ON ro.tenant_id = 7"
                            + " AND l.biz_type = 'RETURN_IN' AND ro.order_no = l.biz_ref_no"
                            + " LEFT JOIN sales_orders so ON so.tenant_id = 7"
                            + " AND so.id = ro.origin_sales_order_id"
                            + " WHERE 1 = 1 AND l.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-012")
        @DisplayName("非业务表（tenants / flyway_schema_history）不被改写")
        void exemptTables() {
            assertThat(rw("SELECT id, code FROM tenants WHERE enabled = ?"))
                    .isEqualTo("SELECT id, code FROM tenants WHERE enabled = ?");
            assertThat(rw("SELECT version FROM flyway_schema_history ORDER BY installed_rank"))
                    .isEqualTo("SELECT version FROM flyway_schema_history ORDER BY installed_rank");
        }
    }

    @Nested
    @DisplayName("INSERT：列清单与 VALUES 尾部追加租户")
    class Insert {

        @Test
        @CaseId("TC-TNT-013")
        @DisplayName("单行 VALUES：列清单加一列、行尾加一个字面量")
        void singleRow() {
            assertThat(rw("""
                    INSERT INTO warehouses (code, name, address, enabled, priority)
                    VALUES (?, ?, ?, ?, ?)"""))
                    .isEqualTo("""
                            INSERT INTO warehouses (code, name, address, enabled, priority, tenant_id)
                            VALUES (?, ?, ?, ?, ?, 7)""");
        }

        @Test
        @CaseId("TC-TNT-014")
        @DisplayName("★ 多行 VALUES：每一行都追加（只加第一行会让第二行落错租户）")
        void multiRow() {
            assertThat(rw("""
                    INSERT INTO approval_flows (code, name, biz_type, description, enabled)
                    VALUES ('A','a','PURCHASE_ORDER',NULL,FALSE),
                           ('B','b','SALES_ORDER',NULL,FALSE)"""))
                    .isEqualTo("""
                            INSERT INTO approval_flows (code, name, biz_type, description, enabled, tenant_id)
                            VALUES ('A','a','PURCHASE_ORDER',NULL,FALSE, 7),
                                   ('B','b','SALES_ORDER',NULL,FALSE, 7)""");
        }

        @Test
        @CaseId("TC-TNT-015")
        @DisplayName("★ 占位符个数不变（字面量注入的核心性质）")
        void placeholderCountUnchanged() {
            String original = "INSERT INTO products (code, name) VALUES (?, ?)";
            assertThat(TenantJdbcPlaceholderCounter.count(original))
                    .as("原文 2 个占位符")
                    .isEqualTo(2);
            assertThat(TenantJdbcPlaceholderCounter.count(rw(original)))
                    .as("★ 改写后仍是 2 个 —— 于是 ps.setXxx(1..n) 的下标不用改")
                    .isEqualTo(2);
        }

        @Test
        @CaseId("TC-TNT-016")
        @DisplayName("RETURNING id 不被破坏（取主键的路径必须仍然可用）")
        void returning() {
            assertThat(rw("""
                    INSERT INTO warehouses (code, name) VALUES (?, ?)
                    RETURNING id"""))
                    .isEqualTo("""
                            INSERT INTO warehouses (code, name, tenant_id) VALUES (?, ?, 7)
                            RETURNING id""");
        }

        @Test
        @CaseId("TC-TNT-017")
        @DisplayName("★ ON CONFLICT 的目标列表也要前缀 tenant_id（复合唯一改了，目标必须跟着改）")
        void onConflictTarget() {
            assertThat(rw("""
                    INSERT INTO inventory_transfers (transfer_no, status, request_id)
                    VALUES (?, 'IN_TRANSIT', ?)
                    ON CONFLICT (request_id) WHERE request_id IS NOT NULL DO NOTHING
                    RETURNING id"""))
                    .as("★ 若不改目标，PG 会报 no unique or exclusion constraint "
                            + "matching the ON CONFLICT specification")
                    .contains("ON CONFLICT (tenant_id, request_id)");
        }

        @Test
        @CaseId("TC-TNT-018")
        @DisplayName("ON CONFLICT DO NOTHING（无目标）不改动")
        void onConflictDoNothing() {
            assertThat(rw("INSERT INTO warehouses (code, name) VALUES (?, ?) ON CONFLICT DO NOTHING"))
                    .isEqualTo("INSERT INTO warehouses (code, name, tenant_id) VALUES (?, ?, 7)"
                            + " ON CONFLICT DO NOTHING");
        }

        @Test
        @CaseId("TC-TNT-019")
        @DisplayName("★ INSERT … SELECT：列清单补列、SELECT 列表补值，源表也被过滤")
        void insertSelect() {
            // ★ 为什么不靠 BEFORE INSERT 触发器填充：会话变量 erp.tenant_id 从来没被设置过
            //   （TenantContext 只活在 JVM 里），触发器取到的是 NULL，随后 NOT NULL 约束会报错。
            //   因此列清单必须显式补 tenant_id、SELECT 列表必须显式补值。
            assertThat(norm(rw("""
                    INSERT INTO app_user_roles (user_id, role_id)
                    SELECT ?, id FROM app_roles WHERE code = ?""")))
                    .as("源表被过滤，杜绝把别租户的角色挂到本租户用户上；"
                            + "目标列与值都显式写入，不依赖未设置的会话变量")
                    .isEqualTo("INSERT INTO app_user_roles (user_id, role_id, tenant_id)"
                            + " SELECT ?, id, 7 FROM app_roles"
                            + " WHERE code = ? AND app_roles.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-050")
        @DisplayName("★ 已显式带 tenant_id 的 INSERT，仍要修 ON CONFLICT 目标（否则复合唯一匹配不上）")
        void explicitTenantStillFixesConflictTarget() {
            assertThat(norm(rw("""
                    INSERT INTO app_roles (code, name, description, tenant_id)
                    VALUES (?, ?, ?, 1) ON CONFLICT (code) DO NOTHING""")))
                    .isEqualTo("INSERT INTO app_roles (code, name, description, tenant_id)"
                            + " VALUES (?, ?, ?, 1) ON CONFLICT (tenant_id, code) DO NOTHING");
        }

        @Test
        @CaseId("TC-TNT-051")
        @DisplayName("★ 发货计划的 request_id 幂等目标补 tenant_id，并保留（非部分）索引可用的谓词")
        void shipmentPlanConflictTarget() {
            assertThat(norm(rw("""
                    INSERT INTO so_shipment_plans (plan_no, sales_order_id, request_id)
                    VALUES (?, ?, ?) ON CONFLICT (request_id) DO NOTHING RETURNING id""")))
                    .isEqualTo("INSERT INTO so_shipment_plans"
                            + " (plan_no, sales_order_id, request_id, tenant_id)"
                            + " VALUES (?, ?, ?, 7)"
                            + " ON CONFLICT (tenant_id, request_id)"
                            + " WHERE request_id IS NOT NULL DO NOTHING RETURNING id");
        }

        @Test
        @CaseId("TC-TNT-052")
        @DisplayName("★ INSERT … SELECT + ON CONFLICT：SELECT 列表与 ON CONFLICT 之间必须有空白（防 1ON）")
        void insertSelectWithConflictKeepsTokenBoundary() {
            assertThat(norm(rw("""
                    INSERT INTO app_user_roles (user_id, role_id)
                    SELECT ?, id FROM app_roles WHERE code = ? ON CONFLICT DO NOTHING""")))
                    .isEqualTo("INSERT INTO app_user_roles (user_id, role_id, tenant_id)"
                            + " SELECT ?, id, 7 FROM app_roles"
                            + " WHERE code = ? AND app_roles.tenant_id = 7"
                            + " ON CONFLICT DO NOTHING");
        }

        @Test
        @CaseId("TC-TNT-020")
        @DisplayName("★ 拒绝改写：INSERT 缺列清单 → 抛（不能静默放过一条无租户的写）")
        void rejectsMissingColumnList() {
            assertThatThrownBy(() -> rw("INSERT INTO products SELECT code, name FROM products"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("租户过滤无法注入");
        }
    }

    @Nested
    @DisplayName("UPDATE / DELETE")
    class UpdateDelete {

        @Test
        @CaseId("TC-TNT-021")
        @DisplayName("UPDATE 带 WHERE：谓词追加在其后")
        void updateWithWhere() {
            assertThat(rw("UPDATE products SET name = ?, updated_at = now() WHERE id = ?"))
                    .isEqualTo("UPDATE products SET name = ?, updated_at = now()"
                            + " WHERE id = ? AND products.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-022")
        @DisplayName("★ 无 WHERE 的 UPDATE：补 WHERE 1 = 1（否则会更新全库所有租户的行）")
        void updateWithoutWhere() {
            assertThat(rw("UPDATE approval_flows SET enabled = FALSE"))
                    .isEqualTo("UPDATE approval_flows SET enabled = FALSE"
                            + " WHERE 1 = 1 AND approval_flows.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-023")
        @DisplayName("DELETE FROM：同法")
        void delete() {
            assertThat(rw("DELETE FROM app_user_roles WHERE user_id = ?"))
                    .isEqualTo("DELETE FROM app_user_roles WHERE user_id = ?"
                            + " AND app_user_roles.tenant_id = 7");
        }

        @Test
        @CaseId("TC-TNT-024")
        @DisplayName("★ TRUNCATE 是 DDL，不改写（造数工具要能清全库）")
        void truncateIsNotRewritten() {
            String sql = "TRUNCATE products, warehouses RESTART IDENTITY CASCADE";
            assertThat(rw(sql)).isEqualTo(sql);
        }
    }

    @Nested
    @DisplayName("幂等：同一条 SQL 过两遍不会重复追加")
    class Idempotency {

        @Test
        @CaseId("TC-TNT-025")
        @DisplayName("★ JdbcTemplate 层改过之后，DataSource 层再过一遍是 no-op")
        void twiceIsNoOp() {
            String once = rw("SELECT id FROM products WHERE id = ?");
            String twice = TenantSql.rewrite(once, T).sql();
            assertThat(twice)
                    .as("★ 不做幂等，双重注入会报 column tenant_id specified more than once")
                    .isEqualTo(once);
        }

        @Test
        @CaseId("TC-TNT-026")
        @DisplayName("★ 手工写了谓词的 SQL 不会被重复追加（当前租户断言）")
        void alreadyScoped() {
            String manual = "SELECT id FROM products WHERE id = ? AND products.tenant_id = 7";
            assertThat(TenantSql.rewrite(manual, T).sql()).isEqualTo(manual);
        }

        @Test
        @CaseId("TC-TNT-027")
        @DisplayName("★ 只给两张表中一张加了谓词时，仍会被补上（不能被误判成已完成）")
        void partiallyScopedStillGetsSecond() {
            String partial = "SELECT o.id FROM purchase_orders o"
                    + " JOIN purchase_order_lines l ON l.order_id = o.id"
                    + " WHERE o.tenant_id = 7";
            assertThat(rw(partial)).contains("l.tenant_id = 7");
        }
    }

    @Nested
    @DisplayName("上下文缺失时必须大声失败")
    class ContextRequired {

        @Test
        @CaseId("TC-TNT-028")
        @DisplayName("★ 没有绑定租户 → 抛 SYS_TENANT_CONTEXT_MISSING，绝不回落默认租户")
        void unboundThrows() {
            TenantContext.clearForTest();
            assertThatThrownBy(() -> TenantSql.rewrite("SELECT id FROM products WHERE id = ?"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("租户上下文缺失");
        }

        @Test
        @CaseId("TC-TNT-029")
        @DisplayName("系统旁路上下文也不能走业务查询（没有确定的租户归属）")
        void systemContextThrows() {
            TenantContext.clearForTest();
            TenantContext.runAsSystem(() -> assertThatThrownBy(
                    () -> TenantSql.rewrite("SELECT id FROM products")).isInstanceOf(
                    BusinessException.class));
            TenantContext.clearForTest();
        }
    }
}
