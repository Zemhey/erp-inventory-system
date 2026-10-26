package com.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingType;
import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.InventorySummaryExportRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.rpt.domain.ReportRow;
import com.erp.support.CaseId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CSV 导出（FR-RPT-09，TC-RPT-042~052）。
 *
 * <p><b>断言方式：同一条查询结果逐格比对</b>。每个用例先通过 HTTP 取 CSV，
 * 再调用页面用的同一个 ApplicationService 方法，按页面格式把服务结果拼成期望格，
 * 逐行逐列断言 —— 不满足于"返回 200 且有内容"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("报表 · CSV 导出（与页面逐格一致、BOM、转义、只读）")
public class ReportExportIT extends ReportV28TestSupport {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;

    private String auth;

    @BeforeEach
    void login() {
        data.user("rpt_export_admin", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("rpt_export_admin", "Admin1!").token();
    }

    @Test
    @CaseId("TC-RPT-042")
    @DisplayName("★ 进货统计 CSV == 同一次服务查询逐格；逗号/引号名称可正确往返")
    void purchaseCsv_matchesServiceRows() throws Exception {
        seedAll();
        jdbc.update("UPDATE suppliers SET name = ? WHERE id = ?", "供应商,\"甲\"", supplier1);

        List<List<String>> csv = getCsv("/reports/purchase/export",
                "from", "2026-03-01", "to", "2026-03-31", "groupBy", "supplier");
        List<ReportRow> rows = reports.purchaseReport(LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), ReportGroupBy.SUPPLIER, null);

        assertThat(csv).hasSize(rows.size() + 1);
        assertThat(csv.get(0)).containsExactly("分组键", "名称", "数量", "金额（元）", "单据数");
        for (int i = 0; i < rows.size(); i++) {
            ReportRow r = rows.get(i);
            assertThat(csv.get(i + 1)).containsExactly(r.key(), r.label(),
                    String.valueOf(r.qty()), money(r.amount()), String.valueOf(r.orderCount()));
        }
        assertThat(rows).anySatisfy(r -> assertThat(r.label()).isEqualTo("供应商,\"甲\""));
    }

    @Test
    @CaseId("TC-RPT-043")
    @DisplayName("★ 出货统计 CSV == 服务查询，含 .5 尾差舍入与订单数")
    void salesCsv_matchesServiceRows_includingRounding() throws Exception {
        seedAll();

        List<List<String>> csv = getCsv("/reports/sales/export",
                "from", "2026-03-01", "to", "2026-03-31", "groupBy", "customer");
        List<ReportRow> rows = reports.salesReport(LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), ReportGroupBy.CUSTOMER, null);

