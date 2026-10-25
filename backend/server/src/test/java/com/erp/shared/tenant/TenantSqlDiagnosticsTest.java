package com.erp.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.support.CaseId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantSql} 的**拒绝形态与诊断工具**（纯单元，不起容器）。
 *
 * <p>改写器最危险的失败方式不是抛异常，而是"静默放过一条没加租户谓词的 SQL"。
 * 因此每一类无法安全改写的写法都必须**大声失败**；本类逐类钉住这些分支。
 * 同时覆盖 {@code describeSites / tablesOf / touchesBusinessTables} 等诊断入口 ——
 * 它们只在守卫测试与排障时执行，最容易长期无人覆盖。
 */
@DisplayName("租户 · SQL 改写的拒绝形态与诊断")
class TenantSqlDiagnosticsTest {

    @Test
    @CaseId("TC-TNT-060")
    @DisplayName("★ 无法安全改写的 INSERT 形态必须抛，而不是静默返回原 SQL")
    void rejectsUnsafeInsertShapes() {
        assertThatThrownBy(() -> TenantSql.rewrite("INSERT INTO products", 7))
                .isInstanceOf(com.erp.shared.error.BusinessException.class)
                .hasMessageContaining("缺少列清单");
        assertThatThrownBy(() -> TenantSql.rewrite("INSERT INTO products (code)", 7))
                .hasMessageContaining("既没有 VALUES 也没有 SELECT");
        assertThatThrownBy(() -> TenantSql.rewrite("INSERT INTO products (code) VALUES", 7))
                .hasMessageContaining("VALUES 之后没有括号组");
        assertThatThrownBy(() -> TenantSql.rewrite("INSERT INTO products (code) VALUES (?, ?", 7))
                .hasMessageContaining("VALUES 括号不闭合");
    }

    @Test
    @CaseId("TC-TNT-061")
    @DisplayName("非业务表 / 无表语句 / 空语句原样返回；非法租户直接拒绝")
    void exemptAndEmptyStatements() {
        assertThat(TenantSql.rewrite("INSERT INTO tenants (code) VALUES ('x')", 7).sql())
                .isEqualTo("INSERT INTO tenants (code) VALUES ('x')");
        assertThat(TenantSql.rewrite("UPDATE tenants SET code = code", 7).sql())
                .isEqualTo("UPDATE tenants SET code = code");
        assertThat(TenantSql.rewrite("DELETE FROM tenants", 7).sql())
                .isEqualTo("DELETE FROM tenants");
        assertThat(TenantSql.rewrite("SELECT 1", 7).sql()).isEqualTo("SELECT 1");
        assertThat(TenantSql.rewrite("", 7).sql()).isEmpty();
        assertThat(TenantSql.rewrite(null, 7).sql()).isNull();
        assertThatThrownBy(() -> TenantSql.rewrite("SELECT 1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @CaseId("TC-TNT-062")
    @DisplayName("★ 每个 *_request_id 列都生成正确的部分索引谓词（具体列先于通用列）")
    void partialIndexPredicatePerColumn() {
        for (String column : java.util.List.of(
                "void_request_id", "receive_request_id", "post_request_id",
                "confirm_request_id", "create_request_id", "disable_request_id",
                "conversion_request_id", "approve_request_id", "release_request_id",
                "convert_request_id", "request_id")) {
            String sql = TenantSql.rewrite("""
                    INSERT INTO purchase_orders (order_no, %s)
                    VALUES (?, ?) ON CONFLICT (%s) DO NOTHING
                    """.formatted(column, column), 7).sql();
            assertThat(sql)
                    .as("★ %s 的推断谓词必须引用同名列（通用 request_id 分支会拼出不存在的列）",
                            column)
                    .contains("WHERE " + column + " IS NOT NULL");
        }
        // 无部分索引的表：不加 WHERE
        assertThat(TenantSql.rewrite("""
                INSERT INTO idempotency_keys (idem_key, request_hash)
                VALUES (?, ?) ON CONFLICT (idem_key) DO NOTHING""", 7).sql())
                .doesNotContain("IS NOT NULL");
        // 显式 tenant_id 且没有 ON CONFLICT：原样返回
        String explicit = "INSERT INTO app_roles (code, name, tenant_id) VALUES (?, ?, 1)";
        assertThat(TenantSql.rewrite(explicit, 7).sql()).isEqualTo(explicit);
    }

    @Test
    @CaseId("TC-TNT-063")
    @DisplayName("诊断入口：别名 / RETURNING / 无尾巴 / touchesBusinessTables 的全部分支")
    void diagnosticsAndClauseGuards() {
        assertThat(TenantSql.rewrite("""
                SELECT id FROM products AS p WHERE p.id = ?""", 7).sql())
                .isEqualTo("SELECT id FROM products AS p WHERE p.id = ? AND p.tenant_id = 7");
        assertThat(TenantSql.rewrite("UPDATE products SET name = name RETURNING id", 7).sql())
                .isEqualTo("UPDATE products SET name = name"
                        + " WHERE 1 = 1 AND products.tenant_id = 7 RETURNING id");
        assertThat(TenantSql.rewrite("DELETE FROM products WHERE id = ? RETURNING id", 7).sql())
                .isEqualTo("DELETE FROM products WHERE id = ?"
                        + " AND products.tenant_id = 7 RETURNING id");
        assertThat(TenantSql.rewrite("SELECT id FROM products", 7).sql())
                .as("无 WHERE / ORDER 时也能补谓词（尾部为空的插入分支）")
                .isEqualTo("SELECT id FROM products WHERE 1 = 1 AND products.tenant_id = 7");

        assertThat(TenantSql.tablesOf("SELECT id FROM products")).containsExactly("products");
        assertThat(TenantSql.tablesOf("SELECT id FROM tenants")).isEmpty();
        assertThat(TenantSql.describeSites("SELECT 1")).isEmpty();
        assertThat(TenantSql.describeSites(
                "SELECT id FROM products p JOIN warehouses AS w ON w.id = p.id"))
                .hasValue("products p, warehouses w");
        assertThat(TenantSql.mentionsTenant(null)).isFalse();
        assertThat(TenantSql.mentionsTenant("SELECT tenant_id")).isTrue();

        assertThat(TenantSql.touchesBusinessTables(null)).isFalse();
        assertThat(TenantSql.touchesBusinessTables("  ")).isFalse();
        assertThat(TenantSql.touchesBusinessTables("TRUNCATE products")).isFalse();
        assertThat(TenantSql.touchesBusinessTables("SELECT id FROM tenants")).isFalse();
        assertThat(TenantSql.touchesBusinessTables("SELECT id FROM products")).isTrue();
        assertThat(TenantSql.touchesBusinessTables("SELECT id FROM products;")).isTrue();
    }
}
