package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.erp.iam.application.AuthenticationService;
import com.erp.shared.tenant.TenantId;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * HTTP 契约测试 · 主数据三块扩展字段（V18 · B2）。
 *
 * <p>覆盖：信封/状态码/参数校验、库位容量守恒、跨租户 404 + 同租户正对照。
 * 这里的路径在 B1 合并前尚未写入 openapi，属于“实现先行、契约接棒后补”的预期状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 客户地址 / 仓库库位 / 商品规格属性")
class MdmFieldsApiIT extends PostgresTestBase {

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

    private String auth1;
    private String auth2;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("b2_admin_1", "Admin1!", "ADMIN", encoder);
        data.inTenant(TenantId.DEMO).user("b2_admin_2", "Admin2!", "ADMIN", encoder);
        auth1 = "Bearer " + authentication.login("b2_admin_1", "Admin1!").token();
        auth2 = "Bearer " + authentication.login("b2_admin_2", "Admin2!").token();
    }

    // ─────────────────────────── TC-API-155 ~ 157 客户地址 ───────────────────────────

    @Test
    @CaseId("TC-API-155")
    @DisplayName("地址：建（首条默认）→ 列表 → 建第二条 → 切换默认，响应包络齐全")
    void address_crudAndDefaultSwitch() throws Exception {
        long customer = createCustomer(auth1, "API-ADDR-1");

        MvcResult firstResult = httpPost("/api/v1/customers/" + customer + "/addresses",
                auth1, addressBody("HOME", "张三", true, null));
        assertStatus(firstResult, 201);
        JsonNode first = dataOf(firstResult);
        assertThat(first.path("addressKey").asText()).isEqualTo("HOME");
        assertThat(first.path("recipientName").asText()).isEqualTo("张三");
        assertThat(first.path("province").asText()).isEqualTo("浙江省");
        assertThat(first.path("detailAddress").asText()).isEqualTo("文三路 1 号");
        assertThat(first.path("isDefault").asBoolean()).isTrue();
        assertThat(first.path("enabled").asBoolean()).isTrue();
        long firstId = first.path("id").asLong();

        MvcResult secondResult = httpPost("/api/v1/customers/" + customer + "/addresses",
                auth1, addressBody("OFFICE", "李四", false, null));
        assertStatus(secondResult, 201);
        JsonNode second = dataOf(secondResult);
        assertThat(second.path("isDefault").asBoolean()).isFalse();
        long secondId = second.path("id").asLong();

        MvcResult switched = httpAction(
                "/api/v1/customers/" + customer + "/addresses/" + secondId + "/default", auth1);
        assertStatus(switched, 200);
        assertThat(dataOf(switched).path("isDefault").asBoolean()).isTrue();

        MvcResult list = httpGet("/api/v1/customers/" + customer + "/addresses", auth1);
        assertStatus(list, 200);
        JsonNode items = dataOf(list);
        assertThat(items).hasSize(2);
        assertThat(defaultIds(items)).containsExactly(secondId);
        assertThat(items.get(0).path("id").asLong()).isIn(firstId, secondId);
    }

    @Test
    @CaseId("TC-API-156")
    @DisplayName("地址校验：缺收件人 400；首条自动默认；停用默认自动提升；停用地址不能设默认")
    void address_validationAndAutoPromotion() throws Exception {
        long customer = createCustomer(auth1, "API-ADDR-2");

        Map<String, Object> missingRecipient = addressBody("NO-RECIPIENT", "张三", true, null);
        missingRecipient.remove("recipientName");
        assertError(httpPost("/api/v1/customers/" + customer + "/addresses", auth1,
                missingRecipient), 400, "SYS_VALIDATION_ERROR");

        MvcResult firstResult = httpPost("/api/v1/customers/" + customer + "/addresses",
                auth1, addressBody("A1", "张三", false, null));
        assertStatus(firstResult, 201);
        JsonNode first = dataOf(firstResult);
        assertThat(first.path("isDefault").asBoolean())
                .as("首条启用地址即使未显式指定也自动成为默认").isTrue();
        long firstId = first.path("id").asLong();

        MvcResult secondResult = httpPost("/api/v1/customers/" + customer + "/addresses",
                auth1, addressBody("A2", "李四", false, null));
        long secondId = dataOf(secondResult).path("id").asLong();

        assertStatus(httpAction("/api/v1/customers/" + customer + "/addresses/"
                + firstId + "/disable", auth1), 200);

        JsonNode list = dataOf(httpGet("/api/v1/customers/" + customer + "/addresses", auth1));
        assertThat(defaultIds(list)).as("停用默认后自动提升下一条启用地址")
                .containsExactly(secondId);

        assertError(httpPost("/api/v1/customers/" + customer + "/addresses", auth1,
                addressBody("A3", "王五", true, false)), 400, "SYS_VALIDATION_ERROR");
    }

    @Test
    @CaseId("TC-API-157")
    @DisplayName("地址：重复键 409；地址不存在 404；客户不存在 404")
    void address_conflictAndNotFound() throws Exception {
        long customer = createCustomer(auth1, "API-ADDR-3");
        assertStatus(httpPost("/api/v1/customers/" + customer + "/addresses", auth1,
                addressBody("HOME", "张三", true, null)), 201);

        assertError(httpPost("/api/v1/customers/" + customer + "/addresses", auth1,
                addressBody("HOME", "李四", false, null)), 409, "MDM_ADDRESS_DUPLICATED");

        assertError(httpPut("/api/v1/customers/" + customer + "/addresses/999999", auth1,
                updateBody("张三")), 404, "MDM_ADDRESS_NOT_FOUND");

        assertError(httpPost("/api/v1/customers/999999/addresses", auth1,
                addressBody("X", "张三", false, null)), 404, "SYS_NOT_FOUND");
    }

    // ─────────────────────────── TC-API-158 ~ 160 仓库库位 ───────────────────────────

    @Test
    @CaseId("TC-API-158")
    @DisplayName("库位：建 40+60，仓库容量 100，列表与核对端点守恒（差 0）")
    void location_conservationEndpoint() throws Exception {
        long warehouse = createWarehouse(auth1, "API-WH-1");
        assertStatus(httpPut("/api/v1/warehouses/" + warehouse + "/capacity", auth1,
                Map.of("capacityQty", 100)), 200);

        assertStatus(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L40", "40 区", 40, true)), 201);
        assertStatus(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L60", "60 区", 60, true)), 201);

        JsonNode list = dataOf(httpGet(
                "/api/v1/warehouses/" + warehouse + "/locations", auth1));
        assertThat(list).hasSize(2);

        JsonNode reconcile = dataOf(httpGet(
                "/api/v1/warehouses/" + warehouse + "/locations/reconcile", auth1));
        assertThat(reconcile.path("warehouseCapacityQty").asLong()).isEqualTo(100);
        assertThat(reconcile.path("locationCapacityQty").asLong()).isEqualTo(100);
        assertThat(reconcile.path("difference").asLong()).isZero();
        assertThat(reconcile.path("enabledLocationCount").asInt()).isEqualTo(2);
        assertThat(reconcile.path("totalLocationCount").asInt()).isEqualTo(2);
        assertThat(reconcile.path("consistent").asBoolean()).isTrue();

        JsonNode warehouseDetail = dataOf(httpGet("/api/v1/warehouses/" + warehouse, auth1));
        assertThat(warehouseDetail.path("capacityQty").asLong()).isEqualTo(100);
    }

    @Test
    @CaseId("TC-API-159")
    @DisplayName("库位边界：超容量 409、编码重复 409、收缩仓库容量低于已分配 409")
    void location_capacityAndDuplicate() throws Exception {
        long warehouse = createWarehouse(auth1, "API-WH-2");
        assertStatus(httpPut("/api/v1/warehouses/" + warehouse + "/capacity", auth1,
                Map.of("capacityQty", 100)), 200);
        assertStatus(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L40", "40 区", 40, true)), 201);
        MvcResult l60 = httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L60", "60 区", 60, true));
        assertStatus(l60, 201);
        long locationId = dataOf(l60).path("id").asLong();

        assertError(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L1", "超额 1", 1, true)), 409,
                "MDM_LOCATION_CAPACITY_EXCEEDED");

        assertError(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L40", "重复码（停用不占容量）", 1, false)), 409,
                "MDM_LOCATION_DUPLICATED");

        assertError(httpPut("/api/v1/warehouses/" + warehouse + "/locations/" + locationId,
                auth1, Map.of("name", "60 区", "capacityQty", 61)), 409,
                "MDM_LOCATION_CAPACITY_EXCEEDED");

        assertError(httpPut("/api/v1/warehouses/" + warehouse + "/capacity", auth1,
                Map.of("capacityQty", 50)), 409, "MDM_LOCATION_CAPACITY_EXCEEDED");
    }

    @Test
    @CaseId("TC-API-160")
    @DisplayName("库位：仓库/库位不存在 404；停用后核对报差额，启用恢复一致")
    void location_disableAndNotFound() throws Exception {
        assertError(httpGet("/api/v1/warehouses/999999/locations", auth1),
                404, "MDM_WAREHOUSE_NOT_FOUND");

        long warehouse = createWarehouse(auth1, "API-WH-3");
        assertStatus(httpPut("/api/v1/warehouses/" + warehouse + "/capacity", auth1,
                Map.of("capacityQty", 100)), 200);
        assertStatus(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L40", "40 区", 40, true)), 201);
        MvcResult l60 = httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth1,
                locationBody("L60", "60 区", 60, true));
        long locationId = dataOf(l60).path("id").asLong();

        assertError(httpPut("/api/v1/warehouses/" + warehouse + "/locations/999999", auth1,
                Map.of("name", "x", "capacityQty", 1)), 404, "MDM_LOCATION_NOT_FOUND");

        assertStatus(httpAction("/api/v1/warehouses/" + warehouse + "/locations/"
                + locationId + "/disable", auth1), 200);
        JsonNode gap = dataOf(httpGet(
                "/api/v1/warehouses/" + warehouse + "/locations/reconcile", auth1));
        assertThat(gap.path("locationCapacityQty").asLong()).isEqualTo(40);
        assertThat(gap.path("difference").asLong()).isEqualTo(60);
        assertThat(gap.path("enabledLocationCount").asInt()).isEqualTo(1);
        assertThat(gap.path("consistent").asBoolean()).isFalse();

        assertStatus(httpAction("/api/v1/warehouses/" + warehouse + "/locations/"
                + locationId + "/enable", auth1), 200);
        JsonNode balanced = dataOf(httpGet(
                "/api/v1/warehouses/" + warehouse + "/locations/reconcile", auth1));
        assertThat(balanced.path("consistent").asBoolean()).isTrue();
        assertThat(balanced.path("difference").asLong()).isZero();
    }

    // ─────────────────────────── TC-API-161 ~ 162 商品规格属性 ───────────────────────────

    @Test
    @CaseId("TC-API-161")
    @DisplayName("属性：PUT 全量替换 → GET 一致 → PUT 空数组清空 → 重复 PUT 幂等")
    void productAttributes_replaceAndClear() throws Exception {
        long product = createProduct(auth1, "API-ATTR-1");

        MvcResult replaced = httpPut("/api/v1/products/" + product + "/attributes", auth1,
                attributesBody(List.of(
                        Map.of("key", "面料", "value", "纯棉"),
                        Map.of("key", "版型", "value", "直筒"))));
        assertStatus(replaced, 200);
        assertThat(dataOf(replaced).path("items")).hasSize(2);

        JsonNode fetched = dataOf(httpGet("/api/v1/products/" + product + "/attributes", auth1));
        assertThat(fetched.path("items")).hasSize(2);
        assertThat(attributeValue(fetched.path("items"), "面料")).isEqualTo("纯棉");

        assertStatus(httpPut("/api/v1/products/" + product + "/attributes", auth1,
                attributesBody(List.of())), 200);
        assertThat(dataOf(httpGet("/api/v1/products/" + product + "/attributes", auth1))
                .path("items")).isEmpty();

        for (int i = 0; i < 2; i++) {
            MvcResult result = httpPut("/api/v1/products/" + product + "/attributes", auth1,
                    attributesBody(List.of(Map.of("key", "洗涤", "value", "手洗"))));
            assertStatus(result, 200);
            assertThat(dataOf(result).path("items")).hasSize(1);
        }
    }

    @Test
    @CaseId("TC-API-162")
    @DisplayName("属性校验：重复键 400、空键 400、商品不存在 404")
    void productAttributes_validationAndNotFound() throws Exception {
        long product = createProduct(auth1, "API-ATTR-2");

        assertError(httpPut("/api/v1/products/" + product + "/attributes", auth1,
                attributesBody(List.of(
                        Map.of("key", "面料", "value", "纯棉"),
                        Map.of("key", "面料", "value", "亚麻")))), 400,
                "MDM_PRODUCT_ATTRIBUTE_INVALID");

        List<Map<String, String>> withBlank = new ArrayList<>();
        withBlank.add(new LinkedHashMap<>(Map.of("key", "", "value", "纯棉")));
        assertError(httpPut("/api/v1/products/" + product + "/attributes", auth1,
                attributesBody(withBlank)), 400, "SYS_VALIDATION_ERROR");

        assertError(httpGet("/api/v1/products/999999/attributes", auth1),
                404, "SYS_NOT_FOUND");
        assertError(httpPut("/api/v1/products/999999/attributes", auth1,
                attributesBody(List.of(Map.of("key", "面料", "value", "纯棉")))),
                404, "SYS_NOT_FOUND");
    }

    // ─────────────────────────── TC-API-163 ~ 164 跨租户 ───────────────────────────

    @Test
    @CaseId("TC-API-163")
    @DisplayName("★ 租户 2 访问租户 1 的地址/库位/属性一律 404；租户 1 自身 200")
    void crossTenant_tenant2CannotTouchTenant1() throws Exception {
        long customer = createCustomer(auth1, "API-T1-CUS");
        long address = dataOf(httpPost("/api/v1/customers/" + customer + "/addresses", auth1,
                addressBody("HOME", "张三", true, null))).path("id").asLong();
        long warehouse = createWarehouse(auth1, "API-T1-WH");
        assertStatus(httpPut("/api/v1/warehouses/" + warehouse + "/capacity", auth1,
                Map.of("capacityQty", 100)), 200);
        long location = dataOf(httpPost("/api/v1/warehouses/" + warehouse + "/locations",
                auth1, locationBody("L1", "库位", 50, true))).path("id").asLong();
        long product = createProduct(auth1, "API-T1-P");
        assertStatus(httpPut("/api/v1/products/" + product + "/attributes", auth1,
                attributesBody(List.of(Map.of("key", "面料", "value", "纯棉")))), 200);

        assertError(httpGet("/api/v1/customers/" + customer + "/addresses", auth2),
                404, "SYS_NOT_FOUND");
        assertError(httpPost("/api/v1/customers/" + customer + "/addresses", auth2,
                addressBody("T2", "冒名", false, null)), 404, "SYS_NOT_FOUND");
        assertError(httpPut("/api/v1/customers/" + customer + "/addresses/" + address,
                auth2, updateBody("冒名")), 404, "SYS_NOT_FOUND");
        assertError(httpAction("/api/v1/customers/" + customer + "/addresses/"
                + address + "/default", auth2), 404, "SYS_NOT_FOUND");
        assertError(httpAction("/api/v1/customers/" + customer + "/addresses/"
                + address + "/disable", auth2), 404, "SYS_NOT_FOUND");

        assertError(httpGet("/api/v1/warehouses/" + warehouse + "/locations", auth2),
                404, "MDM_WAREHOUSE_NOT_FOUND");
        assertError(httpPost("/api/v1/warehouses/" + warehouse + "/locations", auth2,
                locationBody("T2L", "冒名库位", 1, true)), 404, "MDM_WAREHOUSE_NOT_FOUND");
        assertError(httpPut("/api/v1/warehouses/" + warehouse + "/locations/" + location,
                auth2, Map.of("name", "冒名", "capacityQty", 1)), 404, "MDM_WAREHOUSE_NOT_FOUND");
        assertError(httpGet("/api/v1/warehouses/" + warehouse + "/locations/reconcile", auth2),
                404, "MDM_WAREHOUSE_NOT_FOUND");
        assertError(httpPut("/api/v1/warehouses/" + warehouse + "/capacity", auth2,
                Map.of("capacityQty", 1)), 404, "MDM_WAREHOUSE_NOT_FOUND");

        assertError(httpGet("/api/v1/products/" + product + "/attributes", auth2),
                404, "SYS_NOT_FOUND");
        assertError(httpPut("/api/v1/products/" + product + "/attributes", auth2,
                attributesBody(List.of(Map.of("key", "面料", "value", "冒名")))),
                404, "SYS_NOT_FOUND");

        assertStatus(httpGet("/api/v1/customers/" + customer + "/addresses", auth1), 200);
        assertStatus(httpGet("/api/v1/warehouses/" + warehouse + "/locations", auth1), 200);
        assertStatus(httpGet("/api/v1/products/" + product + "/attributes", auth1), 200);
    }

    @Test
    @CaseId("TC-API-164")
    @DisplayName("★ 反向跨租户：租户 1 访问租户 2 地址/库位/属性同样 404，租户 2 自身 200")
    void crossTenant_tenant1CannotTouchTenant2() throws Exception {
        long customer = createCustomer(auth2, "API-T2-CUS");
        long address = dataOf(httpPost("/api/v1/customers/" + customer + "/addresses", auth2,
                addressBody("HOME", "租户二客户", true, null))).path("id").asLong();
        long warehouse = createWarehouse(auth2, "API-T2-WH");
        long location = dataOf(httpPost("/api/v1/warehouses/" + warehouse + "/locations",
                auth2, locationBody("L1", "库位", 50, true))).path("id").asLong();
        long product = createProduct(auth2, "API-T2-P");
        assertStatus(httpPut("/api/v1/products/" + product + "/attributes", auth2,
                attributesBody(List.of(Map.of("key", "面料", "value", "亚麻")))), 200);

        assertError(httpGet("/api/v1/customers/" + customer + "/addresses", auth1),
                404, "SYS_NOT_FOUND");
        assertError(httpPut("/api/v1/customers/" + customer + "/addresses/" + address,
                auth1, updateBody("冒名")), 404, "SYS_NOT_FOUND");
        assertError(httpGet("/api/v1/warehouses/" + warehouse + "/locations", auth1),
                404, "MDM_WAREHOUSE_NOT_FOUND");
        assertError(httpPut("/api/v1/warehouses/" + warehouse + "/locations/" + location,
                auth1, Map.of("name", "冒名", "capacityQty", 1)), 404, "MDM_WAREHOUSE_NOT_FOUND");
        assertError(httpGet("/api/v1/products/" + product + "/attributes", auth1),
                404, "SYS_NOT_FOUND");

        assertStatus(httpGet("/api/v1/customers/" + customer + "/addresses", auth2), 200);
        assertStatus(httpGet("/api/v1/warehouses/" + warehouse + "/locations", auth2), 200);
        assertStatus(httpGet("/api/v1/products/" + product + "/attributes", auth2), 200);
    }

    // ─────────────────────────── HTTP 辅助 ───────────────────────────

    private MvcResult httpPost(String path, String token, Object body) throws Exception {
        return mvc.perform(post(path)
                        .header("Authorization", token)
                        .header("X-Request-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult httpPut(String path, String token, Object body) throws Exception {
        return mvc.perform(put(path)
                        .header("Authorization", token)
                        .header("X-Request-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult httpGet(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", token)).andReturn();
    }

    /** 无请求体的纯状态 POST（default / disable / enable）。 */
    private MvcResult httpAction(String path, String token) throws Exception {
        return mvc.perform(post(path).header("Authorization", token)).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        JsonNode root = json.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.path("code").asText()).isEqualTo("OK");
        return root.path("data");
    }

    private void assertStatus(MvcResult result, int status) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        JsonNode root = json.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.path("code").asText()).isEqualTo(code);
    }

    // ─────────────────────────── 造数与请求体辅助 ───────────────────────────

    private long createCustomer(String token, String code) throws Exception {
        MvcResult result = httpPost("/api/v1/customers", token,
                Map.of("code", code, "name", "客户-" + code));
        assertStatus(result, 201);
        return dataOf(result).path("id").asLong();
    }

    private long createWarehouse(String token, String code) throws Exception {
        MvcResult result = httpPost("/api/v1/warehouses", token,
                Map.of("code", code, "name", "仓库-" + code));
        assertStatus(result, 201);
        return dataOf(result).path("id").asLong();
    }

    private long createProduct(String token, String code) throws Exception {
        MvcResult result = httpPost("/api/v1/products", token,
                Map.of("code", code, "name", "商品-" + code));
        assertStatus(result, 201);
        return dataOf(result).path("id").asLong();
    }

    private Map<String, Object> addressBody(String key, String recipient,
                                             boolean isDefault, Boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressKey", key);
        body.put("recipientName", recipient);
        body.put("phone", "13800000000");
        body.put("province", "浙江省");
        body.put("city", "杭州市");
        body.put("district", "西湖区");
        body.put("detailAddress", "文三路 1 号");
        body.put("isDefault", isDefault);
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        return body;
    }

    private Map<String, Object> updateBody(String recipient) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipientName", recipient);
        body.put("phone", "13800000000");
        body.put("province", "浙江省");
        body.put("city", "杭州市");
        body.put("district", "西湖区");
        body.put("detailAddress", "文三路 1 号");
        return body;
    }

    private Map<String, Object> locationBody(String code, String name, long capacity,
                                             boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("locationCode", code);
        body.put("name", name);
        body.put("capacityQty", capacity);
        body.put("enabled", enabled);
        return body;
    }

    private Map<String, Object> attributesBody(List<Map<String, String>> attributes) {
        return Map.of("attributes", attributes);
    }

    private static List<Long> defaultIds(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.path("isDefault").asBoolean()) {
                ids.add(item.path("id").asLong());
            }
        }
        return ids;
    }

    private static String attributeValue(JsonNode items, String key) {
        for (JsonNode item : items) {
            if (key.equals(item.path("key").asText())) {
                return item.path("value").asText();
            }
        }
        return null;
    }
}