        assertThat(csv).hasSize(rows.size() + 1);
        for (int i = 0; i < rows.size(); i++) {
            ReportRow r = rows.get(i);
            assertThat(csv.get(i + 1)).containsExactly(r.key(), r.label(),
                    String.valueOf(r.qty()), money(r.amount()), String.valueOf(r.orderCount()));
        }
        assertThat(rows).anySatisfy(r -> {
            if (r.key().equals("CUS-V28-1")) {
                assertThat(r.amount()).as("3×12900×9950/10000 = 38506.5 → 38507").isEqualTo(38507);
            }
        });
    }

    @Test
    @CaseId("TC-RPT-044")
    @DisplayName("★ 进销存 CSV 与服务导出查询逐格；名称与页面同一格式")
    void inventoryCsv_matchesServiceRows() throws Exception {
        seedAll();

        List<List<String>> csv = getCsv("/reports/inventory-summary/export", "period", "2026-03");
        List<InventorySummaryExportRow> rows = reports.inventorySummaryExport(
                YearMonth.of(2026, 3), null);

        // V4-4：前 6 列不变，末尾追加 SKU编码 + 四个金额列（分→元；未月结为 -）。
        assertThat(csv.get(0)).containsExactly("SKU", "仓库", "期初", "本期入库", "本期出库", "期末",
                "SKU编码", "期初金额（元）", "本期入库金额（元）", "本期出库金额（元）",
                "期末金额（元）");
        assertThat(csv).hasSize(rows.size() + 1);
        for (int i = 0; i < rows.size(); i++) {
            InventorySummaryExportRow r = rows.get(i);
            assertThat(csv.get(i + 1)).containsExactly(r.skuName(), r.warehouseName(),
                    String.valueOf(r.openingQty()), String.valueOf(r.inboundQty()),
                    String.valueOf(r.outboundQty()), String.valueOf(r.closingQty()),
                    r.skuCode(), moneyCell(r.openingCostAmount()), moneyCell(r.inboundCostAmount()),
                    moneyCell(r.outboundCostAmount()), moneyCell(r.closingCostAmount()));
        }
        assertThat(rows).anySatisfy(r ->
                assertThat(r.skuName()).isEqualTo("V2-8 商品A · 白色-L"));
    }

    /** 与 {@code ReportCsvService.moneyOrDash} 同一规则：null（未月结）落 "-"，不是 0.00。 */
    private static String moneyCell(Long fen) {
        return fen == null ? "-"
                : java.math.BigDecimal.valueOf(fen, 2).toPlainString();
    }

    @Test
    @CaseId("TC-RPT-045")
    @DisplayName("★ 经营日报/月报 CSV == 服务查询；日/月两种粒度都验证")
    void operatingCsv_matchesServiceRows() throws Exception {
        seedAll();

        for (String dimension : List.of("day", "month")) {
            List<List<String>> csv = getCsv("/reports/operating/export",
                    "from", "2026-03-01", "to", "2026-03-31", "dimension", dimension);
            List<OperatingReportRow> rows = reports.operatingReport(
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                    "month".equals(dimension) ? ReportPeriodDim.MONTH : ReportPeriodDim.DAY, null);

            assertThat(csv).hasSize(rows.size() + 1);
            for (int i = 0; i < rows.size(); i++) {
                OperatingReportRow r = rows.get(i);
                assertThat(csv.get(i + 1)).containsExactly(r.period(),
                        String.valueOf(r.openingQty()), String.valueOf(r.inboundQty()),
                        String.valueOf(r.outboundQty()), String.valueOf(r.closingQty()),
                        String.valueOf(r.purchaseQty()), money(r.purchaseAmount()),
                        String.valueOf(r.salesQty()), money(r.salesAmount()));
            }
        }
    }

    @Test
    @CaseId("TC-RPT-046")
    @DisplayName("★ 周转 CSV == 服务查询：周转天数一位小数，无出库为 -，呆滞 是/否")
    void turnoverCsv_matchesServiceRows() throws Exception {
        seedAll();

        List<List<String>> csv = getCsv("/reports/inventory-turnover/export",
                "from", "2026-03-01", "to", "2026-03-31", "deadDays", "30");
        List<InventoryTurnoverRow> rows = reports.inventoryTurnover(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 30, null);

        assertThat(csv).hasSize(rows.size() + 1);
        for (int i = 0; i < rows.size(); i++) {
            InventoryTurnoverRow r = rows.get(i);
            assertThat(csv.get(i + 1)).containsExactly(r.skuCode(), r.skuName(),
                    r.warehouseName(), String.valueOf(r.openingQty()),
                    String.valueOf(r.closingQty()), String.valueOf(r.outboundQty()),
                    turnover(r.turnoverDays()), String.valueOf(r.idleDays()),
                    r.stale() ? "是" : "否");
        }
        assertThat(rows).anySatisfy(r -> assertThat(r.turnoverDays()).isNull());
    }

    @Test
    @CaseId("TC-RPT-047")
    @DisplayName("★ 客户贡献 CSV == 服务查询；Top N 行 + 全量汇总行")
    void contributionCsv_matchesServiceRowsAndSummary() throws Exception {
        seedAll();

        List<List<String>> csv = getCsv("/reports/customer-contribution/export",
                "from", "2026-03-01", "to", "2026-03-31", "limit", "20");
        CustomerContributionResult result = reports.customerContribution(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, 20);

        assertThat(csv.get(0)).containsExactly("客户编码", "客户名称", "单据数", "销量",
                "销售额（元）", "是否复购", "客户类型");
        for (int i = 0; i < result.items().size(); i++) {
            CustomerContributionRow r = result.items().get(i);
            assertThat(csv.get(i + 1)).containsExactly(r.customerCode(), r.customerName(),
                    String.valueOf(r.orderCount()), String.valueOf(r.qty()), money(r.amount()),
                    r.repurchase() ? "是" : "否", r.newCustomer() ? "新客" : "回头客");
        }
        List<String> summary = rowStartingWith(csv, "汇总");
        assertThat(summary).containsExactly("汇总",
                String.valueOf(result.summary().customerCount()),
                String.valueOf(result.summary().repurchaseCount()),
                rate(result.summary().repurchaseRateBp()),
                String.valueOf(result.summary().newCustomerCount()),
                String.valueOf(result.summary().returningCustomerCount()),
                String.valueOf(result.summary().totalQty()),
                money(result.summary().totalAmount()),
                String.valueOf(result.summary().orderCount()));
    }

    @Test
    @CaseId("TC-RPT-048")
    @DisplayName("★ 账龄 AR/AP CSV == 服务查询；明细行与汇总行逐格，桶合计守恒")
    void agingCsv_matchesServiceRowsAndSummary() throws Exception {
        seedAll();

        for (String type : List.of("AR", "AP")) {
            List<List<String>> csv = getCsv("/reports/aging/export", "type", type,
                    "asOf", "2026-03-31");
            AgingResult result = reports.aging(AgingType.valueOf(type), AS_OF);

            for (int i = 0; i < result.items().size(); i++) {
                AgingRow r = result.items().get(i);
                assertThat(csv.get(i + 1)).containsExactly(r.partnerCode(), r.partnerName(),
                        money(r.balanceAmount()), money(r.bucket0()), money(r.bucket1()),
                        money(r.bucket2()), money(r.bucket3()));
            }
            List<String> summary = rowStartingWith(csv, "汇总");
            assertThat(summary).containsExactly("汇总",
                    String.valueOf(result.summary().partnerCount()),
                    money(result.summary().totalBalance()),
                    money(result.summary().bucket0()), money(result.summary().bucket1()),
                    money(result.summary().bucket2()), money(result.summary().bucket3()));
            assertThat(result.summary().bucket0() + result.summary().bucket1()
                    + result.summary().bucket2() + result.summary().bucket3())
                    .isEqualTo(result.summary().totalBalance());
        }
    }

    @Test
    @CaseId("TC-RPT-049")
    @DisplayName("CSV 文件形态：UTF-8 BOM、CRLF、text/csv、attachment 文件名")
    void csvFileShape_isExcelFriendly() throws Exception {
        seedAll();
        var response = mvc.perform(get("/api/v1/reports/sales/export")
                        .header("Authorization", auth)
                        .param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andReturn().getResponse();

        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment").contains("sales-2026-03-01_2026-03-31.csv");
        byte[] body = response.getContentAsByteArray();
        assertThat(body).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(body, StandardCharsets.UTF_8);
        assertThat(text).contains("\r\n");
        assertThat(text.lines().findFirst().orElseThrow()).startsWith("\uFEFF");
    }

    @Test
    @CaseId("TC-RPT-050")
    @DisplayName("导出遵守筛选条件：换仓库/换区间后行集变化，且仍与服务查询一致")
    void exportHonorsFilters() throws Exception {
        seedAll();

        List<List<String>> csvWh1 = getCsv("/reports/sales/export",
                "from", "2026-03-01", "to", "2026-03-31", "warehouseId", String.valueOf(wh1));
        List<ReportRow> rowsWh1 = reports.salesReport(LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31), ReportGroupBy.CUSTOMER, wh1);
        assertThat(csvWh1).hasSize(rowsWh1.size() + 1);

        List<List<String>> empty = getCsv("/reports/sales/export",
                "from", "2020-01-01", "to", "2020-01-31");
        assertThat(empty).as("空区间仍有表头").hasSize(1);
    }

    @Test
    @CaseId("TC-RPT-051")
    @DisplayName("导出同样受鉴权保护：未登录 401")
    void exportRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/reports/purchase/export")
                        .param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @CaseId("TC-RPT-052")
    @DisplayName("★ 导出是只读的：跑完全部导出，单据/流水/余额/应收应付计数不变")
    void exportsAreReadOnly() throws Exception {
        seedAll();
        Map<String, Integer> before = counts();

        getCsv("/reports/purchase/export", "from", "2026-03-01", "to", "2026-03-31");
        getCsv("/reports/sales/export", "from", "2026-03-01", "to", "2026-03-31");
        getCsv("/reports/inventory-summary/export", "period", "2026-03");
        getCsv("/reports/operating/export", "from", "2026-03-01", "to", "2026-03-31");
        getCsv("/reports/inventory-turnover/export", "from", "2026-03-01", "to", "2026-03-31");
        getCsv("/reports/customer-contribution/export", "from", "2026-03-01", "to", "2026-03-31");
        getCsv("/reports/aging/export", "type", "AR", "asOf", "2026-03-31");
        getCsv("/reports/aging/export", "type", "AP", "asOf", "2026-03-31");

        assertThat(counts()).isEqualTo(before);
    }

    // ─────────────────────────── 造数 ───────────────────────────

    /**
     * 一套数据覆盖 7 种导出：进货含逗号/引号名称与 .45 元；出货含 .5 尾差；
     * 流水含期初/出入；贡献含复购/新客；AR/AP 含各账龄桶。
     */
    private void seedAll() {
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        PurchaseOrder po1 = purchase("po-exp-1", supplier1, wh1,
                LocalDate.of(2026, 1, 5), sku1, 100, 1000);
        at(LocalDateTime.of(2026, 2, 1, 9, 0));
        PurchaseOrder po2 = purchase("po-exp-2", supplier2, wh1,
                LocalDate.of(2026, 2, 1), sku2, 10, 2000);
        PurchaseOrder po3 = purchase("po-exp-3", supplier1, wh1,
                LocalDate.of(2026, 2, 1), sku1, 50, 1000);
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        PurchaseOrder po4 = purchase("po-exp-4", supplier1, wh1,
                LocalDate.of(2026, 3, 2), sku1, 3, 4115);
        at(LocalDateTime.of(2026, 3, 4, 9, 0));
        PurchaseOrder po5 = purchase("po-exp-5", supplier2, wh2,
                LocalDate.of(2026, 3, 4), sku1, 50, 1000);

        at(LocalDateTime.of(2026, 1, 15, 9, 0));
        ship("so-exp-hist", customer1, wh1, LocalDate.of(2026, 1, 15), sku1, 1, 1000, 10000);
        at(LocalDateTime.of(2026, 3, 3, 9, 0));
        var so1 = ship("so-exp-1", customer1, wh1, LocalDate.of(2026, 3, 3),
                sku1, 3, 12900, 9950);
        at(LocalDateTime.of(2026, 3, 10, 9, 0));
        var so2 = ship("so-exp-2", customer2, wh1, LocalDate.of(2026, 3, 10),
                sku1, 2, 1000, 10000);
        at(LocalDateTime.of(2026, 3, 20, 9, 0));
        var so3 = ship("so-exp-3", customer3, wh2, LocalDate.of(2026, 3, 20),
                sku1, 1, 1000, 10000);

        setReceivableDueDate(so1.id(), AS_OF.minusDays(31));
        setReceivableDueDate(so2.id(), null);
        setReceivableDueDate(so3.id(), AS_OF.minusDays(95));

        setPayableBizDate(po1.id(), AS_OF.minusDays(31));
        setPayableBizDate(po2.id(), AS_OF.minusDays(61));
        setPayableBizDate(po3.id(), AS_OF.minusDays(5));
        setPayableBizDate(po4.id(), AS_OF.minusDays(95));
        setPayableBizDate(po5.id(), AS_OF.minusDays(40));
        at(DEFAULT_NOW);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : List.of("purchase_orders", "sales_orders", "inventory_ledger",
                "inventory_balances", "customer_receivables", "supplier_payables")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    // ─────────────────────────── CSV / HTTP 辅助 ───────────────────────────

    private List<List<String>> getCsv(String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1" + path)
                .header("Authorization", auth);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        var response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andReturn().getResponse();
        String disposition = response.getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment").contains(".csv");
        return parseCsv(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 最小 CSV 解析：支持引号、双写引号、CRLF；BOM 不进入第一格。 */
    public static List<List<String>> parseCsv(String raw) {
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r') {
                continue;
            } else if (c == '\n') {
                row.add(cell.toString());
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static List<String> rowStartingWith(List<List<String>> rows, String first) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            List<String> row = rows.get(i);
            if (!row.isEmpty() && row.get(0).equals(first)) {
                return row;
            }
        }
        throw new AssertionError("缺少 " + first + " 行");
    }

    private static String money(long fen) {
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    private static String turnover(BigDecimal days) {
        return days == null ? "-" : days.setScale(1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String rate(long bp) {
        return BigDecimal.valueOf(bp, 2).toPlainString() + "%";
    }
}
