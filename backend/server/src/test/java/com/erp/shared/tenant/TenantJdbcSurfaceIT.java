package com.erp.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link TenantAwareJdbcTemplate} 的**全部覆写入口**（FR-TNT-03）。
 *
 * <p>租户改写挂在 {@code JdbcTemplate} 的少数入口上；只要有一个入口没被覆写，
 * 走那条入口的仓储就会静默绕过过滤。本类逐个调用每个入口（含"拒绝"的
 * {@code PreparedStatementCreator} 族），把"哪些形态被覆盖"变成可执行断言，
 * 而不是靠读代码确认。
 *
 * <p>这里只做无害的空操作 SQL（{@code WHERE false} / 自增 0 行），
 * 断言重点是"能到达改写层且不抛"，不是业务语义。
 */
@SpringBootTest
@DisplayName("租户 · JdbcTemplate 入口覆盖 / TenantJdbc 辅助 / TenantDirectory")
class TenantJdbcSurfaceIT extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TenantDirectory tenantDirectory;

    private TenantAwareJdbcTemplate template() {
        return (TenantAwareJdbcTemplate) jdbc;
    }

    @Test
    @CaseId("TC-TNT-064")
    @DisplayName("查询/更新/批量的每个重载都经过改写层（空操作也能到达）")
    void everyOverloadReachesRewriteLayer() {
        TenantAwareJdbcTemplate t = template();
        List<Long> ids = t.query("SELECT id FROM products WHERE false",
                (org.springframework.jdbc.core.RowMapper<Long>) (rs, i) -> rs.getLong(1));
        assertThat(ids).isEmpty();
        Long productCount = t.query("SELECT COUNT(*) FROM products",
                (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs -> {
                    rs.next();
                    return rs.getLong(1);
                });
        assertThat(productCount).isNotNull();
        t.query("SELECT id FROM products WHERE false", rs -> {
        });
        assertThat(t.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isNotNull();
        Long countByMapper = t.queryForObject("SELECT COUNT(*) FROM products",
                (org.springframework.jdbc.core.RowMapper<Long>) (rs, i) -> rs.getLong(1));
        assertThat(countByMapper).isNotNull();
        assertThat(t.queryForList("SELECT code FROM products WHERE false", String.class))
                .isEmpty();
        assertThat(t.queryForList("SELECT code FROM products WHERE false")).isEmpty();
        assertThat(t.queryForMap("SELECT 1 AS x")).containsKey("x");

        assertThat(t.update("UPDATE products SET name = name WHERE false")).isZero();
        assertThat(t.update("UPDATE products SET name = name WHERE id = ?",
                new Object[]{-1L}, new int[]{Types.BIGINT})).isZero();
        t.execute("SELECT 1");

        String sql = "UPDATE products SET name = name WHERE id = ?";
        assertThat(t.batchUpdate(sql, List.<Object[]>of(new Object[]{-1L}))).hasSize(1);
        assertThat(t.batchUpdate(sql, List.<Object[]>of(new Object[]{-1L}),
                new int[]{Types.BIGINT})).hasSize(1);
        assertThat(t.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setLong(1, -1L);
            }

            @Override
            public int getBatchSize() {
                return 1;
            }
        })).hasSize(1);
    }

    @Test
    @CaseId("TC-TNT-065")
    @DisplayName("★ PreparedStatementCreator 族必须大声失败（拿不到 SQL 文本就不能保证过滤）")
    void preparedStatementCreatorsAreRejected() {
        assertThatThrownBy(() -> template().update(
                con -> con.prepareStatement("SELECT 1")))
                .isInstanceOf(com.erp.shared.error.BusinessException.class)
                .hasMessageContaining("拿不到 SQL 文本");
        assertThatThrownBy(() -> template().query(
                con -> con.prepareStatement("SELECT 1"), (rs, i) -> 1L))
                .isInstanceOf(com.erp.shared.error.BusinessException.class);
        assertThatThrownBy(() -> template().query(
                con -> con.prepareStatement("SELECT 1"),
                (java.sql.ResultSet rs) -> null))
                .isInstanceOf(com.erp.shared.error.BusinessException.class);
        assertThatThrownBy(() -> template().execute(
                con -> con.prepareStatement("SELECT 1"),
                (java.sql.PreparedStatement ps) -> null))
                .isInstanceOf(com.erp.shared.error.BusinessException.class);
    }

    @Test
    @CaseId("TC-TNT-066")
    @DisplayName("TenantJdbc 辅助与 TenantDirectory：查询已存在/不存在的租户，诊断上下文")
    void helpersAndDirectory() {
        TenantAwareJdbcTemplate t = template();
        assertThat(TenantJdbc.update(t, "UPDATE products SET name = name WHERE false")).isZero();
        assertThat(TenantJdbc.query(t, "SELECT code FROM products WHERE false",
                (rs, i) -> rs.getString(1))).isEmpty();
        assertThat(TenantJdbc.named(t)).isNotNull();
        assertThat(TenantJdbc.params()).isNotNull();
        String rewritten = TenantJdbc.rewriteWith(7, "SELECT 1",
                (java.util.function.Function<String, String>) sql -> sql);
        assertThat(rewritten).isEqualTo("SELECT 1");

        assertThat(tenantDirectory.enabledTenantIds()).contains(1L, 2L);
        assertThat(tenantDirectory.codeOf(1L)).hasValue("default");
        assertThat(tenantDirectory.codeOf(999L)).isEmpty();

        assertThat(TenantAwareJdbcTemplate.describeContext()).contains("TENANT(1)");
        assertThat(TenantAwareJdbcTemplate.none()).isEmpty();
        assertThat(t.enforcementEnabled()).isTrue();
    }
}
