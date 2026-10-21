package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
 * HTTP 契约测试（MDM 部分）—— 走真实的 Spring MVC 栈，验证 <b>接口层</b>行为。
 *
 * <p><b>为什么单元/服务层测试不够</b>：
 * 参数校验、错误码到 HTTP 状态的映射、响应包裹结构，都发生在 Controller
 * 与 {@code GlobalExceptionHandler} 这一层。服务层测试<b>绕过</b>了这些代码，
 * 于是"接口返回 400 还是 500"这类契约问题完全测不到。
 *
 * <p>这也让覆盖率从"服务层很高、接口层几乎为零"变成真实反映。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 主数据 CRUD")
class MasterDataApiIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private String auth;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("admin1", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("admin1", "Admin1!").token();
    }

    // ─────────────────────────── 商品 ───────────────────────────

    @Test
    @CaseId("TC-API-001")
    @DisplayName("商品：建 → 查 → 改 → 停用 全链路，响应包络符合契约")
    void product_crudFlow() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/products")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "P-API-01", "name", "接口测试商品",
                                "category", "上装", "season", "2025夏"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.code").value("P-API-01"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn();

        long id = json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mvc.perform(get("/api/v1/products/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("接口测试商品"));

        mvc.perform(put("/api/v1/products/" + id)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "改名后的商品", "category", "下装", "enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改名后的商品"))
                .andExpect(jsonPath("$.data.category").value("下装"));

        mvc.perform(post("/api/v1/products/" + id + "/disable").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    @CaseId("TC-API-002")
    @DisplayName("商品：编码重复 → 409 MDM_CODE_DUPLICATED；不存在 → 404 SYS_NOT_FOUND")
    void product_conflictAndNotFound() throws Exception {
        String body = json.writeValueAsString(Map.of("code", "P-API-DUP", "name", "重复商品"));
        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MDM_CODE_DUPLICATED"));

        mvc.perform(get("/api/v1/products/999999").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYS_NOT_FOUND"));
    }

    @Test
    @CaseId("TC-API-003")
    @DisplayName("★ 参数校验：编码过短 / 名称为空 → 400 SYS_VALIDATION_ERROR（不是 500）")
    void product_validationErrors() throws Exception {
        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "X", "name", "名字"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "P-OK-1", "name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-API-004")
    @DisplayName("商品列表分页：items/total/page/size 齐全，size 越界被钳制到 200")
    void product_paging() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/v1/products").header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    Map.of("code", "P-PG-" + i, "name", "分页商品" + i))))
                    .andExpect(status().isCreated());
        }

        mvc.perform(get("/api/v1/products").param("page", "1").param("size", "2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2));

        mvc.perform(get("/api/v1/products").param("size", "9999").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(200))
                .andExpect(jsonPath("$.data.page").value(1));
    }

    // ─────────────────────────── SKU ───────────────────────────

    @Test
    @CaseId("TC-API-005")
    @DisplayName("SKU：建单条 → 矩阵生成 → 重复矩阵跳过 → 自冲突 409")
    void sku_createAndMatrix() throws Exception {
        long productId = createProduct("P-SKU-API");

        mvc.perform(post("/api/v1/skus").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "productId", productId, "color", "白色", "size", "L",
                                "barcode", "6901111111111", "retailPriceAmount", 12900))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.color").value("白色"))
                .andExpect(jsonPath("$.data.retailPriceAmount").value(12900));

        String matrix = json.writeValueAsString(Map.of(
                "productId", productId,
                "colors", List.of("白色", "黑色"),
                "sizes", List.of("S", "M"),
                "retailPriceAmount", 9900));
        mvc.perform(post("/api/v1/skus/matrix").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(matrix))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdCount").value(4))
                .andExpect(jsonPath("$.data.skipped").value(0));

        // 再跑一次：全部已存在 → 跳过
        mvc.perform(post("/api/v1/skus/matrix").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(matrix))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.skipped").value(4));

        // 自冲突 → 409
        mvc.perform(post("/api/v1/skus/matrix").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "productId", productId,
                                "colors", List.of("红色", "红色"),
                                "sizes", List.of("S")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MDM_SKU_DUPLICATED"));
    }

    @Test
    @CaseId("TC-API-006")
    @DisplayName("SKU 列表按商品过滤；不存在的商品查 SKU → 404")
    void sku_listAndNotFound() throws Exception {
        long productId = createProduct("P-SKU-LIST");
        mvc.perform(post("/api/v1/skus").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "productId", productId, "color", "灰色", "size", "XL"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/skus").param("productId", String.valueOf(productId))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].color").value("灰色"));

        mvc.perform(get("/api/v1/skus").param("productId", "999999").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mvc.perform(get("/api/v1/skus/999999").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MDM_SKU_NOT_FOUND"));
    }

    @Test
    @CaseId("TC-API-011")
    @DisplayName("★ SKU 分页：size 控制条数，skuSize 过滤尺码，两参数互不吞并")
    void sku_pagingAndSizeFilter() throws Exception {
        long productId = createProduct("P-SKU-PAGE");
        mvc.perform(post("/api/v1/skus/matrix").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "productId", productId,
                                "colors", List.of("白色", "黑色"),
                                "sizes", List.of("S", "M", "L")))))
                .andExpect(status().isCreated());

        // size=2 必须真的只返回 2 条（修复前会被静默忽略，永远返回 20 条）
        mvc.perform(get("/api/v1/skus").param("productId", String.valueOf(productId))
                        .param("page", "1").param("size", "2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.size").value(2));

        // skuSize 过滤尺码，不与分页参数冲突
        mvc.perform(get("/api/v1/skus").param("productId", String.valueOf(productId))
                        .param("skuSize", "L").param("size", "20")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].size").value("L"))
                .andExpect(jsonPath("$.data.items[1].size").value("L"));
    }

    // ─────────────────────────── 供应商 / 客户 / 仓库 ───────────────────────────

    @Test
    @CaseId("TC-API-007")
    @DisplayName("供应商：建 → 重复 409 → 改 → 停用")
    void supplier_crud() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/suppliers").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "SUP-API", "name", "接口供应商",
                                "contactName", "王五", "contactPhone", "13700000000"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contactName").value("王五"))
                .andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();

        mvc.perform(post("/api/v1/suppliers").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "SUP-API", "name", "另一个"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MDM_CODE_DUPLICATED"));

        mvc.perform(put("/api/v1/suppliers/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "改名供应商", "enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("改名供应商"));

        mvc.perform(post("/api/v1/suppliers/" + id + "/disable").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mvc.perform(get("/api/v1/suppliers").param("keyword", "改名").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @CaseId("TC-API-008")
    @DisplayName("★ 客户折扣率：越界 400（契约层校验），合法 9 折成功")
    void customer_discountValidation() throws Exception {
        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "CUS-API", "name", "接口客户", "discountRate", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "CUS-API", "name", "接口客户", "discountRate", 10001))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "CUS-API", "name", "接口客户",
                                "discountRate", 9000, "level", "VIP"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.discountRate").value(9000))
                .andExpect(jsonPath("$.data.level").value("VIP"));
    }

    @Test
    @CaseId("TC-API-009")
    @DisplayName("仓库：列表返回数组（非分页）；onlyEnabled 过滤生效")
    void warehouse_listAndFilter() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/warehouses").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("code", "WH-API-1", "name", "接口仓一"))))
                .andExpect(status().isCreated())
                .andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(post("/api/v1/warehouses").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("code", "WH-API-2", "name", "接口仓二"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/warehouses").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mvc.perform(post("/api/v1/warehouses/" + id + "/disable").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/warehouses").param("onlyEnabled", "true").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("WH-API-2"));

        mvc.perform(get("/api/v1/warehouses/999999").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MDM_WAREHOUSE_NOT_FOUND"));
    }

    @Test
    @CaseId("TC-API-010")
    @DisplayName("缺少 X-Request-Id 的写接口 → 400（契约要求写操作必须带幂等键）")
    void startWithoutRequestId_thenRejected() throws Exception {
        mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":1,"warehouseId":1,
                                 "lines":[{"skuId":1,"qty":1,"unitPriceAmount":100}]}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(auth).isNotBlank();
    }

    private long createProduct(String code) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", code, "name", "商品-" + code))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
}
