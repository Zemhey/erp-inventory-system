package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 库存作业接口契约测试（TC-API-019 ~ 029）。
 *
 * <p>重点不是重复服务层用例，而是证明：
 * <ul>
 *   <li>契约里的路径真的存在且字段名与前端约定一致；</li>
 *   <li>写操作缺 {@code X-Request-Id} 返回 400，而不是 500；</li>
 *   <li>只读角色调用写接口返回 403（后端权限是唯一安全边界）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 库存作业（调拨/盘点/调整/冻结/洞察）")
class InventoryOpsApiIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private InventoryOperationsApplicationService operations;

    private String adminAuth;
    private String viewerAuth;
    private long warehouseA;
    private long warehouseB;
    private long skuId;
    private final AtomicInteger seq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("inv_ops_admin", "Admin1!", "ADMIN", encoder);
        data.user("inv_ops_viewer", "Viewer1!", "VIEWER", encoder);
        adminAuth = "Bearer " + authentication.login("inv_ops_admin", "Admin1!").token();
        viewerAuth = "Bearer " + authentication.login("inv_ops_viewer", "Viewer1!").token();

        warehouseA = data.warehouse("WH-OPS-A");
        warehouseB = data.warehouse("WH-OPS-B");
        long productId = data.product("P-OPS-API", "库存作业接口商品");
        skuId = data.sku(productId, "白色", "L");
    }

    private String req() {
        return "api-req-" + seq.getAndIncrement();
    }

    private void stock(int qty) {
        inventory.receiveIn(skuId, warehouseA, "B1", null, LocalDate.of(2026, 1, 1), qty,
                "PURCHASE_IN", "PO-OPS-" + seq.getAndIncrement(), null, 1000L, req());
    }

    private String transferBody(int qty) {
        return """
                {"fromWarehouseId":%d,"toWarehouseId":%d,
                 "lines":[{"skuId":%d,"batchNo":"B1","qty":%d}]}
                """.formatted(warehouseA, warehouseB, skuId, qty);
    }

    // ─────────────────────────── 调拨 ───────────────────────────

    @Test
    @CaseId("TC-API-019")
    @DisplayName("POST /inventory/transfers：建单即发运，响应含 transferNo / inTransitQty / lines")
    void createTransfer_shouldReturnContractShape() throws Exception {
        stock(10);

        mvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.transferNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.inTransitQty").value(4))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.lines[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.lines[0].batchNo").value("B1"))
                .andExpect(jsonPath("$.data.lines[0].qty").value(4))
                .andExpect(jsonPath("$.data.lines[0].inTransitQty").value(4));
    }

    @Test
    @CaseId("TC-API-020")
    @DisplayName("写操作缺 X-Request-Id：400 而不是 500；只读角色写接口 403")
    void writeWithoutRequestId_then400_andViewerWrite_then403() throws Exception {
        mvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/inventory/transfers")
                        .header("Authorization", viewerAuth)
                        .header("X-Request-Id", req())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @CaseId("TC-API-021")
    @DisplayName("POST /inventory/transfers/{id}/receive：状态变为 COMPLETED 且守恒差为 0")
    void receiveTransfer_shouldCompleteAndKeepConservation() throws Exception {
        stock(10);
        long transferId = operations.createTransfer(warehouseA, warehouseB, null, null,
                java.util.List.of(new InventoryOperationsApplicationService.TransferLineCommand(
                        skuId, "B1", 4, null)), req()).transfer().id();

        mvc.perform(post("/api/v1/inventory/transfers/" + transferId + "/receive")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.inTransitQty").value(0))
                .andExpect(jsonPath("$.data.conservationDiff").value(0))
                .andExpect(jsonPath("$.data.receivedAt").isNotEmpty());
    }

    @Test
    @CaseId("TC-API-022")
    @DisplayName("GET /inventory/transfers：状态筛选与分页字段符合契约")
    void listTransfers_shouldPageAndFilter() throws Exception {
        stock(20);
        operations.createTransfer(warehouseA, warehouseB, null, null,
                java.util.List.of(new InventoryOperationsApplicationService.TransferLineCommand(
                        skuId, "B1", 2, null)), req());
        long completedId = operations.createTransfer(warehouseA, warehouseB, null, null,
                java.util.List.of(new InventoryOperationsApplicationService.TransferLineCommand(
                        skuId, "B1", 3, null)), req()).transfer().id();
        operations.receiveTransfer(completedId, req());

        mvc.perform(get("/api/v1/inventory/transfers")
                        .param("status", "IN_TRANSIT")
                        .param("page", "1").param("size", "10")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.items[0].status").value("IN_TRANSIT"));

        mvc.perform(get("/api/v1/inventory/transfers")
                        .param("toWarehouseId", String.valueOf(warehouseB))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    // ─────────────────────────── 盘点 ───────────────────────────

    @Test
    @CaseId("TC-API-023")
    @DisplayName("盘点：建单返回 DRAFT 与账面快照，过账后 POSTED 且差异正确")
    void stocktake_createAndPost() throws Exception {
        stock(10);

        String createBody = """
                {"warehouseId":%d,
                 "lines":[{"skuId":%d,"batchNo":"B1","countedQty":12}]}
                """.formatted(warehouseA, skuId);
        String response = mvc.perform(post("/api/v1/inventory/stocktakes")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.lines[0].bookQty").value(10))
                .andExpect(jsonPath("$.data.lines[0].countedQty").value(12))
                .andExpect(jsonPath("$.data.lines[0].diffQty").value(2))
                .andReturn().getResponse().getContentAsString();

        String idText = response.replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/v1/inventory/stocktakes/" + idText + "/post")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("POSTED"))
                .andExpect(jsonPath("$.data.totalDiffQty").value(2))
                .andExpect(jsonPath("$.data.postedAt").isNotEmpty());

        mvc.perform(get("/api/v1/inventory")
                        .param("skuId", String.valueOf(skuId))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHandQty").value(12));
    }

    @Test
    @CaseId("TC-API-024")
    @DisplayName("盘点全部差异为 0：过账返回 409 INV_STOCKTAKE_NO_DIFFERENCE")
    void stocktake_withNoDifference_then409() throws Exception {
        stock(10);
        long stocktakeId = operations.createStocktake(warehouseA, null, null,
                java.util.List.of(new InventoryOperationsApplicationService.StocktakeLineCommand(
                        skuId, "B1", 10)), req()).stocktake().id();

        mvc.perform(post("/api/v1/inventory/stocktakes/" + stocktakeId + "/post")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INV_STOCKTAKE_NO_DIFFERENCE"));
    }

    // ─────────────────────────── 调整 / 冻结 ───────────────────────────

    @Test
    @CaseId("TC-API-025")
    @DisplayName("库存调整：原因空白返回 400（Bean Validation），不是 500")
    void adjustment_withBlankReason_then400() throws Exception {
        stock(10);

        String body = """
                {"skuId":%d,"warehouseId":%d,"batchNo":"B1","changeQty":2,"reason":"   "}
                """.formatted(skuId, warehouseA);
        mvc.perform(post("/api/v1/inventory/adjustments")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-API-026")
    @DisplayName("冻结/解冻：冻结后可用量下降，解冻后恢复（GET /inventory 可见 frozenQty）")
    void freezeAndRelease_shouldChangeAvailable() throws Exception {
        stock(10);
        String body = """
                {"skuId":%d,"warehouseId":%d,"batchNo":"B1","qty":4,"reason":"抽检"}
                """.formatted(skuId, warehouseA);

        String response = mvc.perform(post("/api/v1/inventory/freezes")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.qty").value(4))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/v1/inventory")
                        .param("skuId", String.valueOf(skuId))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onHandQty").value(10))
                .andExpect(jsonPath("$.data[0].frozenQty").value(4))
                .andExpect(jsonPath("$.data[0].availableQty").value(6));

        String idText = response.replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/v1/inventory/freezes/" + idText + "/release")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", req()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RELEASED"));

        mvc.perform(get("/api/v1/inventory")
                        .param("skuId", String.valueOf(skuId))
                        .header("Authorization", adminAuth))
                .andExpect(jsonPath("$.data[0].frozenQty").value(0))
                .andExpect(jsonPath("$.data[0].availableQty").value(10));
    }

    // ─────────────────────────── 洞察 ───────────────────────────

    @Test
    @CaseId("TC-API-027")
    @DisplayName("GET /inventory/balance-dimensions：五维行 + 总量与状态合计")
    void balanceDimensions_shouldReturnTotals() throws Exception {
        stock(10);
        inventory.reserve(new ReserveCommand(skuId, warehouseA, "B1", 3, req()));
        operations.createFreeze(skuId, warehouseA, "B1", 2, "抽检", null, req());

        mvc.perform(get("/api/v1/inventory/balance-dimensions")
                        .param("skuId", String.valueOf(skuId))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.totals.onHandQty").value(10))
                .andExpect(jsonPath("$.data.totals.byStatus.AVAILABLE").value(5))
                .andExpect(jsonPath("$.data.totals.byStatus.RESERVED").value(3))
                .andExpect(jsonPath("$.data.totals.byStatus.FROZEN").value(2));
    }

    @Test
    @CaseId("TC-API-028")
    @DisplayName("GET /inventory/alerts：安全库存预警字段符合契约")
    void alerts_shouldReturnSafetyAlert() throws Exception {
        stock(3);
        jdbc.update("UPDATE skus SET min_level = 5, max_level = 20 WHERE id = ?", skuId);

        mvc.perform(get("/api/v1/inventory/alerts")
                        .param("warehouseId", String.valueOf(warehouseA))
                        .param("type", "SAFETY")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("SAFETY"))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].availableQty").value(3))
                .andExpect(jsonPath("$.data.items[0].threshold").value(5));
    }

    @Test
    @CaseId("TC-API-029")
    @DisplayName("GET /inventory/replenishment：给出建议；存在未到货进货单后建议消失")
    void replenishment_shouldRespectPendingPurchase() throws Exception {
        stock(5);
        jdbc.update("UPDATE skus SET min_level = 10, max_level = 30 WHERE id = ?", skuId);

        mvc.perform(get("/api/v1/inventory/replenishment")
                        .param("warehouseId", String.valueOf(warehouseA))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(25));

        long supplierId = new TestDataFactory(jdbc).supplier("SUP-API-OPEN");
        long poId = jdbc.queryForObject("""
                INSERT INTO purchase_orders
                    (order_no, status, supplier_id, warehouse_id, biz_date, total_amount, total_qty)
                VALUES ('PO-API-OPEN', 'APPROVED', ?, ?, CURRENT_DATE, 0, 0)
                RETURNING id
                """, Long.class, supplierId, warehouseA);
        jdbc.update("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                VALUES (?, 1, ?, 20, 0, 100, 2000)
                """, poId, skuId);

        mvc.perform(get("/api/v1/inventory/replenishment")
                        .param("warehouseId", String.valueOf(warehouseA))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
