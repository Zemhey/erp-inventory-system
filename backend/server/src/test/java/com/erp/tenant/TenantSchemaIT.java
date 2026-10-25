package com.erp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantId;
import com.erp.shared.tenant.TenantSql;
import com.erp.shared.tenant.TenantTables;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ★ 租户列与约束的**结构门禁**（FR-TNT-02）。
 *
 * <p><b>为什么需要它</b>：{@code TenantSql} 是"按表名清单改写"的，清单与库里的
 * 真实列一旦漂移，症状只有两种：漏过滤（静默泄露）或往不存在的列上写谓词
 * （运行时 SQL 报错）。两种都不应该靠"某条用例碰巧跑到"来发现。
 *
 * <p>本类把三处**必须逐字一致**的东西钉在一起：
 * ① {@link TenantTables#ALL}；② 库里真实带 {@code tenant_id} 的表；
 * ③ {@code TenantSql} 的 ON CONFLICT 白名单（这些表的唯一索引必须以 tenant_id 开头）。
 */
@SpringBootTest
@DisplayName("租户 · schema 门禁（清单 / 真实列 / 复合唯一）")
class TenantSchemaIT extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @CaseId("TC-TNT-031")
    @DisplayName("★ 表清单与库里真实列双向相等：少一张=漏过滤，多一张=改写器写不存在的列")
    void tableListMatchesDatabase() {
        Set<String> actual = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'tenant_id'
                """, String.class));
        Set<String> expected = TenantTables.snapshot();

        assertThat(actual)
                .as("★ 库里带 tenant_id 的表必须与 TenantTables.ALL 完全一致")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual)
                .as("tenants / flyway_schema_history 不应出现在业务表清单里")
                .doesNotContain("tenants", "flyway_schema_history");
    }

    @Test
    @CaseId("TC-TNT-032")
    @DisplayName("★ V16 之后所有业务表都没有 tenant_id 为 NULL 的行（回填完整）")
    void noNullTenantIds() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'tenant_id'
                 ORDER BY table_name
                """, String.class);
        assertThat(tables).isNotEmpty();
        for (String table : tables) {
            Integer nulls = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE tenant_id IS NULL", Integer.class);
            assertThat(nulls)
                    .as("★ %s 存在 tenant_id 为 NULL 的行 —— V16 的回填不完整", table)
                    .isZero();
        }
    }

    @Test
    @CaseId("TC-TNT-033")
    @DisplayName("★ ON CONFLICT 白名单里的表，都存在以 tenant_id 开头的唯一索引")
    void onConflictTablesHaveCompositeUniqueIndex() {
        for (String table : TenantSql.onConflictScopedTables()) {
            List<String> indexes = jdbc.queryForList("""
                    SELECT indexdef FROM pg_indexes
                     WHERE schemaname = 'public' AND tablename = ?
                       AND indexdef LIKE 'CREATE UNIQUE INDEX%'
                    """, String.class, table);
            assertThat(indexes)
                    .as("★ %s 没有唯一索引 —— TenantSql 会在 ON CONFLICT 目标里加 tenant_id，"
                            + "而 PG 找不到匹配的唯一索引会直接报错", table)
                    .isNotEmpty();
            assertThat(indexes.stream().anyMatch(def -> def.contains("(tenant_id,")))
                    .as("★ %s 的唯一索引不以 tenant_id 开头，ON CONFLICT (tenant_id, …) "
                            + "无法匹配。实际索引=%s", table, indexes)
                    .isTrue();
        }
    }

    @Test
    @CaseId("TC-TNT-034")
    @DisplayName("★ 审计表的 tenant_id 仍是 V13 的 NOT NULL DEFAULT 1（已知坑），演示租户已种角色")
    void auditColumnAndDemoSeed() {
        String isNullable = jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'audit_logs'
                   AND column_name = 'tenant_id'
                """, String.class);
        String defaultValue = jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'audit_logs'
                   AND column_name = 'tenant_id'
                """, String.class);
        assertThat(isNullable).isEqualTo("NO");
        assertThat(defaultValue)
                .as("★ audit_logs.tenant_id 的 DB 默认值仍是 1：直接 SQL 漏传会静默记到租户 1，"
                        + "应用路径必须由 TenantSql 显式补列（见 TenantInvariantsIT 的归属用例）")
                .isEqualTo("1");

        Integer demoRoles = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_roles WHERE tenant_id = ?", Integer.class, TenantId.DEMO));
        Integer defaultRoles = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_roles WHERE tenant_id = ?", Integer.class,
                TenantId.DEFAULT));
        assertThat(demoRoles)
                .as("★ 演示租户必须有与默认租户同构的角色，否则租户 2 的用户处处 403")
                .isEqualTo(defaultRoles);
    }
}
