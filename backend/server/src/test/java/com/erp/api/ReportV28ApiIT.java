package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.rpt.ReportExportIT;
import com.erp.rpt.ReportV28TestSupport;
import com.erp.support.CaseId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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
 * V2-8 新报表 HTTP 契约（TC-API-135~144）。
 *
 * <p>服务层证明"数对"，本类证明"接口对"：状态码、包络字段、默认值、
 * 参数校验、导出响应头，以及跑完所有报表后库表零变化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · V2-8 报表与导出")
class ReportV28ApiIT extends ReportV28TestSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    private String auth;

    @BeforeEach
    void seedHttp() {
        data.user("rpt_v28_admin", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("rpt_v28_admin", "Admin1!").token();
        at(LocalDateTime.of(2026, 3, 5, 10, 0));
        purchase("po-api-v28", supplier1, wh1, LocalDate.of(2026, 3, 2), sku1, 10, 1000);
        at(LocalDateTime.of(2026, 3, 5, 11, 0));
        ship("so-api-v28", customer1, wh1, LocalDate.of(2026, 3, 3),
                sku1, 3, 1000, 10000);
    }

    @Test
    @CaseId("TC-API-135")
    @DisplayName("★ 四个新报表可达：包络、字段名与数值符合契约")
    void newReports_envelopeAndFields() throws Exception {
        mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items.length()").value(31))
                .andExpect(jsonPath("$.data.items[0].period").value("2026-03-01"))
                .andExpect(jsonPath("$.data.items[1].purchaseQty").value(10))
                .andExpect(jsonPath("$.data.items[1].purchaseAmount").value(10000))
                .andExpect(jsonPath("$.data.items[4].inboundQty").value(10))
                .andExpect(jsonPath("$.data.items[4].outboundQty").value(3))
                .andExpect(jsonPath("$.data.items[4].closingQty").value(7));

        mvc.perform(authed(get("/api/v1/reports/inventory-turnover")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(sku1))
                .andExpect(jsonPath("$.data.items[0].skuCode").value("P-V28-1-白色-L"))
                .andExpect(jsonPath("$.data.items[0].warehouseName").value("测试仓-WH-V28-1"))
                .andExpect(jsonPath("$.data.items[0].closingQty").value(7))
                .andExpect(jsonPath("$.data.items[0].outboundQty").value(3))
                .andExpect(jsonPath("$.data.items[0].turnoverDays").isNumber())
                .andExpect(jsonPath("$.data.items[0].stale").value(false));

        mvc.perform(authed(get("/api/v1/reports/customer-contribution")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].customerCode").value("CUS-V28-1"))
                .andExpect(jsonPath("$.data.items[0].orderCount").value(1))
                .andExpect(jsonPath("$.data.items[0].qty").value(3))
                .andExpect(jsonPath("$.data.items[0].amount").value(3000))
                .andExpect(jsonPath("$.data.items[0].repurchase").value(false))
                .andExpect(jsonPath("$.data.items[0].newCustomer").value(true))
                .andExpect(jsonPath("$.data.summary.customerCount").value(1))
                .andExpect(jsonPath("$.data.summary.repurchaseRateBp").value(0));

        mvc.perform(authed(get("/api/v1/reports/aging")
                        .param("type", "AR").param("asOf", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].partnerCode").value("CUS-V28-1"))
                .andExpect(jsonPath("$.data.items[0].balanceAmount").value(3000))
                .andExpect(jsonPath("$.data.items[0].bucket0").value(3000))
                .andExpect(jsonPath("$.data.summary.totalBalance").value(3000));

        mvc.perform(authed(get("/api/v1/reports/aging")
                        .param("type", "AP").param("asOf", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].partnerCode").value("SUP-V28-1"))
                .andExpect(jsonPath("$.data.items[0].balanceAmount").value(10000));
    }

    @Test
    @CaseId("TC-API-136")
    @DisplayName("参数非法一律 400：dimension / deadDays / limit")
    void invalidReportParams_badRequest() throws Exception {
        mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("dimension", "week")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message", Matchers.containsString("dimension")));

        mvc.perform(authed(get("/api/v1/reports/inventory-turnover")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("deadDays", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("deadDays")));

        mvc.perform(authed(get("/api/v1/reports/customer-contribution")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("limit", "201")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("limit")));
    }

    @Test
    @CaseId("TC-API-137")
    @DisplayName("账龄参数：type 非法/缺失、asOf 缺失均 400；大小写不敏感")
    void agingParams_validation() throws Exception {
        mvc.perform(authed(get("/api/v1/reports/aging")
                        .param("type", "XX").param("asOf", "2026-03-31")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("type")));

        mvc.perform(authed(get("/api/v1/reports/aging").param("asOf", "2026-03-31")))
                .andExpect(status().isBadRequest());

        mvc.perform(authed(get("/api/v1/reports/aging").param("type", "AR")))
                .andExpect(status().isBadRequest());

        mvc.perform(authed(get("/api/v1/reports/aging")
                        .param("type", "ar").param("asOf", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].partnerCode").value("CUS-V28-1"));
    }

    @Test
    @CaseId("TC-API-138")
    @DisplayName("from > to / 日期格式错误 / 缺必填参数均 400，不落到 500")
    void rangeAndDateFormat_validation() throws Exception {
        for (String path : List.of("/api/v1/reports/operating",
                "/api/v1/reports/inventory-turnover",
                "/api/v1/reports/customer-contribution")) {
            mvc.perform(authed(get(path)
                            .param("from", "2026-03-05").param("to", "2026-03-01")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message", Matchers.containsString("from")));
        }

        mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2026/03/01").param("to", "2026-03-31")))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/v1/reports/operating").param("to", "2026-03-31")))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/v1/reports/inventory-turnover")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("deadDays", "abc")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @CaseId("TC-API-139")
    @DisplayName("默认值：dimension=day、deadDays=30、limit=20；month 粒度返回月行")
    void defaults_areApplied() throws Exception {
        mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(31))
                .andExpect(jsonPath("$.data.items[0].period").value("2026-03-01"));

        mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("dimension", "month")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].period").value("2026-03"));

        mvc.perform(authed(get("/api/v1/reports/inventory-turnover")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].idleDays").value(26))
                .andExpect(jsonPath("$.data.items[0].stale").value(false));

        mvc.perform(authed(get("/api/v1/reports/customer-contribution")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    @CaseId("TC-API-140")
    @DisplayName("七个导出端点：200 + text/csv + attachment 文件名 + UTF-8 BOM")
    void exportEndpoints_returnCsv() throws Exception {
        List<String[]> calls = List.of(
                new String[]{"/api/v1/reports/purchase/export",
                        "from", "2026-03-01", "to", "2026-03-31"},
                new String[]{"/api/v1/reports/sales/export",
                        "from", "2026-03-01", "to", "2026-03-31"},
                new String[]{"/api/v1/reports/inventory-summary/export", "period", "2026-03"},
                new String[]{"/api/v1/reports/operating/export",
                        "from", "2026-03-01", "to", "2026-03-31"},
                new String[]{"/api/v1/reports/inventory-turnover/export",
                        "from", "2026-03-01", "to", "2026-03-31"},
                new String[]{"/api/v1/reports/customer-contribution/export",
                        "from", "2026-03-01", "to", "2026-03-31"},
                new String[]{"/api/v1/reports/aging/export", "type", "AR", "asOf", "2026-03-31"},
                new String[]{"/api/v1/reports/aging/export", "type", "AP", "asOf", "2026-03-31"});

        for (String[] call : calls) {
            MockHttpServletRequestBuilder request = authed(get(call[0]));
            for (int i = 1; i < call.length; i += 2) {
                request.param(call[i], call[i + 1]);
            }
            var response = mvc.perform(request).andExpect(status().isOk())
                    .andExpect(header().string("Content-Type",
                            Matchers.containsString("text/csv")))
                    .andReturn().getResponse();
            assertThat(response.getHeader("Content-Disposition"))
                    .as("%s 必须带附件文件名", call[0])
                    .contains("attachment").contains(".csv");
            byte[] body = response.getContentAsByteArray();
            assertThat(body).as("%s 必须带 UTF-8 BOM", call[0])
                    .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        }
    }

    @Test
    @CaseId("TC-API-141")
    @DisplayName("★ 同一 HTTP 参数下，经营报表 JSON 与 CSV 逐格一致（31 行 × 9 列）")
    void exportMatchesJsonForSameQuery() throws Exception {
        String body = mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode items = json.readTree(body).path("data").path("items");

        var response = mvc.perform(authed(get("/api/v1/reports/operating/export")
                        .param("from", "2026-03-01").param("to", "2026-03-31")))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        List<List<String>> csv = ReportExportIT.parseCsv(
                response.getContentAsString(StandardCharsets.UTF_8));

        assertThat(csv).hasSize(items.size() + 1);
        for (int i = 0; i < items.size(); i++) {
            JsonNode r = items.get(i);
            assertThat(csv.get(i + 1)).containsExactly(
                    r.path("period").asText(),
                    r.path("openingQty").asText(),
                    r.path("inboundQty").asText(),
                    r.path("outboundQty").asText(),
                    r.path("closingQty").asText(),
                    r.path("purchaseQty").asText(),
                    yuan(r.path("purchaseAmount").asLong()),
                    r.path("salesQty").asText(),
                    yuan(r.path("salesAmount").asLong()));
        }
    }

    @Test
    @CaseId("TC-API-142")
    @DisplayName("鉴权：未登录 401；有登录但无 rpt:read 的角色 403")
    void authorization() throws Exception {
        mvc.perform(get("/api/v1/reports/operating")
                        .param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports/purchase/export")
                        .param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isUnauthorized());

        data.user("rpt_v28_purchaser", "Admin1!", "PURCHASER", encoder);
        String purchaser = "Bearer " + authentication.login(
                "rpt_v28_purchaser", "Admin1!").token();
        mvc.perform(get("/api/v1/reports/operating")
                        .header("Authorization", purchaser)
                        .param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/aging/export")
                        .header("Authorization", purchaser)
                        .param("type", "AR").param("asOf", "2026-03-31"))
                .andExpect(status().isForbidden());
    }

    @Test
    @CaseId("TC-API-143")
    @DisplayName("★ 全部新报表 + 导出跑完后，单据/流水/余额/应收应付计数零变化")
    void reportsAndExports_areReadOnly() throws Exception {
        Map<String, Integer> before = counts();

        mvc.perform(authed(get("/api/v1/reports/operating")
                .param("from", "2026-03-01").param("to", "2026-03-31"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/reports/inventory-turnover")
                .param("from", "2026-03-01").param("to", "2026-03-31"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/reports/customer-contribution")
                .param("from", "2026-03-01").param("to", "2026-03-31"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/reports/aging")
                .param("type", "AR").param("asOf", "2026-03-31"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/reports/aging/export")
                .param("type", "AP").param("asOf", "2026-03-31"))).andExpect(status().isOk());

        assertThat(counts()).isEqualTo(before);
    }

    @Test
    @CaseId("TC-API-144")
    @DisplayName("空区间/无数据：四个报表 200，不 500；经营报表按分桶返回零行")
    void emptyData_isNotAnError() throws Exception {
        mvc.perform(authed(get("/api/v1/reports/operating")
                        .param("from", "2020-01-01").param("to", "2020-01-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].closingQty").value(0));
        mvc.perform(authed(get("/api/v1/reports/inventory-turnover")
                        .param("from", "2020-01-01").param("to", "2020-01-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(authed(get("/api/v1/reports/customer-contribution")
                        .param("from", "2020-01-01").param("to", "2020-01-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.summary.customerCount").value(0))
                .andExpect(jsonPath("$.data.summary.repurchaseRateBp").value(0));
        mvc.perform(authed(get("/api/v1/reports/aging")
                        .param("type", "AR").param("asOf", "2020-01-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.summary.totalBalance").value(0));
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", auth);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : List.of("purchase_orders", "sales_orders", "inventory_ledger",
                "inventory_balances", "customer_receivables", "supplier_payables")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    private static String yuan(long fen) {
        return java.math.BigDecimal.valueOf(fen, 2).toPlainString();
    }
}
