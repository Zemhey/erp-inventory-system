package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.inv.application.InventoryApplicationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 库存接口契约测试 —— 重点是 {@code GET /inventory/ledger}。
 *
 * <p>M3-S1 实测发现该路径在契约里、在矩阵里标 🟢，但 Controller 没有映射，
 * 请求直接 500。本测试与 {@code ApiContractMappingIT} 一起守住两类问题：
 * 前者证明接口行为正确，后者证明接口真的存在。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 库存流水")
class InventoryApiIT extends PostgresTestBase {

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

    private String auth;
    private long warehouseId;
    private long skuId;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("inv_admin", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("inv_admin", "Admin1!").token();

        warehouseId = data.warehouse("WH-INV-API");
        long productId = data.product("P-INV-API", "流水接口商品");
        skuId = data.sku(productId, "白色", "L");

        inventory.receiveIn(skuId, warehouseId, "B1", null, null,
                10, "PURCHASE_IN", "PO-L-1", null, 1000L, "req-ledger-1");
        inventory.receiveIn(skuId, warehouseId, "B1", null, null,
                5, "PURCHASE_IN", "PO-L-2", null, 1000L, "req-ledger-2");
        inventory.receiveIn(skuId, warehouseId, "B2", null, null,
                3, "PURCHASE_IN", "PO-L-1", null, 2000L, "req-ledger-3");
    }

    @Test
    @CaseId("TC-API-012")
    @DisplayName("★ 库存流水：分页参数生效、按来源单据反查、响应字段符合契约")
    void ledger_pagingAndBizRefFilter() throws Exception {
        mvc.perform(get("/api/v1/inventory/ledger")
                        .param("skuId", String.valueOf(skuId))
                        .param("page", "1").param("size", "2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                // 按 occurred_at DESC, id DESC：最后写入的 B2 必须在第一行
                .andExpect(jsonPath("$.data.items[0].batchNo").value("B2"))
                .andExpect(jsonPath("$.data.items[0].changeQty").value(3))
                .andExpect(jsonPath("$.data.items[0].bizType").value("PURCHASE_IN"))
                .andExpect(jsonPath("$.data.items[0].bizRefNo").value("PO-L-1"))
                .andExpect(jsonPath("$.data.items[0].unitCostAmount").value(2000))
                .andExpect(jsonPath("$.data.items[0].occurredAt").isNotEmpty());

        mvc.perform(get("/api/v1/inventory/ledger")
                        .param("bizRefNo", "PO-L-1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].bizRefNo").value("PO-L-1"))
                .andExpect(jsonPath("$.data.items[1].bizRefNo").value("PO-L-1"));

        mvc.perform(get("/api/v1/inventory/ledger")
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("from", "2000-01-01T00:00:00Z")
                        .param("to", "2999-12-31T23:59:59Z")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));

        mvc.perform(get("/api/v1/inventory/ledger")
                        .param("to", "2000-01-01T00:00:00Z")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @CaseId("TC-API-013")
    @DisplayName("库存流水：未登录 401（权限守卫生效，不是 500）")
    void ledger_withoutToken_thenUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/inventory/ledger"))
                .andExpect(status().isUnauthorized());
    }
}
