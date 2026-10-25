package com.erp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.ReservationExpiryScheduler;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantDirectory;
import com.erp.shared.tenant.TenantId;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import com.erp.sys.repository.AuditLogRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ★ **守恒类不变量**在租户维度成立（S8，硬验收第 2 条）。
 *
 * <p>租户过滤只保证"看不见别人的"，不保证"所有行都恰好归属某个租户"。
 * 后者要靠三条守恒：
 * <ol>
 *   <li>Σ(各租户库存) == 全局库存 —— 不能有行落在租户 0 / NULL 里"隐身"；</li>
 *   <li>单号序列按租户独立 —— 两个租户同一天都从 0001 开始，且不互相跳号；</li>
 *   <li>幂等键按租户定义 —— 同一 requestId 在两个租户各建一单，各自重放只回自己那单。</li>
 * </ol>
 *
 * <p>另加一条指挥侧点名的缺口：{@code audit_logs.tenant_id} 有 DB 默认值 1，
 * 直接 SQL 漏传会静默记到租户 1；本类断言应用路径写入后**按真实 tenant_id 查库**
 * 等于写入方租户，而不是只测"别租户读不到"。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("租户 · 守恒不变量（库存 / 单号 / 幂等 / 审计归属 / 调度）")
class TenantInvariantsIT extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private InventoryOperationsApplicationService operations;
    @Autowired
    private TenantDirectory tenantDirectory;
    @Autowired
    private DocumentNumberGenerator numbers;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    private TenantFixture tenant1;
    private TenantFixture tenant2;

    @BeforeEach
    void setUp() {
        TestDataFactory data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 3, 15, 10, 0, 0));
        tenant1 = TenantFixture.create(jdbc, data, TenantId.DEFAULT, "CLOSED");
        tenant2 = TenantFixture.create(jdbc, data, TenantId.DEMO, "OPEN");
    }

    @Test
    @CaseId("TC-TNT-044")
    @DisplayName("★ Σ(各租户库存) == 全局库存；任一租户的 SQL 只能看见自己的行")
    void stockConservationAcrossTenants() {
        long sum1 = sumOnHand(TenantId.DEFAULT);
        long sum2 = sumOnHand(TenantId.DEMO);
        long global = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COALESCE(SUM(on_hand_qty), 0) FROM inventory_balances", Long.class));

        assertThat(sum1).as("租户 1 的库存合计").isEqualTo(100L);
        assertThat(sum2).as("租户 2 的库存合计").isEqualTo(40L);
        assertThat(sum1 + sum2)
                .as("★ Σ(各租户库存) 必须等于全局库存，不能有行落在无租户的缝隙里")
                .isEqualTo(global);

        Long visibleRows = TenantContext.callIn(TenantId.DEFAULT, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balances", Long.class));
        assertThat(visibleRows).as("★ 租户 1 的查询只能看到自己的 1 行").isEqualTo(1L);

        // 改一个租户的库存，守恒仍然成立（不是"初始数据碰巧相等"）
        TenantContext.runIn(TenantId.DEFAULT,
                () -> jdbc.update("UPDATE inventory_balances SET on_hand_qty = on_hand_qty + 5"));
        long newGlobal = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COALESCE(SUM(on_hand_qty), 0) FROM inventory_balances", Long.class));
        assertThat(sumOnHand(TenantId.DEFAULT) + sumOnHand(TenantId.DEMO))
                .as("★ 变更后守恒仍成立")
                .isEqualTo(newGlobal);
        assertThat(newGlobal).isEqualTo(145L);
    }

    @Test
    @CaseId("TC-TNT-045")
    @DisplayName("★ 单号序列按租户独立：同一天两个租户都从 0001 开始，各自递增不串号")
    void documentSequenceIsPerTenant() {
        LocalDate day = LocalDate.of(2026, 3, 20);
        String t1First = TenantContext.callIn(TenantId.DEFAULT,
                () -> numbers.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day));
        String t2First = TenantContext.callIn(TenantId.DEMO,
                () -> numbers.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day));
        String t1Second = TenantContext.callIn(TenantId.DEFAULT,
                () -> numbers.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day));
        String t2Second = TenantContext.callIn(TenantId.DEMO,
                () -> numbers.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, day));

        assertThat(t1First).isEqualTo("PO20260320-0001");
        assertThat(t2First).as("★ 租户 2 不能被租户 1 的取号推着走").isEqualTo("PO20260320-0001");
        assertThat(t1Second).isEqualTo("PO20260320-0002");
        assertThat(t2Second).isEqualTo("PO20260320-0002");

        Long t1Rows = TenantContext.callIn(TenantId.DEFAULT, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_sequences", Long.class));
        Long globalRows = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM document_sequences", Long.class));
        assertThat(t1Rows).as("租户 1 只看到自己的序列行").isEqualTo(1L);
        assertThat(globalRows).as("全局有两个租户各一行").isEqualTo(2L);
    }

    @Test
    @CaseId("TC-TNT-046")
    @DisplayName("★ 幂等键按租户：同一 requestId 在两个租户各建一单；同租户重放只回自己那单")
    void idempotencyKeyIsPerTenant() {
        LocalDate day = LocalDate.of(2026, 4, 10);
        PurchaseOrder o1 = createOrder(tenant1, day, "TNT-IDEM-KEY");
        PurchaseOrder o2 = createOrder(tenant2, day, "TNT-IDEM-KEY");
        assertThat(o1.id()).as("同一幂等键在租户 2 不得命中租户 1 的单").isNotEqualTo(o2.id());
        assertThat(o2.orderNo()).isEqualTo("PO20260410-0001");
        assertThat(o1.orderNo()).isEqualTo("PO20260410-0001");

        PurchaseOrder replay = createOrder(tenant1, day, "TNT-IDEM-KEY");
        assertThat(replay.id()).as("同租户重放必须返回首次那一单").isEqualTo(o1.id());

        Long t1Count = TenantContext.callIn(TenantId.DEFAULT, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_orders WHERE request_id = 'TNT-IDEM-KEY'", Long.class));
        Long globalCount = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_orders WHERE request_id = 'TNT-IDEM-KEY'", Long.class));
        assertThat(t1Count).isEqualTo(1L);
        assertThat(globalCount).as("★ 同一幂等键在两个租户各留一行，互不覆盖").isEqualTo(2L);
    }

    @Test
    @CaseId("TC-TNT-047")
    @DisplayName("★ 审计归属：租户 2 写入后按真实 tenant_id 查库 == 2（不是 DB 默认值 1）")
    void auditRowBelongsToWritingTenant() {
        long auditId = TenantContext.callIn(TenantId.DEMO, () -> auditLogs.insert(
                null, "audit-t2", AuditAction.CREATE, "PRODUCT", "99", null,
                null, null, "trace-audit-t2", "127.0.0.1",
                OffsetDateTime.of(2026, 3, 15, 12, 0, 0, 0, ZoneOffset.ofHours(8)),
                AuditResult.SUCCESS, null));

        Long actualTenant = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT tenant_id FROM audit_logs WHERE id = ?", Long.class, auditId));
        assertThat(actualTenant)
                .as("★ audit_logs.tenant_id 的 DB 默认值是 1；应用路径必须由 TenantSql 显式补列，"
                        + "否则租户 2 的审计会静默记到租户 1")
                .isEqualTo(TenantId.DEMO);

        Long t1Visible = TenantContext.callIn(TenantId.DEFAULT, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE id = ?", Long.class, auditId));
        Long t2Visible = TenantContext.callIn(TenantId.DEMO, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE id = ?", Long.class, auditId));
        assertThat(t1Visible).as("租户 1 不能看到租户 2 的审计").isZero();
        assertThat(t2Visible).as("写入方自己必须看得到").isEqualTo(1L);
    }

    @Test
    @CaseId("TC-TNT-048")
    @DisplayName("★ 定时任务逐租户执行：两个租户各有一条到期预占，调度后各自只释放自己的")
    void schedulerRunsPerTenant() {
        var r1 = TenantContext.callIn(TenantId.DEFAULT, () -> inventory.reserve(
                new InventoryApplicationService.ReserveCommand(
                        tenant1.sku, tenant1.warehouse, "TNT-B1", 5, "TNT-EXP-1")));
        var r2 = TenantContext.callIn(TenantId.DEMO, () -> inventory.reserve(
                new InventoryApplicationService.ReserveCommand(
                        tenant2.sku, tenant2.warehouse, "TNT-B1", 5, "TNT-EXP-1")));
        OffsetDateTime past = OffsetDateTime.of(2026, 3, 14, 0, 0, 0, 0, ZoneOffset.ofHours(8));
        TenantContext.runIn(TenantId.DEFAULT, () -> jdbc.update(
                "UPDATE inventory_reservations SET expires_at = ? WHERE id = ?",
                past, r1.reservationId()));
        TenantContext.runIn(TenantId.DEMO, () -> jdbc.update(
                "UPDATE inventory_reservations SET expires_at = ? WHERE id = ?",
                past, r2.reservationId()));

        // 测试 profile 关掉了 @Scheduled 装配，bean 因此不存在：手工构造再触发（与既有
        // InventoryReservationExpiryIT 同一写法），这样断言的是方法体而不是调度线程。
        new ReservationExpiryScheduler(inventory, operations, tenantDirectory)
                .expireOverdueReservations();

        assertThat(reservationStatus(TenantId.DEFAULT, r1.reservationId()))
                .as("租户 1 的到期预占必须被释放").isEqualTo("EXPIRED");
        assertThat(reservationStatus(TenantId.DEMO, r2.reservationId()))
                .as("★ 租户 2 的到期预占也必须被释放（调度按租户循环，不是只跑默认租户）")
                .isEqualTo("EXPIRED");
        Long crossVisible = TenantContext.callIn(TenantId.DEFAULT, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations WHERE id = ?", Long.class,
                r2.reservationId()));
        assertThat(crossVisible).as("租户 1 看不到租户 2 的预占").isZero();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private long sumOnHand(long tenantId) {
        return TenantContext.callIn(tenantId, () -> {
            Long n = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(on_hand_qty), 0) FROM inventory_balances", Long.class);
            return n == null ? 0 : n;
        });
    }

    private PurchaseOrder createOrder(TenantFixture f, LocalDate day, String requestId) {
        return TenantContext.callIn(f.tenantId, () -> purchaseOrders.create(
                new PurchaseOrderApplicationService.CreateCommand(
                        f.supplier, f.warehouse, day, "租户幂等用例",
                        List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                                f.sku, 2, 1000L)),
                        requestId)));
    }

    private String reservationStatus(long tenantId, long reservationId) {
        return TenantContext.callIn(tenantId, () -> jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?", String.class,
                reservationId));
    }
}
