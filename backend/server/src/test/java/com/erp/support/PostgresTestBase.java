package com.erp.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
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
 *
 * <p><b>为什么有 {@link #assertDockerAvailable()} 这段前置检查</b>：
 * Docker 未启动时，Testcontainers 只会抛出
 * {@code IllegalStateException: Could not find a valid Docker environment}，
 * 这个提示极具误导性 —— 它会让人以为是端口、权限或 API 版本问题，
 * 而实际原因往往只是"Docker Desktop 没开"。
 * 本项目在 M0 阶段就为此浪费过一次排查（见 docs/ADR-0010 的同类记录）。
 * 前置检查把"猜"变成"看"：直接告诉你该启动什么、怎么启动。
 */
public abstract class PostgresTestBase {

    /**
     * ★ V2-7 FR-TNT-04：**测试线程的默认租户绑定**。
     *
     * <p><b>为什么测试基类要绑定租户</b>：生产里租户来自 HTTP 请求
     * （{@code JwtAuthenticationFilter} 按 token 的 {@code tid} 绑定）。而集成测试
     * 大多是<b>直接调应用服务</b>（不起 MockMvc），那条路径上没有任何东西会绑定租户
     * —— 于是 {@code TenantSql} 会抛 {@code SYS_TENANT_CONTEXT_MISSING}，
     * 现有 956 条用例会成片变红。
     *
     * <p><b>为什么这是"集中一处"而不是逐条改用例</b>：956 条用例里绝大多数只关心
     * 业务语义，不关心"数据属于哪个租户"。在基类统一绑默认租户，等价于给它们一个
     * 与生产一致的运行环境（默认租户就是存量数据的归属），<b>一行用例都不用改</b>。
     *
     * <p><b>为什么每条用例都重新绑一次</b>：测试线程会被 JUnit 复用，而上一条用例
     * （或被调代码的缺陷）可能留下过别的租户绑定。每条用例前清空再绑，
     * 让"顺序依赖"无处藏身。
     *
     * <p><b>需要别的租户时</b>：用例内部用
     * {@code TenantContext.callIn(TenantId.DEMO, …)} 或
     * {@code new TestDataFactory(jdbc).inTenant(TenantId.DEMO)}，
     * 它们会在结束时精确回退到这里的默认绑定。
     */
    @org.junit.jupiter.api.BeforeEach
    void bindDefaultTenant() {
        com.erp.shared.tenant.TenantContext.clearForTest();
        com.erp.shared.tenant.TenantContext.bindForTest(
                com.erp.shared.tenant.TenantId.DEFAULT);
    }

    /**
     * 每个用例结束后清空绑定栈。
     *
     * <p>用"整体清空"而不是"逐层 pop"：用例内部可能嵌套了若干次 {@code callIn}，
     * 正常路径会各自回退；但若被测代码有缺陷漏了回退，逐层 pop 也无法知道该退几层。
     * 清空是最稳的收尾，代价只是"用例之间不共享绑定"—— 而它们本来就不该共享。
     */
    @org.junit.jupiter.api.AfterEach
    void unbindTenant() {
        com.erp.shared.tenant.TenantContext.clearForTest();
    }

    static {
        assertDockerAvailable();
    }

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("erp_test")
                    .withUsername("erp")
                    .withPassword("erp")
                    // 复用容器：本机反复跑测试时省去启动开销。
                    // 注意：Testcontainers 1.21 起默认允许 reuse，无需额外配置。
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    /**
     * 前置检查 Docker 是否可用，不可用时抛出一条<b>可直接照做</b>的提示。
     *
     * <p>失败信息包含：判断结论、本机三种常见成因、对应的修复动作。
     */
    private static void assertDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
        } catch (RuntimeException | Error ex) {
            String os = System.getProperty("os.name", "").toLowerCase();
            String hint = os.contains("win")
                    ? """
                    【修复】Windows 下按顺序尝试：
                      1. 启动 Docker Desktop：开始菜单搜索 "Docker Desktop" 并打开，
                         等待托盘图标变为绿色（约 10-30 秒）。
                      2. 命令行确认守护进程已就绪：
                           docker version --format '{{.Server.Version}}'
                         能打印版本号才算真的起来了。
                      3. 若仍失败，检查本机 Docker API 版本是否较新（Docker 29+）：
                         需要 DOCKER_API_VERSION=1.54，已在 build.gradle.kts 的 test 任务中预设。
                    """
                    : """
                    【修复】Linux / CI 下按顺序尝试：
                      1. 确认守护进程在运行：systemctl status docker
                      2. 确认当前用户属于 docker 组：groups | grep docker
                      3. 确认 socket 可访问：ls -l /var/run/docker.sock
                    """;
            throw new IllegalStateException("""
                    ════════════════════════════════════════════════════════════════
                    集成测试无法启动：Docker 不可用
                    ════════════════════════════════════════════════════════════════
                    本项目使用 Testcontainers 启动真实 PostgreSQL 16 容器，
                    因此必须有一个可用的 Docker 守护进程。

                    %s
                    ────────────────────────────────────────────────────────────────
                    Testcontainers 原始错误（仅供排查参考，其字面含义常具误导性）：
                      %s
                    ════════════════════════════════════════════════════════════════
                    """.formatted(hint, ex.getMessage()));
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 让 Flyway 在每个测试类启动时把 V1 迁移跑在干净库上
        registry.add("spring.flyway.clean-disabled", () -> "false");
        /**
         * ★ 连接池必须调小（V2-5 实测踩到）。
         *
         * <p>Spring 会<b>缓存多个测试上下文</b>（MockMvc 一套、RANDOM_PORT 一套、
         * 带不同 profile 的又一套），而每个上下文都按 {@code application.yml} 的
         * {@code maximum-pool-size: 20} 建池。Testcontainers 的 PostgreSQL 默认
         * {@code max_connections=100}，于是上下文一多就撞上
         * {@code FATAL: sorry, too many clients already} —— 表现是
         * "单独跑绿、全量跑红"，报错还停在 Tomcat 启动阶段，极难定位。
         *
         * <p>测试并发度低（单 JVM 顺序执行测试类），4 个连接完全够用；
         * 生产仍用 20，不受这里影响。
         */
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }
}
