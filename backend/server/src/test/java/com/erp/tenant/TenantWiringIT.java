package com.erp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.shared.error.BusinessException;
import com.erp.shared.tenant.TenantAwareJdbcTemplate;
import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantId;
import com.erp.shared.tenant.TenantJdbcConfig;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 租户装配的**唯一出口**门禁（FR-TNT-03/04）。
 *
 * <p>租户过滤最大的风险不是"写错一条 SQL"，而是"装配悄悄没生效"——
 * 某个 `@Primary` 被删、某个 profile 注册了朴素 {@code JdbcTemplate}，
 * 结果全部查询回到 V15 的行为，而默认租户的用例照样全绿。
 * 本类用运行时类型断言把这条前提变成红/绿。
 */
@SpringBootTest
@DisplayName("租户 · 装配门禁（唯一出口 / 上下文必填 / 开关默认开）")
class TenantWiringIT extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantJdbcConfig.TenantEnforcement enforcement;

    @Test
    @CaseId("TC-TNT-035")
    @DisplayName("★ 容器里的 JdbcTemplate 必须是租户感知实现，且强制开关默认开启")
    void jdbcTemplateIsTenantAware() {
        assertThat(jdbc)
                .as("★ 若这里不是 TenantAwareJdbcTemplate，所有查询都没有租户过滤（静默回到 V15）")
                .isInstanceOf(TenantAwareJdbcTemplate.class);
        assertThat(((TenantAwareJdbcTemplate) jdbc).enforcementEnabled())
                .as("★ erp.tenant.enforce 默认必须为 true")
                .isTrue();
        assertThat(enforcement.enabled()).isTrue();
        assertThat(dataSource).isNotNull();
    }

    @Test
    @CaseId("TC-TNT-036")
    @DisplayName("★ 没有租户绑定时拒绝执行，而不是回落到默认租户")
    void missingContextFailsLoudly() {
        TenantContext.clearForTest();
        try {
            assertThatThrownBy(TenantContext::require)
                    .as("★ 未绑定租户时必须抛，绝不能静默用默认租户")
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("租户上下文缺失");
            // 读业务表同样拒绝（而不是把 tenant_id = 1 拼上去）
            assertThatThrownBy(() -> jdbc.queryForList("SELECT id FROM products"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("租户上下文缺失");
        } finally {
            TenantContext.bindForTest(TenantId.DEFAULT);
        }
    }

    @Test
    @CaseId("TC-TNT-037")
    @DisplayName("上下文成对开闭：嵌套切换后精确回退到上一层，异常也不泄漏")
    void contextIsStackScoped() {
        assertThat(TenantContext.require()).isEqualTo(TenantId.DEFAULT);
        TenantContext.runIn(TenantId.DEMO, () -> {
            assertThat(TenantContext.require()).isEqualTo(TenantId.DEMO);
            assertThatThrownBy(() -> TenantContext.runIn(TenantId.DEMO, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(TenantContext.require())
                    .as("内层异常后必须回到外层租户，而不是把绑定弄丢")
                    .isEqualTo(TenantId.DEMO);
        });
        assertThat(TenantContext.require()).isEqualTo(TenantId.DEFAULT);
    }
}
