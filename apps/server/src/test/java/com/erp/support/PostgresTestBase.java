package com.erp.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Testcontainers 集成测试基类。
 *
 * <p><b>为什么必须用真 PostgreSQL，而不是 H2/SQLite</b>（docs/06-测试策略.md §2）：
 * 本项目的核心不变量（防超卖、生成列、CHECK 约束、行锁语义）全部依赖
 * <b>真实数据库行为</b>。H2 的锁与约束语义与 PostgreSQL 不一致，
 * 用它测出来的"绿"是假绿 —— 比没有测试更危险。
 *
 * <p>容器在静态初始化块中启动（singleton 模式），整个测试 JVM 共享一个实例，
 * 避免每个测试类都付一次启动成本。
 */
public abstract class PostgresTestBase {

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("erp_test")
                    .withUsername("erp")
                    .withPassword("erp")
                    // 复用容器：本机跑得快；CI 中可通过环境变量关闭
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 让 Flyway 在每个测试类启动时把 V1 迁移跑在干净库上
        registry.add("spring.flyway.clean-disabled", () -> "false");
    }
}
