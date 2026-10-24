package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.erp.iam.application.AuthenticationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.repository.CustomerRepository;
import com.erp.mdm.repository.ProductRepository;
import com.erp.mdm.repository.SkuRepository;
import com.erp.mdm.repository.SupplierRepository;
import com.erp.mdm.repository.WarehouseRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * MDM 写端点的幂等键（V2-C1）—— 8 个端点真实消费 {@code X-Request-Id} 的行为级证明。
 *
 * <p><b>这一类用例存在的唯一理由</b>：契约声明了幂等键，而"声明"本身不等于"生效"。
 * 一个收下参数却什么都不做的 Controller 也能让"接口存在 X-Request-Id"这种文本检查变绿。
 * 所以这里的每一条断言都落在<b>可观察后果</b>上：表里多几行、单号序列进没进位、
 * 审计多没多一条、重放回来的 id 与内容是否与首次一致。
 *
 * <p><b>为什么守卫必须能在"进程重启后"依然成立</b>（{@link #dbUniqueConstraintRejectsSameKey}）：
 * 幂等键一旦由进程内 Map/Caffeine 承担，重启即失效 —— 客户端重试撞上重启窗口就会建出两行，
 * 而这件事在开发机上永远看不到（没人会在测试中途 kill -9）。
 * 本类因此把"数据库唯一索引会拦住第二个同键 INSERT"单独列成用例，
 * 并且绕过应用层直接调仓储：证明拦住它的是<b>索引</b>，不是 Java 代码里的 if。
 *
 * <p><b>重放不该留下第二条审计</b>（{@link #replayDoesNotDuplicateAuditRows}）：
 * {@code AuditAspect} 按 HTTP 写请求留痕，一次成功重放如果照常写流水，
 * "谁在什么时候改了什么"就会被重试次数淹没。业务侧一行都没写的时候，审计也不该有第二条 ——
 * 重放仍写 INFO 日志（可观测性没有黑洞），只是不进 {@code audit_logs}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · MDM 幂等键（V2-C1）")
class MdmIdempotencyIT extends PostgresTestBase {

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
    @Autowired
    private WarehouseApplicationService warehousesService;
    @Autowired
    private WarehouseRepository warehouses;
    @Autowired
    private SupplierRepository suppliers;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private ProductRepository products;
    @Autowired
    private SkuRepository skus;
    @Autowired
    private DocumentNumberGenerator numbers;

    private String auth;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("admin1", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("admin1", "Admin1!").token();
    }

    // ─────────────────────────── 五个创建型端点的重放 ───────────────────────────

    @Test
    @CaseId("TC-API-085")
    @DisplayName("POST /warehouses：同键重放返回同一 id，表里只有一行")
    void warehouseCreate_replayReturnsSameRow() throws Exception {
        String body = write("code", "W-085", "name", "华东仓");
        MvcResult first = sendPost("/api/v1/warehouses", "req-api-085", body)
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = sendPost("/api/v1/warehouses", "req-api-085", body)
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(countByCreateKey("warehouses", "req-api-085")).isEqualTo(1L);
        assertThat(count("warehouses")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-API-086")
    @DisplayName("POST /suppliers：同键重放返回同一 id，表里只有一行")
    void supplierCreate_replayReturnsSameRow() throws Exception {
        String body = write("code", "S-086", "name", "测试供应商");
        MvcResult first = sendPost("/api/v1/suppliers", "req-api-086", body)
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = sendPost("/api/v1/suppliers", "req-api-086", body)
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(countByCreateKey("suppliers", "req-api-086")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-API-087")
    @DisplayName("POST /customers：同键重放返回同一 id，表里只有一行")
    void customerCreate_replayReturnsSameRow() throws Exception {
        String body = write("code", "C-087", "name", "测试客户", "phone", "13800000000");
        MvcResult first = sendPost("/api/v1/customers", "req-api-087", body)
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = sendPost("/api/v1/customers", "req-api-087", body)
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(countByCreateKey("customers", "req-api-087")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-API-088")
    @DisplayName("POST /products：同键重放返回同一 id，表里只有一行")
    void productCreate_replayReturnsSameRow() throws Exception {
        String body = write("code", "P-088", "name", "幂等测试商品", "category", "上装");
        MvcResult first = sendPost("/api/v1/products", "req-api-088", body)
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = sendPost("/api/v1/products", "req-api-088", body)
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(countByCreateKey("products", "req-api-088")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-API-089")
    @DisplayName("POST /skus：同键重放返回同一 id，且不撞变体唯一约束")
    void skuCreate_replayReturnsSameRow() throws Exception {
        long productId = idOf(sendPost("/api/v1/products", "req-api-089-p",
                write("code", "P-089", "name", "SKU 母商品")).andExpect(status().isCreated()).andReturn());
        String body = write("productId", productId, "color", "红色", "size", "M",
                "retailPriceAmount", 19900);

        MvcResult first = sendPost("/api/v1/skus", "req-api-089", body)
                .andExpect(status().isCreated()).andReturn();
        // ★ 没有幂等键时，这一次会被 uq_sku_variant 拦成 409 MDM_SKU_DUPLICATED；
        //   有了键，它必须先被识别成"同一个请求"，而不是"第二个重复变体"
        MvcResult replay = sendPost("/api/v1/skus", "req-api-089", body)
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(countByCreateKey("skus", "req-api-089")).isEqualTo(1L);
        assertThat(count("skus")).isEqualTo(1L);
    }

    // ─────────────────────────── 三个纯开关的重放 ───────────────────────────

    @Test
    @CaseId("TC-API-090")
    @DisplayName("POST /warehouses/{id}/disable：同键重放结果相同、零写入、零新增审计")
    void warehouseDisable_replayIsSameResultAndNoWrite() throws Exception {
        long id = createWarehouse("W-090", "req-api-090-c");
        String before = updatedAt("warehouses", id);

        MvcResult first = sendPost("/api/v1/warehouses/" + id + "/disable", "req-api-090-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andReturn();
        String afterFirst = updatedAt("warehouses", id);
        assertThat(afterFirst).as("首次停用必须真的写一行").isNotEqualTo(before);

        MvcResult replay = sendPost("/api/v1/warehouses/" + id + "/disable", "req-api-090-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andReturn();

        assertThat(json.readTree(replay.getResponse().getContentAsString()))
                .as("重放响应体必须与首次逐字段一致")
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()));
        assertThat(updatedAt("warehouses", id))
                .as("★ 重放不许再写第二次：updated_at 不能被推后")
                .isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT disable_request_id FROM warehouses WHERE id = ?", String.class, id))
                .isEqualTo("req-api-090-d");
        assertThat(audits("WAREHOUSE", "DISABLE", id)).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-API-091")
    @DisplayName("POST /suppliers/{id}/disable：同键重放结果相同、零写入")
    void supplierDisable_replayIsSameResultAndNoWrite() throws Exception {
        long id = idOf(sendPost("/api/v1/suppliers", "req-api-091-c",
                write("code", "S-091", "name", "停用演练供应商"))
                .andExpect(status().isCreated()).andReturn());

        sendPost("/api/v1/suppliers/" + id + "/disable", "req-api-091-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        String afterFirst = updatedAt("suppliers", id);

        sendPost("/api/v1/suppliers/" + id + "/disable", "req-api-091-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(updatedAt("suppliers", id)).isEqualTo(afterFirst);
        assertThat(audits("SUPPLIER", "DISABLE", id)).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-API-092")
    @DisplayName("POST /customers/{id}/disable：同键重放结果相同、零写入")
    void customerDisable_replayIsSameResultAndNoWrite() throws Exception {
        long id = idOf(sendPost("/api/v1/customers", "req-api-092-c",
                write("code", "C-092", "name", "停用演练客户"))
                .andExpect(status().isCreated()).andReturn());

        sendPost("/api/v1/customers/" + id + "/disable", "req-api-092-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        String afterFirst = updatedAt("customers", id);

        sendPost("/api/v1/customers/" + id + "/disable", "req-api-092-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(updatedAt("customers", id)).isEqualTo(afterFirst);
        assertThat(audits("CUSTOMER", "DISABLE", id)).isEqualTo(1L);
    }

    // ─────────────────────────── 跨切面：缺头 / 审计 / 单号 ───────────────────────────

    @Test
    @CaseId("TC-API-093")
    @DisplayName("缺 X-Request-Id 或幂等键为空白 → 400 SYS_VALIDATION_ERROR，且失败也要留痕")
    void missingOrBlankRequestId_isRejectedAndAudited() throws Exception {
        String body = write("code", "W-093", "name", "缺键仓库");

        mvc.perform(post("/api/v1/warehouses").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
        assertThat(count("warehouses")).as("被拒的请求不许留下任何一行").isEqualTo(0L);

        mvc.perform(post("/api/v1/warehouses").header("Authorization", auth)
                        .header("X-Request-Id", "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
        assertThat(count("warehouses")).isEqualTo(0L);

        // ★ 两次失败的写尝试都由 AuditSafetyNetFilter 兜底留痕（方法根本没被调用，切面看不到）
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE resource_type = 'WAREHOUSE' AND result = 'FAIL'
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    @CaseId("TC-API-094")
    @DisplayName("★ 重放不重复写 audit_logs：5 个创建端点各 1 条 CREATE，重放后仍是 1 条")
    void replayDoesNotDuplicateAuditRows() throws Exception {
        long warehouseId = createWarehouse("W-094", "req-api-094-w");
        long productId = idOf(sendPost("/api/v1/products", "req-api-094-p",
                write("code", "P-094", "name", "审计商品")).andExpect(status().isCreated()).andReturn());
        long skuId = idOf(sendPost("/api/v1/skus", "req-api-094-s",
                write("productId", productId, "color", "黑", "size", "L"))
                .andExpect(status().isCreated()).andReturn());
        long supplierId = idOf(sendPost("/api/v1/suppliers", "req-api-094-u",
                write("code", "S-094", "name", "审计供应商")).andExpect(status().isCreated()).andReturn());
        long customerId = idOf(sendPost("/api/v1/customers", "req-api-094-c",
                write("code", "C-094", "name", "审计客户")).andExpect(status().isCreated()).andReturn());

        for (Map.Entry<String, Long> e : Map.of(
                "WAREHOUSE", warehouseId, "PRODUCT", productId, "SKU", skuId,
                "SUPPLIER", supplierId, "CUSTOMER", customerId).entrySet()) {
            assertThat(audits(e.getKey(), "CREATE", e.getValue()))
                    .as("%s 首次创建应有恰好 1 条 CREATE 审计", e.getKey())
                    .isEqualTo(1L);
        }

        // 逐个重放：键相同、内容相同，期望"一条流水都不许多"
        sendPost("/api/v1/warehouses", "req-api-094-w", write("code", "W-094", "name", "审计仓库"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/products", "req-api-094-p", write("code", "P-094", "name", "审计商品"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/skus", "req-api-094-s",
                write("productId", productId, "color", "黑", "size", "L"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/suppliers", "req-api-094-u", write("code", "S-094", "name", "审计供应商"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/customers", "req-api-094-c", write("code", "C-094", "name", "审计客户"))
                .andExpect(status().isCreated());

        assertThat(audits("WAREHOUSE", "CREATE", warehouseId)).isEqualTo(1L);
        assertThat(audits("PRODUCT", "CREATE", productId)).isEqualTo(1L);
        assertThat(audits("SKU", "CREATE", skuId)).isEqualTo(1L);
        assertThat(audits("SUPPLIER", "CREATE", supplierId)).isEqualTo(1L);
        assertThat(audits("CUSTOMER", "CREATE", customerId)).isEqualTo(1L);
    }

    // ─────────────────────────── MDM 模块内的语义 ───────────────────────────

    @Test
    @CaseId("TC-MDM-027")
    @DisplayName("★ 重放返回资源的当前状态，不是当时的响应快照（选方案 b 的理由）")
    void replayReturnsCurrentStateNotCachedSnapshot() throws Exception {
        long id = createWarehouse("W-027", "req-mdm-027");
        mvc.perform(put("/api/v1/warehouses/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write("name", "改名后的仓库", "enabled", true, "priority", 50)))
                .andExpect(status().isOk());

        JsonNode data = dataOf(sendPost("/api/v1/warehouses", "req-mdm-027",
                write("code", "W-027", "name", "改名前的旧名字"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(data.path("id").asLong()).isEqualTo(id);
        assertThat(data.path("name").asText())
                .as("通用表若缓存 response_body 会把旧名字吐回去；回查实体表才拿得到当前值")
                .isEqualTo("改名后的仓库");
    }

    @Test
    @CaseId("TC-MDM-028")
    @DisplayName("★ 同键第二次 INSERT 被数据库唯一索引拦住（绕过应用层，证明守卫不活在进程里）")
    void dbUniqueConstraintRejectsSameKey() {
        createWarehouse("W-028", "req-mdm-028");

        assertThatThrownBy(() -> warehouses.insert(
                "W-028-B", "并发演练的第二行", null, true, 100, "req-mdm-028"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_DUPLICATE_REQUEST);

        assertThat(countByCreateKey("warehouses", "req-mdm-028"))
                .as("拦住的是这个键，不是这张表 —— 换键必须能继续写").isEqualTo(1L);

        // 换一个键就写得进去：证明拦住它的是"键重复"，而不是别的约束
        assertThatCode(() -> warehouses.insert(
                "W-028-C", "换个键的第三行", null, true, 100, "req-mdm-028-new"))
                .doesNotThrowAnyException();

        // suppliers 同理：局部索引 + NULL 不参与唯一性（无键的内部写入彼此永不冲突）
        suppliers.insert("S-028-A", "无键甲", null, null, null, true, null);
        suppliers.insert("S-028-B", "无键乙", null, null, null, true, null);
        assertThat(count("suppliers")).isEqualTo(2L);
    }

    @Test
    @CaseId("TC-MDM-029")
    @DisplayName("V15 的 8 个局部唯一索引真实存在，且都带 IS NOT NULL 谓词")    void v15PartialUniqueIndexesExist() {
        String[] names = {"uq_warehouse_create_request", "uq_supplier_create_request",
                "uq_customer_create_request", "uq_product_create_request", "uq_sku_create_request",
                "uq_warehouse_disable_request", "uq_supplier_disable_request",
                "uq_customer_disable_request"};

        for (String name : names) {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT indexdef FROM pg_indexes
                     WHERE schemaname = 'public' AND indexname = ?
                    """, name);
            assertThat(String.valueOf(row.get("indexdef")))
                    .as("%s 必须是局部唯一索引：NULL 行不参与幂等判定", name)
                    .contains("UNIQUE").contains("IS NOT NULL");
        }
    }

    @Test
    @CaseId("TC-MDM-030")
    @DisplayName("幂等键不稀释编码唯一：不同键 + 同编码，仍然 409 MDM_CODE_DUPLICATED")
    void distinctKeysWithSameCode_stillConflictOnCode() throws Exception {
        sendPost("/api/v1/products", "req-mdm-030-a", write("code", "P-030", "name", "同编码商品"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/products", "req-mdm-030-b", write("code", "P-030", "name", "同编码商品"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MDM_CODE_DUPLICATED"));
        assertThat(count("products")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-MDM-031")
    @DisplayName("同键 + 不同编码：返回首次那一行（重放语义优先于业务字段差异）")
    void sameKeyWithDifferentPayload_returnsFirstResource() throws Exception {
        MvcResult first = sendPost("/api/v1/warehouses", "req-mdm-031",
                write("code", "W-031-A", "name", "首次请求"))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = sendPost("/api/v1/warehouses", "req-mdm-031",
                write("code", "W-031-B", "name", "换了编码的第二次"))
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(nameOf("warehouses", idOf(first))).isEqualTo("首次请求");
        assertThat(count("warehouses")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-MDM-032")
    @DisplayName("不带键的内部写入彼此永不冲突（局部索引不约束 NULL）")
    void nullKeysNeverCollide() {
        warehousesService.create(new WarehouseApplicationService.CreateCommand(
                "W-032-A", "无键甲", null, true));
        warehousesService.create(new WarehouseApplicationService.CreateCommand(
                "W-032-B", "无键乙", null, true));

        assertThat(count("warehouses")).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouses WHERE create_request_id IS NULL", Long.class))
                .isEqualTo(2L);
    }

    @Test
    @CaseId("TC-MDM-033")
    @DisplayName("停用后被重新启用、再用原键停用：这是真实变更，必须真的生效并留痕")
    void disableAfterReenable_isARealChangeNotAReplay() throws Exception {
        long id = createWarehouse("W-033", "req-mdm-033-c");
        sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-033-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(put("/api/v1/warehouses/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write("name", "重新启用的仓库", "enabled", true, "priority", 100)))
                .andExpect(status().isOk());

        // ★ 幂等键不该把一次真实的"再停一次"吞掉：资源当前是启用态，
        //   这次请求就真的把它改成停用（若把它判成重放，客户端会以为停用生效了，
        //   而仓库还在收货 —— 这是会漏进生产的那种 bug）。
        sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-033-d", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(jdbc.queryForObject("SELECT enabled FROM warehouses WHERE id = ?",
                Boolean.class, id)).isFalse();
        assertThat(audits("WAREHOUSE", "DISABLE", id))
                .as("两次都是真实状态变更，各留一条流水（与纯重放的 0 条区分开）")
                .isEqualTo(2L);
    }

    @Test
    @CaseId("TC-MDM-034")
    @DisplayName("已是停用态时用新键再停一次：状态已达成，不写第二遍")
    void disableAlreadyDisabled_writesNothing() throws Exception {
        long id = createWarehouse("W-034", "req-mdm-034-c");
        sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-034-d1", "{}")
                .andExpect(status().isOk());
        String afterFirst = updatedAt("warehouses", id);

        sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-034-d2", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(updatedAt("warehouses", id)).isEqualTo(afterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT disable_request_id FROM warehouses WHERE id = ?", String.class, id))
                .as("第二次没写行，也就不该把键换掉")
                .isEqualTo("req-mdm-034-d1");
    }

    @Test
    @CaseId("TC-MDM-035")
    @DisplayName("同一个停用键打到两个不同资源 → 409 SYS_DUPLICATE_REQUEST（返回「已停用」是错的）")
    void sameDisableKeyOnTwoResources_isRejected() throws Exception {
        long firstId = createWarehouse("W-035-A", "req-mdm-035-c1");
        long secondId = createWarehouse("W-035-B", "req-mdm-035-c2");

        sendPost("/api/v1/warehouses/" + firstId + "/disable", "req-mdm-035-shared", "{}")
                .andExpect(status().isOk());
        sendPost("/api/v1/warehouses/" + secondId + "/disable", "req-mdm-035-shared", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYS_DUPLICATE_REQUEST"));

        assertThat(jdbc.queryForObject("SELECT enabled FROM warehouses WHERE id = ?",
                Boolean.class, secondId)).as("被拦下的那次不许留下半成品").isTrue();
    }

    @Test
    @CaseId("TC-MDM-036")
    @DisplayName("★ MDM 写入永不占用单号序列；重放同样不进位（含正向对照）")
    void mdmWritesNeverAdvanceDocumentSequences() throws Exception {
        String idle = sequenceFingerprint();

        long id = createWarehouse("W-036", "req-mdm-036-c");
        sendPost("/api/v1/warehouses", "req-mdm-036-c", write("code", "W-036", "name", "序列无关"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-036-d", "{}")
                .andExpect(status().isOk());
        sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-036-d", "{}")
                .andExpect(status().isOk());

        assertThat(sequenceFingerprint())
                .as("MDM 的 code 由调用方给定，不进 document_sequences；重放同样不许进位")
                .isEqualTo(idle);

        // 正向对照：指纹真的能感知进位，否则上面那条断言等于什么都没测
        numbers.next(DocumentNumberGenerator.DocType.PURCHASE_ORDER, LocalDate.of(2026, 10, 24));
        assertThat(sequenceFingerprint()).isNotEqualTo(idle);
    }

    @Test
    @CaseId("TC-MDM-037")
    @DisplayName("重放不改任何一张 MDM 表的行数（五个创建端点各重放一次）")
    void replayAddsNoRowInAnyMdmTable() throws Exception {
        long productId = idOf(sendPost("/api/v1/products", "req-mdm-037-p",
                write("code", "P-037", "name", "行数不变商品")).andExpect(status().isCreated()).andReturn());
        sendPost("/api/v1/skus", "req-mdm-037-s",
                write("productId", productId, "color", "白", "size", "S"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/suppliers", "req-mdm-037-u", write("code", "S-037", "name", "行数不变供应商"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/customers", "req-mdm-037-c", write("code", "C-037", "name", "行数不变客户"))
                .andExpect(status().isCreated());
        long warehouseId = createWarehouse("W-037", "req-mdm-037-w");

        Map<String, Long> before = Map.of("warehouses", count("warehouses"), "suppliers", count("suppliers"),
                "customers", count("customers"), "products", count("products"), "skus", count("skus"));

        sendPost("/api/v1/warehouses", "req-mdm-037-w", write("code", "W-037", "name", "重放"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/suppliers", "req-mdm-037-u", write("code", "S-037", "name", "重放"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/customers", "req-mdm-037-c", write("code", "C-037", "name", "重放"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/products", "req-mdm-037-p", write("code", "P-037", "name", "重放"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/skus", "req-mdm-037-s",
                write("productId", productId, "color", "白", "size", "S"))
                .andExpect(status().isCreated());
        sendPost("/api/v1/warehouses/" + warehouseId + "/disable", "req-mdm-037-dis", "{}")
                .andExpect(status().isOk());
        sendPost("/api/v1/warehouses/" + warehouseId + "/disable", "req-mdm-037-dis", "{}")
                .andExpect(status().isOk());

        assertThat(count("warehouses")).isEqualTo(before.get("warehouses"));
        assertThat(count("suppliers")).isEqualTo(before.get("suppliers"));
        assertThat(count("customers")).isEqualTo(before.get("customers"));
        assertThat(count("products")).isEqualTo(before.get("products"));
        assertThat(count("skus")).isEqualTo(before.get("skus"));
    }

    @Test
    @CaseId("TC-MDM-038")
    @DisplayName("幂等键按表命名空间：同一个键打到不同资源类型互不干扰")
    void sameKeyAcrossDifferentTablesDoesNotCollide() throws Exception {
        long warehouseId = idOf(sendPost("/api/v1/warehouses", "req-mdm-038-shared",
                write("code", "W-038", "name", "仓库"))
                .andExpect(status().isCreated()).andReturn());
        long supplierId = idOf(sendPost("/api/v1/suppliers", "req-mdm-038-shared",
                write("code", "S-038", "name", "供应商"))
                .andExpect(status().isCreated()).andReturn());
        long productId = idOf(sendPost("/api/v1/products", "req-mdm-038-shared",
                write("code", "P-038", "name", "商品"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(warehouseId).isPositive();
        assertThat(supplierId).isPositive();
        assertThat(productId).isPositive();

        // 但在同一张表内，它仍然是那个键的第一次：换表不改变表内的重放判定
        MvcResult replayInWarehouse = sendPost("/api/v1/warehouses", "req-mdm-038-shared",
                write("code", "W-038", "name", "仓库")).andExpect(status().isCreated()).andReturn();
        assertThat(idOf(replayInWarehouse)).isEqualTo(warehouseId);
    }

    @Test
    @CaseId("TC-MDM-039")
    @DisplayName("重放响应体与首次逐字段一致（创建 + 停用两类都验）")
    void replayBodyIsIdenticalToFirst() throws Exception {
        String body = write("code", "W-039", "name", "逐字段一致", "address", "上海市", "priority", 7);
        MvcResult first = sendPost("/api/v1/warehouses", "req-mdm-039-c", body)
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = sendPost("/api/v1/warehouses", "req-mdm-039-c", body)
                .andExpect(status().isCreated()).andReturn();
        assertThat(dataOf(replay)).isEqualTo(dataOf(first));

        long id = idOf(first);
        MvcResult disabled = sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-039-d", "{}")
                .andExpect(status().isOk()).andReturn();
        MvcResult disabledReplay = sendPost("/api/v1/warehouses/" + id + "/disable", "req-mdm-039-d", "{}")
                .andExpect(status().isOk()).andReturn();
        assertThat(dataOf(disabledReplay)).isEqualTo(dataOf(disabled));
    }

    @Test
    @CaseId("TC-MDM-040")
    @DisplayName("★ 五张创建表的数据库守卫逐表生效：同键第二次 INSERT 一律被自己的索引拦住")
    void everyCreateTableHasItsOwnDbGuard() {
        // warehouses 由 TC-MDM-028 覆盖；这里补齐另外四张表。
        // 少测一张，那张表的幂等承诺就只剩"应用层的一个 if"—— 重启窗口内重复提交就会建出两行，
        // 而这件事在任何单线程用例里都看不出来。
        suppliers.insert("S-040-A", "首个供应商", null, null, null, true, "req-mdm-040-sup");
        assertThatThrownBy(() -> suppliers.insert(
                "S-040-B", "同键的第二行", null, null, null, true, "req-mdm-040-sup"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_DUPLICATE_REQUEST);

        products.insert("P-040-A", "首个商品", null, null, true, "req-mdm-040-prod");
        assertThatThrownBy(() -> products.insert(
                "P-040-B", "同键的第二行", null, null, true, "req-mdm-040-prod"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_DUPLICATE_REQUEST);

        customers.insert("C-040-A", "首个客户", null, 10000, "NORMAL", true, "req-mdm-040-cus");
        assertThatThrownBy(() -> customers.insert(
                "C-040-B", "同键的第二行", null, 10000, "NORMAL", true, "req-mdm-040-cus"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_DUPLICATE_REQUEST);

        long skuProductId = products.insert("P-040-SKU", "SKU 母商品", null, null, true, null);
        skus.insert(skuProductId, "红", "S", null, 100L, true, null, null, null, "req-mdm-040-sku");
        assertThatThrownBy(() -> skus.insert(skuProductId, "蓝", "M", null, 100L, true,
                null, null, null, "req-mdm-040-sku"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_DUPLICATE_REQUEST);

        assertThat(countByCreateKey("suppliers", "req-mdm-040-sup")).isEqualTo(1L);
        assertThat(countByCreateKey("products", "req-mdm-040-prod")).isEqualTo(1L);
        assertThat(countByCreateKey("customers", "req-mdm-040-cus")).isEqualTo(1L);
        assertThat(countByCreateKey("skus", "req-mdm-040-sku")).isEqualTo(1L);
    }

    @Test
    @CaseId("TC-MDM-041")
    @DisplayName("停用键的冲突判定对 suppliers / customers 同样生效（不只是仓库）")
    void disableKeyGuardCoversSupplierAndCustomerToo() throws Exception {
        long supplierA = idOf(sendPost("/api/v1/suppliers", "req-mdm-041-sa",
                write("code", "S-041-A", "name", "甲供应商"))
                .andExpect(status().isCreated()).andReturn());
        long supplierB = idOf(sendPost("/api/v1/suppliers", "req-mdm-041-sb",
                write("code", "S-041-B", "name", "乙供应商"))
                .andExpect(status().isCreated()).andReturn());
        sendPost("/api/v1/suppliers/" + supplierA + "/disable", "req-mdm-041-shared", "{}")
                .andExpect(status().isOk());
        sendPost("/api/v1/suppliers/" + supplierB + "/disable", "req-mdm-041-shared", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYS_DUPLICATE_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT enabled FROM suppliers WHERE id = ?",
                Boolean.class, supplierB)).isTrue();

        long customerA = idOf(sendPost("/api/v1/customers", "req-mdm-041-ca",
                write("code", "C-041-A", "name", "甲客户"))
                .andExpect(status().isCreated()).andReturn());
        long customerB = idOf(sendPost("/api/v1/customers", "req-mdm-041-cb",
                write("code", "C-041-B", "name", "乙客户"))
                .andExpect(status().isCreated()).andReturn());
        sendPost("/api/v1/customers/" + customerA + "/disable", "req-mdm-041-shared", "{}")
                .andExpect(status().isOk());
        sendPost("/api/v1/customers/" + customerB + "/disable", "req-mdm-041-shared", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYS_DUPLICATE_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT enabled FROM customers WHERE id = ?",
                Boolean.class, customerB)).isTrue();
    }

    // ─────────────────────────── 夹具与断言辅助 ───────────────────────────

    private long createWarehouse(String code, String requestId) {
        try {
            return idOf(sendPost("/api/v1/warehouses", requestId,
                    write("code", code, "name", "幂等用例仓库 " + code))
                    .andExpect(status().isCreated()).andReturn());
        } catch (Exception ex) {
            throw new IllegalStateException("创建仓库夹具失败：" + code, ex);
        }
    }

    private ResultActions sendPost(String path, String requestId, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", auth)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 请求体构造：允许 null 值，且保持字段顺序（Map.of 两者都做不到）。 */
    private String write(Object... keyValue) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            payload.put((String) keyValue[i], keyValue[i + 1]);
        }
        return json.writeValueAsString(payload);
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        // ★ 必须显式按 UTF-8 取响应文本：MockMvc 的响应默认按 ISO-8859-1 解码，
        //   直接 getContentAsString() 会把中文读成乱码（TC-MDM-027 首跑就踩到）。
        return json.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private long idOf(MvcResult result) throws Exception {
        return dataOf(result).path("id").asLong();
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    private long countByCreateKey(String table, String requestId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE create_request_id = ?",
                Long.class, requestId);
        return n == null ? 0L : n;
    }

    private String nameOf(String table, long id) {
        return jdbc.queryForObject("SELECT name FROM " + table + " WHERE id = ?", String.class, id);
    }

    /** {@code updated_at} 取文本形式比较：避开 Timestamp 的精度与类型差异。 */
    private String updatedAt(String table, long id) {
        return jdbc.queryForObject(
                "SELECT updated_at::text FROM " + table + " WHERE id = ?", String.class, id);
    }

    private long audits(String resourceType, String action, long resourceId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE resource_type = ? AND action = ? AND resource_id = ?
                """, Long.class, resourceType, action, String.valueOf(resourceId));
        return n == null ? 0L : n;
    }

    /**
     * 单号序列指纹：{@code 行数:累计号}。
     * 用它而不是单表行数，是因为 MDM 根本不写这张表 —— 要证明"没动过"，
     * 得用一个"动过就一定变"的度量，否则断言恒真。
     */
    private String sequenceFingerprint() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) || ':' || COALESCE(SUM(current_no), 0) FROM document_sequences
                """, String.class);
    }
}
