package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.application.SalesQuoteApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesQuote;
import com.erp.sal.domain.SalesQuoteLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 报价单 → 出货单转化（FR-SAL-05）。
 *
 * <p>核心断言：行不丢、转化复用建单预占与信用校验；失败时报价单保持 APPROVED 且无预占。
 */
@SpringBootTest
@DisplayName("销售 · 报价单转化")
class SalesQuoteIT extends PostgresTestBase {

    @Autowired
    private SalesQuoteApplicationService quotes;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private WarehouseApplicationService warehouseService;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long customerId;
    private long warehouseId;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        customerId = data.customer("CUS-QUOTE");
        warehouseId = data.warehouse("WH-QUOTE");
        long productId = data.product("P-QUOTE", "报价测试商品");
        skuId = data.sku(productId, "白色", "L");
        stock(warehouseId, 50);
    }

    @Test
    @CaseId("TC-SAL-025")
    @DisplayName("★ 建报价单：DRAFT、金额按万分比折扣计算、不预占库存、不生成出货单")
    void createQuote_doesNotReserve() {
        SalesQuote quote = createQuote(5, 12900L, 9000, "req-quote-025");

        assertThat(quote.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(quote.quoteNo()).matches("QT\\d{8}-\\d{4,}");
        assertThat(quote.totalQty()).isEqualTo(5);
        assertThat(quote.totalAmount()).isEqualTo(SalesOrderApplicationService.lineAmount(
                5, 12900L, 9000));
        assertThat(quote.lines()).singleElement()
                .satisfies(l -> assertThat(l.amount()).isEqualTo(quote.totalAmount()));
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
        assertThat(count("sales_orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
    }

    @Test
    @CaseId("TC-SAL-026")
    @DisplayName("报价建单幂等：同 requestId 返回同一张报价单，不重复建行")
    void createQuote_isIdempotent() {
        SalesQuote first = createQuote(3, 1000L, null, "req-quote-026");
        SalesQuote second = createQuote(3, 1000L, null, "req-quote-026");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("sales_quotes")).isEqualTo(1);
        assertThat(count("sales_quote_lines")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-SAL-027")
    @DisplayName("报价校验：空明细/数量非正/重复 SKU/折扣越界/有效期早于报价日 → 400，无残留")
    void createQuote_validation() {
        assertThatThrownBy(() -> quotes.create(new SalesQuoteApplicationService.CreateCommand(
                customerId, LocalDate.of(2025, 6, 1), null, null, List.of(), "req-q-bad-1")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> createQuote(0, 1000L, null, "req-q-bad-2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> quotes.create(new SalesQuoteApplicationService.CreateCommand(
                customerId, LocalDate.of(2025, 6, 1), null, null,
                List.of(new SalesQuoteApplicationService.CreateLineCommand(skuId, 1, 1000L, null),
                        new SalesQuoteApplicationService.CreateLineCommand(skuId, 2, 1000L, null)),
                "req-q-bad-3")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> createQuote(1, 1000L, 0, "req-q-bad-4"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> quotes.create(new SalesQuoteApplicationService.CreateCommand(
                customerId, LocalDate.of(2025, 6, 2), LocalDate.of(2025, 6, 1), null,
                List.of(new SalesQuoteApplicationService.CreateLineCommand(skuId, 1, 1000L, null)),
                "req-q-bad-5")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThat(count("sales_quotes")).isZero();
    }

    @Test
    @CaseId("TC-SAL-028")
    @DisplayName("报价状态机：DRAFT→SUBMITTED→APPROVED；DRAFT 直接审核非法")
    void quoteStatusMachine() {
        SalesQuote quote = createQuote(2, 1000L, null, "req-quote-028");

        assertThat(quotes.submit(quote.id()).status()).isEqualTo(DocumentStatus.SUBMITTED);
        assertThat(quotes.approve(quote.id()).status()).isEqualTo(DocumentStatus.APPROVED);

        SalesQuote draft = createQuote(1, 1000L, null, "req-quote-028b");
        assertThatThrownBy(() -> quotes.approve(draft.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_QUOTE_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-SAL-029")
    @DisplayName("★ 转化：行不丢（数量/单价/折扣/金额一致），调用建单预占，报价单 COMPLETED")
    void convert_preservesLinesAndReserves() {
        SalesQuote quote = approvedQuote(5, 12900L, 9000, "req-quote-029");

        SalesOrder order = quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(warehouseId, null, "req-q-convert-029"));

        assertThat(order.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(order.lines()).hasSize(1);
        assertThat(order.lines().get(0).qty()).isEqualTo(5);
        assertThat(order.lines().get(0).unitPriceAmount()).isEqualTo(12900L);
        assertThat(order.lines().get(0).discountRate()).isEqualTo(9000);
        assertThat(order.totalAmount()).isEqualTo(quote.totalAmount());
        assertThat(data.reserved(skuId, warehouseId, "")).isEqualTo(5);

        SalesQuote converted = quotes.require(quote.id());
        assertThat(converted.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(converted.convertedOrderId()).isEqualTo(order.id());
        assertThat(converted.lines()).hasSize(quote.lines().size());
        assertThat(converted.lines()).extracting(SalesQuoteLine::qty)
                .containsExactlyElementsOf(quote.lines().stream().map(SalesQuoteLine::qty).toList());
    }

    @Test
    @CaseId("TC-SAL-030")
    @DisplayName("转化幂等：同键返回首次订单且不重复预占；新键再转化 → 409")
    void convert_isIdempotent() {
        SalesQuote quote = approvedQuote(4, 1000L, null, "req-quote-030");
        SalesOrder first = quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-030"));

        SalesOrder replay = quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-030"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(data.reserved(skuId, warehouseId, "")).isEqualTo(4);
        assertThat(count("sales_orders")).isEqualTo(1);

        assertThatThrownBy(() -> quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-030-new")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_QUOTE_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-SAL-031")
    @DisplayName("未审核报价不可转化：DRAFT/SUBMITTED → 409，库存不变")
    void convert_nonApproved_thenReject() {
        SalesQuote draft = createQuote(2, 1000L, null, "req-quote-031");
        assertThatThrownBy(() -> quotes.convert(draft.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-031")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_QUOTE_STATUS_ILLEGAL);
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
        assertThat(count("sales_orders")).isZero();
    }

    @Test
    @CaseId("TC-SAL-032")
    @DisplayName("已过期报价不可转化：报价保持 APPROVED，无订单/预占")
    void convert_expired_thenReject() {
        SalesQuote quote = quotes.create(new SalesQuoteApplicationService.CreateCommand(
                customerId, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 2), null,
                List.of(new SalesQuoteApplicationService.CreateLineCommand(skuId, 2, 1000L, null)),
                "req-quote-032"));
        quotes.submit(quote.id());
        quotes.approve(quote.id());

        assertThatThrownBy(() -> quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-032")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_QUOTE_STATUS_ILLEGAL);

        assertThat(quotes.require(quote.id()).status()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(count("sales_orders")).isZero();
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-033")
    @DisplayName("★ 转化时库存不足：整单回滚，报价单保持 APPROVED，无订单/预占")
    void convert_insufficientStock_keepsQuoteApproved() {
        jdbc.update("UPDATE inventory_balances SET on_hand_qty = 3 WHERE sku_id = ?", skuId);
        jdbc.update("DELETE FROM inventory_ledger WHERE sku_id = ?", skuId);
        jdbc.update("""
                INSERT INTO inventory_ledger
                    (sku_id, warehouse_id, batch_no, change_qty, biz_type, biz_ref_no, occurred_at)
                VALUES (?, ?, '', 3, 'PURCHASE_IN', 'SEED-Q-033', now())
                """, skuId, warehouseId);
        SalesQuote quote = approvedQuote(10, 1000L, null, "req-quote-033");

        assertThatThrownBy(() -> quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-033")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        assertThat(quotes.require(quote.id()).status()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(count("sales_orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-034")
    @DisplayName("★ 转化时信用超额：报价保持 APPROVED，无订单/预占（FR-SAL-09 联动）")
    void convert_overCreditLimit_keepsQuoteApproved() {
        jdbc.update("UPDATE customers SET credit_limit = 1000 WHERE id = ?", customerId);
        SalesQuote quote = approvedQuote(5, 1000L, null, "req-quote-034");

        assertThatThrownBy(() -> quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-034")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);

        assertThat(quotes.require(quote.id()).status()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(count("sales_orders")).isZero();
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
        assertThat(count("customer_credit_snapshots")).isZero();
    }

    @Test
    @CaseId("TC-SAL-035")
    @DisplayName("转化省略 warehouseId：按优先级自动选仓/拆单，报价单完成")
    void convert_withoutWarehouse_usesAutoSplit() {
        long secondWarehouse = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-QUOTE-2", "报价二仓", null, true, 500)).id();
        data.stock(skuId, secondWarehouse, "", null, 5);
        data.ledger(skuId, secondWarehouse, "", 5, "PURCHASE_IN", "SEED-Q-035-2");
        SalesQuote quote = approvedQuote(15, 1000L, null, "req-quote-035");

        SalesOrder order = quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(null, null, "req-q-convert-035"));

        assertThat(order.totalQty()).isEqualTo(15);
        assertThat(order.lines().get(0).reservations()).hasSize(2);
        assertThat(order.lines().get(0).reservations().stream()
                .mapToInt(r -> r.reservedQty()).sum()).isEqualTo(15);
        assertThat(quotes.require(quote.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
    }

    @Test
    @CaseId("TC-SAL-036")
    @DisplayName("作废已审核报价：状态 VOIDED，之后不能转化")
    void voidApprovedQuote_cannotConvert() {
        SalesQuote quote = approvedQuote(2, 1000L, null, "req-quote-036");

        SalesQuote voided = quotes.voidOrder(quote.id());
        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);

        assertThatThrownBy(() -> quotes.convert(quote.id(),
                new SalesQuoteApplicationService.ConvertCommand(
                        warehouseId, null, "req-q-convert-036")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_QUOTE_STATUS_ILLEGAL);
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private SalesQuote createQuote(int qty, long price, Integer discount, String requestId) {
        return quotes.create(new SalesQuoteApplicationService.CreateCommand(
                customerId, LocalDate.of(2025, 6, 1), LocalDate.of(2027, 12, 31), "报价",
                List.of(new SalesQuoteApplicationService.CreateLineCommand(
                        skuId, qty, price, discount)), requestId));
    }

    private SalesQuote approvedQuote(int qty, long price, Integer discount, String requestId) {
        SalesQuote quote = createQuote(qty, price, discount, requestId);
        quotes.submit(quote.id());
        return quotes.approve(quote.id());
    }

    private void stock(long wh, int qty) {
        data.stock(skuId, wh, "", null, qty);
        data.ledger(skuId, wh, "", qty, "PURCHASE_IN", "SEED-Q-" + wh);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
