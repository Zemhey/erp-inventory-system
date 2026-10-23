package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.application.SalesQuoteApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesQuote;
import com.erp.sal.repository.ReceivableRepository;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * V2-3 销售闭环 HTTP 层验收（TC-API-070 ~ 084）。
 *
 * <p>只测“服务层测不到”的东西：参数绑定、幂等键、权限、HTTP 错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · V2-3 销售闭环")
class SalesClosedLoopApiIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private SalesQuoteApplicationService quotes;
    @Autowired
    private ReceivableRepository receivables;

    private TestDataFactory data;
    private String auth;
    private long customerId;
    private long warehouseId;
    private long skuA;
    private long skuB;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("v23_admin", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("v23_admin", "Admin1!").token();
        customerId = data.customer("CUS-V23-API");
        warehouseId = data.warehouse("WH-V23-API");
        long productId = data.product("P-V23-API", "V23 接口商品");
        skuA = data.sku(productId, "白色", "L");
        skuB = data.sku(productId, "黑色", "M");
    }

    // ─────────────────────────── 报价 ───────────────────────────

    @Test
    @CaseId("TC-API-070")
    @DisplayName("报价 HTTP：建单 201 → 提交/审核 → 转化 200，报价 COMPLETED 且订单已预占")
    void quoteHttpFlow() throws Exception {
        stock(skuA, warehouseId, 50);
        MvcResult created = mvc.perform(post("/api/v1/sales-quotes")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-quote-070")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"bizDate":"2025-06-01","validUntil":"2027-12-31",
                                 "lines":[{"skuId":%d,"qty":5,"unitPriceAmount":1000}]}
                                """.formatted(customerId, skuA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalAmount").value(5000))
                .andReturn();
        long quoteId = data(created).get("id").asLong();

        mvc.perform(post("/api/v1/sales-quotes/" + quoteId + "/submit")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        mvc.perform(post("/api/v1/sales-quotes/" + quoteId + "/approve")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mvc.perform(post("/api/v1/sales-quotes/" + quoteId + "/convert")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-quote-070-convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalQty").value(5));

        mvc.perform(get("/api/v1/sales-quotes/" + quoteId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.convertedOrderId").isNumber());
        assertThat(data.reserved(skuA, warehouseId, "")).isEqualTo(5);
    }

    @Test
    @CaseId("TC-API-071")
    @DisplayName("报价转化信用超额：409 SAL_CREDIT_LIMIT_EXCEEDED，且无预占")
    void quoteConvertOverCredit_http() throws Exception {
        stock(skuA, warehouseId, 50);
        jdbc.update("UPDATE customers SET credit_limit = 1000 WHERE id = ?", customerId);
        long quoteId = approvedQuote(5, 1000L, "api-quote-071");

        mvc.perform(post("/api/v1/sales-quotes/" + quoteId + "/convert")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-quote-071-convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":%d}".formatted(warehouseId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAL_CREDIT_LIMIT_EXCEEDED"));
        assertThat(count("inventory_reservations")).isZero();
        assertThat(count("sales_orders")).isZero();
    }

    // ─────────────────────────── 自动选仓 / 套装 ───────────────────────────

    @Test
    @CaseId("TC-API-072")
    @DisplayName("自动选仓 HTTP：201，allocations 跨两仓且 Σ qty == 行数量")
    void autoSplitHttp() throws Exception {
        long wh2 = data.warehouse("WH-V23-API-2");
        stock(skuA, warehouseId, 10);
        stock(skuA, wh2, 10);

        MvcResult result = mvc.perform(post("/api/v1/sales-orders/auto-split")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-auto-072")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"lines":[
                                  {"skuId":%d,"qty":15,"unitPriceAmount":1000,"discountRate":10000}
                                ]}
                                """.formatted(customerId, skuA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].allocations").isArray())
                .andReturn();

        JsonNode allocations = data(result).get("lines").get(0).get("allocations");
        assertThat(allocations.size()).isEqualTo(2);
        int total = 0;
        for (JsonNode allocation : allocations) {
            total += allocation.get("qty").asInt();
        }
        assertThat(total).isEqualTo(15);
    }

    @Test
    @CaseId("TC-API-073")
    @DisplayName("套装 HTTP：建套装 201，可用量 = min(组件可用÷配比)")
    void bundleHttp() throws Exception {
        stock(skuA, warehouseId, 10);
        stock(skuB, warehouseId, 15);

        MvcResult created = mvc.perform(post("/api/v1/product-bundles")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-bundle-073")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"API-BUNDLE-073","name":"接口套装","priceAmount":39900,
                                 "items":[{"componentSkuId":%d,"ratioQty":2},
                                          {"componentSkuId":%d,"ratioQty":3}]}
                                """.formatted(skuA, skuB)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("API-BUNDLE-073"))
                .andReturn();
        long bundleId = data(created).get("id").asLong();

        mvc.perform(get("/api/v1/product-bundles/" + bundleId + "/availability")
                        .param("warehouseId", String.valueOf(warehouseId))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableQty").value(5))
                .andExpect(jsonPath("$.data.components[0].maxBundles").value(5))
                .andExpect(jsonPath("$.data.components[1].maxBundles").value(5));
    }

    // ─────────────────────────── 发货 ───────────────────────────

    @Test
    @CaseId("TC-API-074")
    @DisplayName("发货计划 HTTP：200 + 重放同键返回同一计划，不重复建发货单")
    void shipmentPlanHttp_isIdempotent() throws Exception {
        SalesOrder order = approvedOrder(6, "api-plan-074");
        MvcResult first = mvc.perform(post("/api/v1/sales-orders/" + order.id() + "/shipments")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-plan-074-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipments.length()").value(1))
                .andReturn();
        long planId = data(first).get("planId").asLong();

        MvcResult replay = mvc.perform(post("/api/v1/sales-orders/" + order.id() + "/shipments")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-plan-074-1"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(replay).get("planId").asLong()).isEqualTo(planId);
        assertThat(count("so_shipments")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-API-075")
    @DisplayName("扫码 HTTP：错条码 400；正确条码累计并最终 PICKED")
    void scanHttp_validatesBarcodeAndAccumulates() throws Exception {
        jdbc.update("UPDATE skus SET barcode = '6901000750' WHERE id = ?", skuA);
        SalesOrder order = approvedOrder(2, "api-scan-075");
        ShipmentRef shipment = plan(order, "api-scan-075-plan");

        mvc.perform(post("/api/v1/shipments/" + shipment.id() + "/scan")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipmentLineId":%d,"qty":1,"barcode":"WRONG"}
                                """.formatted(shipment.lineId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/shipments/" + shipment.id() + "/scan")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipmentLineId":%d,"qty":2,"barcode":"6901000750"}
                                """.formatted(shipment.lineId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED"));
    }

    @Test
    @CaseId("TC-API-076")
    @DisplayName("发货确认 HTTP：幂等重放 200；新键 409；库存只扣一次")
    void confirmHttp_idempotent() throws Exception {
        SalesOrder order = approvedOrder(3, "api-confirm-076");
        ShipmentRef shipment = plan(order, "api-confirm-076-plan");
        scanAll(shipment, 3, "api-confirm-076-scan");

        mvc.perform(post("/api/v1/shipments/" + shipment.id() + "/confirm")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-confirm-076-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mvc.perform(post("/api/v1/shipments/" + shipment.id() + "/confirm")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-confirm-076-1"))
                .andExpect(status().isOk());
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(10);

        mvc.perform(post("/api/v1/shipments/" + shipment.id() + "/confirm")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-confirm-076-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAL_SHIPMENT_STATUS_ILLEGAL"));
    }

    // ─────────────────────────── 应收 / 收款 / 信用 ───────────────────────────

    @Test
    @CaseId("TC-API-077")
    @DisplayName("收款核销不守恒 HTTP：409 SAL_RECEIPT_UNBALANCED，不生成收款单")
    void receiptUnbalancedHttp() throws Exception {
        long receivableId = shippedReceivable("api-ar-077");

        mvc.perform(post("/api/v1/customer-receipts")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-receipt-077")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"amount":5000,
                                 "allocations":[{"receivableId":%d,"amount":3000}]}
                                """.formatted(customerId, receivableId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAL_RECEIPT_UNBALANCED"));
        assertThat(count("customer_receipts")).isZero();
    }

    @Test
    @CaseId("TC-API-078")
    @DisplayName("核销超额 HTTP：409 SAL_RECEIPT_OVER_ALLOCATED，余额不变")
    void receiptOverAllocationHttp() throws Exception {
        long receivableId = shippedReceivable("api-ar-078");

        mvc.perform(post("/api/v1/customer-receipts")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-receipt-078")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"amount":10001,
                                 "allocations":[{"receivableId":%d,"amount":10001}]}
                                """.formatted(customerId, receivableId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAL_RECEIPT_OVER_ALLOCATED"));
        assertThat(receivables.findById(receivableId).orElseThrow().balance()).isEqualTo(10000);
    }

    @Test
    @CaseId("TC-API-079")
    @DisplayName("应收查询 HTTP：summary 与列表返回余额字段")
    void receivableQueryHttp() throws Exception {
        long receivableId = shippedReceivable("api-ar-079");

        mvc.perform(get("/api/v1/receivables/summary")
                        .param("customerId", String.valueOf(customerId))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippedAmount").value(10000))
                .andExpect(jsonPath("$.data.balance").value(10000));

        mvc.perform(get("/api/v1/receivables")
                        .param("customerId", String.valueOf(customerId))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(receivableId));
    }

    @Test
    @CaseId("TC-API-080")
    @DisplayName("信用 HTTP：GET 默认 / PUT 调整生效 / 负数 400")
    void creditHttp() throws Exception {
        mvc.perform(get("/api/v1/customers/" + customerId + "/credit")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditLimit").value(0));

        mvc.perform(put("/api/v1/customers/" + customerId + "/credit")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":200000,\"creditDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creditLimit").value(200000))
                .andExpect(jsonPath("$.data.creditDays").value(30));

        mvc.perform(put("/api/v1/customers/" + customerId + "/credit")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creditLimit\":-1,\"creditDays\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    // ─────────────────────────── 权限 / 参数 / 主数据扩展 ───────────────────────────

    @Test
    @CaseId("TC-API-081")
    @DisplayName("未登录访问报价列表：401 IAM_UNAUTHENTICATED")
    void quoteList_withoutToken_then401() throws Exception {
        mvc.perform(get("/api/v1/sales-quotes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IAM_UNAUTHENTICATED"));
    }

    @Test
    @CaseId("TC-API-082")
    @DisplayName("VIEWER 无 sal:quote：建报价 403 IAM_FORBIDDEN")
    void quoteCreate_withViewer_then403() throws Exception {
        data.user("v23_viewer", "Viewer1!", "VIEWER", encoder);
        String viewerAuth = "Bearer " + authentication.login("v23_viewer", "Viewer1!").token();

        mvc.perform(post("/api/v1/sales-quotes")
                        .header("Authorization", viewerAuth)
                        .header("X-Request-Id", "api-quote-082")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"lines":[{"skuId":%d,"qty":1,"unitPriceAmount":1000}]}
                                """.formatted(customerId, skuA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
    }

    @Test
    @CaseId("TC-API-083")
    @DisplayName("写接口缺 X-Request-Id：400 SYS_VALIDATION_ERROR")
    void autoSplit_withoutRequestId_then400() throws Exception {
        mvc.perform(post("/api/v1/sales-orders/auto-split")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"lines":[{"skuId":%d,"qty":1,"unitPriceAmount":1000}]}
                                """.formatted(customerId, skuA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-API-084")
    @DisplayName("主数据扩展 HTTP：SKU 季节筛选、仓库 priority 排序")
    void masterDataDimensionsHttp() throws Exception {
        mvc.perform(post("/api/v1/skus")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-sku-084")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"color":"季节白","size":"XL",
                                 "retailPriceAmount":12900,"seasonCode":"2026SS",
                                 "bandCode":"BAND-API","launchDate":"2026-03-01"}
                                """.formatted(productId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.seasonCode").value("2026SS"))
                .andExpect(jsonPath("$.data.seasonYear").value(2026));

        mvc.perform(get("/api/v1/skus")
                        .param("seasonCode", "2026SS")
                        .param("seasonYear", "2026")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mvc.perform(post("/api/v1/warehouses")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "api-wh-084")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"WH-API-084\",\"name\":\"接口仓\",\"priority\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.priority").value(5));

        mvc.perform(get("/api/v1/warehouses").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].priority").value(5));
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private record ShipmentRef(long id, long lineId) {
    }

    private long productId() {
        return jdbc.queryForObject("SELECT id FROM products WHERE code = 'P-V23-API'", Long.class);
    }

    private void stock(long skuId, long wh, int qty) {
        data.stock(skuId, wh, "", null, qty);
        data.ledger(skuId, wh, "", qty, "PURCHASE_IN", "SEED-API-" + skuId + "-" + wh);
    }

    private SalesOrder approvedOrder(int qty, String requestId) {
        stock(skuA, warehouseId, qty + 10);
        SalesOrder order = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "接口发货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, qty, 1000L, null)),
                requestId));
        salesOrders.submit(order.id());
        return salesOrders.approve(order.id());
    }

    private ShipmentRef plan(SalesOrder order, String requestId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sales-orders/" + order.id() + "/shipments")
                        .header("Authorization", auth)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode shipment = data(result).get("shipments").get(0);
        return new ShipmentRef(shipment.get("id").asLong(),
                shipment.get("lines").get(0).get("shipmentLineId").asLong());
    }

    private void scanAll(ShipmentRef shipment, int qty, String requestId) throws Exception {
        mvc.perform(post("/api/v1/shipments/" + shipment.id() + "/scan")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipmentLineId":%d,"qty":%d}
                                """.formatted(shipment.lineId(), qty)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED"));
    }

    private long shippedReceivable(String requestId) {
        stock(skuA, warehouseId, 20);
        SalesOrder order = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "接口应收",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 10, 1000L, null)),
                requestId));
        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        salesOrders.ship(order.id(), requestId + "-ship");
        return receivables.findByOrderId(order.id()).orElseThrow().id();
    }

    private long approvedQuote(int qty, long price, String requestId) {
        SalesQuote quote = quotes.create(new SalesQuoteApplicationService.CreateCommand(
                customerId, LocalDate.of(2025, 6, 1), LocalDate.of(2027, 12, 31), "接口报价",
                List.of(new SalesQuoteApplicationService.CreateLineCommand(
                        skuA, qty, price, null)), requestId));
        quotes.submit(quote.id());
        return quotes.approve(quote.id()).id();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
