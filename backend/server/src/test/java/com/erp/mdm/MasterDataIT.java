package com.erp.mdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.domain.Product;
import com.erp.mdm.domain.Sku;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 主数据集成测试（FR-MDM-01~04）。
 *
 * <p>虽然是"CRUD 模块"，但测试重点<b>不是</b>"能插能查"，而是计划书 §2.1 强调的三条规则：
 * <ol>
 *   <li>编码唯一性由<b>数据库约束</b>保证，而不是应用层"先查后插"；</li>
 *   <li>SKU 矩阵生成的"跳过已存在"与"请求自冲突报错"两种语义必须分清；</li>
 *   <li>停用不删：停用后记录仍在、仍可查、历史引用不断。</li>
 * </ol>
 */
@SpringBootTest
@DisplayName("主数据 · 商品 / SKU / 供应商 / 客户 / 仓库")
class MasterDataIT extends PostgresTestBase {

    @Autowired
    private ProductApplicationService products;
    @Autowired
    private SkuApplicationService skus;
    @Autowired
    private SupplierApplicationService suppliers;
    @Autowired
    private CustomerApplicationService customers;
    @Autowired
    private WarehouseApplicationService warehouses;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
    }

    // ─────────────────────────── FR-MDM-01 商品 ───────────────────────────

    @Test
    @CaseId("TC-MDM-001")
    @DisplayName("商品编码重复必须被数据库唯一约束拒绝，并翻译成 MDM_CODE_DUPLICATED")
    void createProduct_withDuplicateCode_thenReject() {
        products.create(new ProductApplicationService.CreateCommand(
                "P-DUP-001", "圆领T恤", "上装", "2025夏", true));

        assertThatThrownBy(() -> products.create(new ProductApplicationService.CreateCommand(
                "P-DUP-001", "另一个T恤", null, null, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_CODE_DUPLICATED)
                .hasMessageContaining("P-DUP-001");
    }

    @Test
    @CaseId("TC-MDM-002")
    @DisplayName("★ 并发创建同一商品编码：只允许一个成功，其余全部是 409 而不是 500")
    void createProduct_concurrently_withSameCode_onlyOneSucceeds() throws Exception {
        int threads = 12;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var startGate = new java.util.concurrent.CountDownLatch(1);
        var readyGate = new java.util.concurrent.CountDownLatch(threads);
        var succeeded = new java.util.concurrent.atomic.AtomicInteger();
        var duplicated = new java.util.concurrent.atomic.AtomicInteger();
        var unexpected = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();

        List<java.util.concurrent.Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((java.util.concurrent.Callable<Void>) () -> {
                readyGate.countDown();
                startGate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    products.create(new ProductApplicationService.CreateCommand(
                            "P-RACE-001", "并发商品", null, null, true));
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getCode() == ErrorCode.MDM_CODE_DUPLICATED) {
                        duplicated.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (RuntimeException e) {
                    unexpected.add(e);
                }
                return null;
            }));
        }
        assertThat(readyGate.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        for (var f : futures) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(unexpected).as("不允许出现非业务异常（那说明约束翻译漏了路径）").isEmpty();
        assertThat(succeeded.get()).as("只能有一个成功").isEqualTo(1);
        assertThat(duplicated.get()).isEqualTo(threads - 1);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE code = 'P-RACE-001'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @CaseId("TC-MDM-003")
    @DisplayName("商品停用后记录保留（停用不删），且 enabled 变为 false")
    void disableProduct_shouldSoftDelete() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-SD-001", "待下架商品", null, null, true));

        products.disable(p.id());

        assertThat(products.require(p.id()).enabled()).isFalse();
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ?",
                Integer.class, p.id());
        assertThat(rows).as("停用不得物理删除，历史单据要能查到它").isEqualTo(1);
    }

    @Test
    @CaseId("TC-MDM-004")
    @DisplayName("商品分页：keyword 同时匹配编码与名称，total 是全部匹配数而非当前页")
    void pageProducts_shouldFilterAndReportTotal() {
        for (int i = 1; i <= 5; i++) {
            products.create(new ProductApplicationService.CreateCommand(
                    "P-PG-%03d".formatted(i), "卫衣" + i, null, null, true));
        }
        products.create(new ProductApplicationService.CreateCommand(
                "P-OTHER-001", "牛仔裤", null, null, true));

        PageResult<Product> page = products.page("卫衣", 1, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.total()).as("total 必须是 5 而不是 2").isEqualTo(5);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
    }

    @Test
    @CaseId("TC-MDM-005")
    @DisplayName("商品不存在时查详情返回 404 语义的业务异常（不是空指针）")
    void requireProduct_whenMissing_thenNotFound() {
        assertThatThrownBy(() -> products.require(999_999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);
    }

    // ─────────────────────────── FR-MDM-01 SKU 与矩阵 ───────────────────────────

    @Test
    @CaseId("TC-MDM-006")
    @DisplayName("★ SKU 矩阵生成：2 色 × 3 码 = 6 个 SKU，且组合与价格正确")
    void createMatrix_shouldGenerateCartesianProduct() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-MX-001", "矩阵T恤", null, null, true));

        var result = skus.createMatrix(p.id(),
                List.of("白色", "黑色"), List.of("S", "M", "L"), 12900L, true);

        assertThat(result.created()).hasSize(6);
        assertThat(result.skipped()).isZero();
        assertThat(result.created())
                .extracting(Sku::color, Sku::size)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("白色", "S"),
                        org.assertj.core.groups.Tuple.tuple("白色", "M"),
                        org.assertj.core.groups.Tuple.tuple("白色", "L"),
                        org.assertj.core.groups.Tuple.tuple("黑色", "S"),
                        org.assertj.core.groups.Tuple.tuple("黑色", "M"),
                        org.assertj.core.groups.Tuple.tuple("黑色", "L"));
        assertThat(result.created()).allSatisfy(s ->
                assertThat(s.retailPriceAmount()).isEqualTo(12900L));
    }

    @Test
    @CaseId("TC-MDM-007")
    @DisplayName("★ 矩阵重复执行：已存在组合被跳过而不是报错（运营补色的安全重放）")
    void createMatrix_again_shouldSkipExisting() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-MX-002", "可补色T恤", null, null, true));
        skus.createMatrix(p.id(), List.of("白色"), List.of("S", "M"), 9900L, true);

        var second = skus.createMatrix(p.id(), List.of("白色", "红色"), List.of("S", "M"), 9900L, true);

        assertThat(second.created()).as("只新增红色两个").hasSize(2);
        assertThat(second.skipped()).as("白色两个已存在").isEqualTo(2);
        assertThat(skus.findByProduct(p.id())).hasSize(4);
    }

    @Test
    @CaseId("TC-MDM-008")
    @DisplayName("★ 矩阵请求自身重复（颜色列表写了两次）：必须报 MDM_SKU_DUPLICATED，不静默去重")
    void createMatrix_withSelfConflict_thenReject() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-MX-003", "自冲突T恤", null, null, true));

        assertThatThrownBy(() -> skus.createMatrix(p.id(),
                List.of("白色", "白色"), List.of("S"), 9900L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_SKU_DUPLICATED)
                .hasMessageContaining("白色");

        assertThat(skus.findByProduct(p.id())).as("失败必须整批回滚，一条都不留").isEmpty();
    }

    @Test
    @CaseId("TC-MDM-009")
    @DisplayName("矩阵的颜色或尺码列表为空：校验错误，不静默生成 0 条")
    void createMatrix_withEmptyAxis_thenValidationError() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-MX-004", "空维度T恤", null, null, true));

        assertThatThrownBy(() -> skus.createMatrix(p.id(), List.of(), List.of("S"), 100L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
        assertThatThrownBy(() -> skus.createMatrix(p.id(), List.of("白色"), List.of(), 100L, true))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
    }

    @Test
    @CaseId("TC-MDM-010")
    @DisplayName("同一 (颜色, 尺码) 在不同商品下可重复；同一商品下重复则被约束拒绝")
    void skuVariantUniqueness_isScopedToProduct() {
        Product a = products.create(new ProductApplicationService.CreateCommand(
                "P-A-001", "商品A", null, null, true));
        Product b = products.create(new ProductApplicationService.CreateCommand(
                "P-B-001", "商品B", null, null, true));

        // 不同商品，相同组合 —— 合法
        skus.create(new SkuApplicationService.CreateCommand(a.id(), "白色", "L", null, 100L, true));
        skus.create(new SkuApplicationService.CreateCommand(b.id(), "白色", "L", null, 100L, true));

        // 同一商品，重复组合 —— 必须被拒绝
        assertThatThrownBy(() -> skus.create(
                new SkuApplicationService.CreateCommand(a.id(), "白色", "L", null, 100L, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_SKU_DUPLICATED);
    }

    @Test
    @CaseId("TC-MDM-011")
    @DisplayName("SKU 停用后不删：仍可查到，但 enabled=false")
    void disableSku_shouldSoftDelete() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-SS-001", "停用SKU商品", null, null, true));
        Sku s = skus.create(new SkuApplicationService.CreateCommand(
                p.id(), "灰色", "XL", "6909999999", 15900L, true));

        skus.disable(s.id());

        Sku after = skus.require(s.id());
        assertThat(after.enabled()).isFalse();
        assertThat(after.barcode()).as("停用不应丢失基本信息").isEqualTo("6909999999");
        assertThat(after.retailPriceAmount()).isEqualTo(15900L);
    }

    @Test
    @CaseId("TC-MDM-012")
    @DisplayName("SKU 零售价为负：校验错误（金额非负，ADR-0005）")
    void createSku_withNegativePrice_thenValidationError() {
        Product p = products.create(new ProductApplicationService.CreateCommand(
                "P-NEG-001", "负价商品", null, null, true));

        assertThatThrownBy(() -> skus.create(new SkuApplicationService.CreateCommand(
                p.id(), "白色", "L", null, -1L, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
    }

    @Test
    @CaseId("TC-MDM-013")
    @DisplayName("给不存在的商品建 SKU：返回 404 语义（外键约束被翻译）")
    void createSku_forMissingProduct_thenNotFound() {
        assertThatThrownBy(() -> skus.create(new SkuApplicationService.CreateCommand(
                888_888L, "白色", "L", null, 100L, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);
    }

    // ─────────────────────────── FR-MDM-02 供应商 ───────────────────────────

    @Test
    @CaseId("TC-MDM-014")
    @DisplayName("供应商编码唯一：重复编码返回 MDM_CODE_DUPLICATED")
    void createSupplier_withDuplicateCode_thenReject() {
        suppliers.create(new SupplierApplicationService.CreateCommand(
                "SUP-DUP", "供应商甲", "张三", "13800000000", null, true));

        assertThatThrownBy(() -> suppliers.create(new SupplierApplicationService.CreateCommand(
                "SUP-DUP", "供应商乙", null, null, null, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_CODE_DUPLICATED);
    }

    @Test
    @CaseId("TC-MDM-015")
    @DisplayName("供应商停用不删，联系方式保留")
    void disableSupplier_shouldNeverDelete() {
        var s = suppliers.create(new SupplierApplicationService.CreateCommand(
                "SUP-SD", "待停用供应商", "李四", "13900000000", "广州市", true));

        suppliers.disable(s.id());

        var after = suppliers.require(s.id());
        assertThat(after.enabled()).isFalse();
        assertThat(after.contactName()).isEqualTo("李四");
        assertThat(after.contactPhone()).isEqualTo("13900000000");
    }

    // ─────────────────────────── FR-MDM-03 客户 ───────────────────────────

    @Test
    @CaseId("TC-MDM-016")
    @DisplayName("客户折扣等级：默认 10000（不打折），可设 9000（9 折）")
    void customerDiscountRate_shouldBeIntegerBasisPoints() {
        var normal = customers.create(new CustomerApplicationService.CreateCommand(
                "CUS-N", "普通客户", null, 10000, "NORMAL", true));
        var vip = customers.create(new CustomerApplicationService.CreateCommand(
                "CUS-V", "VIP客户", null, 9000, "VIP", true));

        assertThat(normal.discountRate()).isEqualTo(10000);
        assertThat(vip.discountRate()).isEqualTo(9000);
        assertThat(vip.level()).isEqualTo("VIP");
    }

    @Test
    @CaseId("TC-MDM-017")
    @DisplayName("★ 折扣率越界（0 / 10001 / 负数）必须被拒绝：0 等于白送，不允许")
    void customerDiscountRate_outOfRange_thenReject() {
        for (int bad : new int[]{0, -1, 10001, 20000}) {
            assertThatThrownBy(() -> customers.create(new CustomerApplicationService.CreateCommand(
                    "CUS-BAD-" + Math.abs(bad), "越界客户", null, bad, "NORMAL", true)))
                    .as("折扣率 %d 必须被拒绝", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
        }
    }

    @Test
    @CaseId("TC-MDM-018")
    @DisplayName("数据库 CHECK 兜底：即使绕过应用层，折扣率也无法写成 0 或超过 10000")
    void databaseConstraint_mustGuardDiscountRate() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO customers (code, name, discount_rate) VALUES ('CUS-RAW', '原始写入', 0)
                """))
                .as("不变量必须由数据库兜底")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ─────────────────────────── FR-MDM-04 仓库 ───────────────────────────

    @Test
    @CaseId("TC-MDM-019")
    @DisplayName("仓库 CRUD + 编码唯一 + onlyEnabled 过滤")
    void warehouse_crudAndEnabledFilter() {
        var sh = warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-SH", "上海仓", "青浦区", true));
        warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-GZ", "广州仓", "白云区", true));

        assertThat(warehouses.list(false)).hasSize(2);
        assertThat(warehouses.list(true)).hasSize(2);

        assertThatThrownBy(() -> warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-SH", "重复仓", null, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_CODE_DUPLICATED);

        warehouses.disable(sh.id());
        assertThat(warehouses.list(true)).as("停用后不再出现在启用列表").hasSize(1);
        assertThat(warehouses.list(false)).as("但全量列表仍能看到").hasSize(2);
    }
}
