package com.erp.mdm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.mdm.application.CustomerAddressApplicationService;
import com.erp.mdm.application.ProductAttributeApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.WarehouseLocationApplicationService;
import com.erp.mdm.domain.CustomerAddress;
import com.erp.mdm.domain.ProductAttribute;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.WarehouseLocation;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantId;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 主数据字段扩展集成测试（V18 · Batch B / B2）。
 *
 * <p>重点不是 CRUD，而是三条不变量：
 * ① 默认地址唯一且停用默认会自动提升；② 启用库位容量合计与仓库容量的守恒；
 * ③ 规格属性不破坏 SKU 矩阵与颜色×尺码唯一键。
 */
@SpringBootTest
@DisplayName("主数据字段 · 客户地址 / 仓库库位 / 商品规格属性")
class MasterDataFieldsIT extends PostgresTestBase {

    @Autowired
    private CustomerAddressApplicationService addresses;
    @Autowired
    private WarehouseLocationApplicationService locations;
    @Autowired
    private ProductAttributeApplicationService attributes;
    @Autowired
    private SkuApplicationService skus;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
    }

    // ─────────────────────────── 客户收货地址 ───────────────────────────

    @Test
    @CaseId("TC-MDM-043")
    @DisplayName("地址：首条自动默认；同客户多地址；任一时刻只有一个默认")
    void address_firstBecomesDefault_andOnlyOneDefault() {
        long customer = data.customer("ADDR-ONE");

        CustomerAddress home = addresses.create(customer,
                address("HOME", "张三", null, null));
        assertThat(home.isDefault()).as("首条启用地址自动成为默认").isTrue();

        CustomerAddress office = addresses.create(customer,
                address("OFFICE", "李四", false, null));
        assertThat(office.isDefault()).isFalse();
        assertThat(defaultsOf(customer)).containsExactly(home.id());

        CustomerAddress switched = addresses.setDefault(customer, office.id());
        assertThat(switched.isDefault()).isTrue();
        assertThat(defaultsOf(customer)).containsExactly(office.id());
        assertThat(addresses.require(customer, home.id()).isDefault()).isFalse();
    }

    @Test
    @CaseId("TC-MDM-044")
    @DisplayName("地址键：客户内唯一；跨客户可复用；建单幂等键重放返回首次结果")
    void addressKey_uniqueWithinCustomer_andIdempotentCreate() {
        long customer = data.customer("ADDR-TWO");
        long other = data.customer("ADDR-TWO-OTHER");
        addresses.create(customer, address("HOME", "张三", true, null));

        assertThatThrownBy(() -> addresses.create(customer, address("HOME", "李四", false, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_ADDRESS_DUPLICATED)
                .hasMessageContaining("HOME");

        CustomerAddress otherHome = addresses.create(other, address("HOME", "王五", true, null));
        assertThat(otherHome.customerId()).isEqualTo(other);

        CustomerAddress first = addresses.create(customer,
                address("WORK", "赵六", false, "req-addr-1"));
        CustomerAddress replay = addresses.create(customer,
                address("WORK", "赵六", false, "req-addr-1"));
        assertThat(replay.id()).as("同键重放返回首次那一行").isEqualTo(first.id());

        assertThatThrownBy(() -> addresses.create(other,
                address("WORK2", "钱七", false, "req-addr-1")))
                .as("同一幂等键被另一个客户复用时必须报错，不能返回别人的地址")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_DUPLICATE_REQUEST);
    }

    @Test
    @CaseId("TC-MDM-045")
    @DisplayName("★ 停用默认地址自动提升下一条；历史出货单计数与客户引用不变")
    void disableDefaultAddress_promotesNext_andKeepsHistory() {
        long customer = data.customer("ADDR-THREE");
        long warehouse = data.warehouse("ADDR-WH");
        CustomerAddress first = addresses.create(customer,
                address("A1", "张三", true, null));
        CustomerAddress second = addresses.create(customer,
                address("A2", "李四", false, null));

        jdbc.update("""
                INSERT INTO sales_orders
                    (order_no, status, customer_id, warehouse_id, biz_date,
                     total_amount, total_qty, tenant_id)
                VALUES ('SO-ADDR-HIST-1', 'DRAFT', ?, ?, CURRENT_DATE, 0, 0, ?)
                """, customer, warehouse, TenantId.DEFAULT);
        int ordersBefore = count("SELECT COUNT(*) FROM sales_orders WHERE customer_id = ?", customer);

        assertThat(first.isDefault()).isTrue();
        CustomerAddress disabled = addresses.disable(customer, first.id());

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.isDefault()).isFalse();
        assertThat(addresses.require(customer, second.id()).isDefault())
                .as("停用默认后自动提升最早创建的启用地址").isTrue();
        assertThat(defaultsOf(customer)).containsExactly(second.id());

        int ordersAfter = count("SELECT COUNT(*) FROM sales_orders WHERE customer_id = ?", customer);
        assertThat(ordersAfter).as("地址停用不得改写/删除历史单据").isEqualTo(ordersBefore);
        assertThat(jdbc.queryForObject(
                "SELECT customer_id FROM sales_orders WHERE order_no = 'SO-ADDR-HIST-1'",
                Long.class)).isEqualTo(customer);

        assertThat(addresses.list(customer, true))
                .extracting(CustomerAddress::id)
                .as("onlyEnabled 列表不再包含停用地址").containsExactly(second.id());
        assertThat(addresses.list(customer, false))
                .extracting(CustomerAddress::id)
                .as("全量列表仍保留停用地址（软删）").contains(first.id(), second.id());
    }

    @Test
    @CaseId("TC-MDM-046")
    @DisplayName("地址边界：默认不能直接取消/不能停用；切换默认；跨客户访问 404")
    void addressDefaultBoundaries() {
        long customer = data.customer("ADDR-FOUR");
        long other = data.customer("ADDR-FOUR-OTHER");
        CustomerAddress first = addresses.create(customer, address("A1", "张三", true, null));
        CustomerAddress second = addresses.create(customer, address("A2", "李四", false, null));

        assertThatThrownBy(() -> addresses.update(customer, first.id(),
                new CustomerAddressApplicationService.UpdateCommand(
                        "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                        "文三路 A1", false, true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("不能直接取消默认");

        CustomerAddress switched = addresses.update(customer, second.id(),
                new CustomerAddressApplicationService.UpdateCommand(
                        "李四", "13800000000", "浙江省", "杭州市", "滨江区",
                        "江南大道 2 号", true, true));
        assertThat(switched.isDefault()).isTrue();
        assertThat(defaultsOf(customer)).containsExactly(second.id());

        assertThatThrownBy(() -> addresses.update(customer, first.id(),
                new CustomerAddressApplicationService.UpdateCommand(
                        "张三", "13800000000", "浙江省", "杭州市", "西湖区",
                        "文三路 A1", true, false)))
                .as("停用地址不能同时被设为默认")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> addresses.require(other, first.id()))
                .as("跨客户访问地址必须 404")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_ADDRESS_NOT_FOUND);

        addresses.disable(customer, second.id());
        assertThatThrownBy(() -> addresses.setDefault(customer, second.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("停用");
    }

    // ─────────────────────────── 仓库库位与容量守恒 ───────────────────────────

    @Test
    @CaseId("TC-MDM-047")
    @DisplayName("库位编码：仓库内唯一；不同仓库可复用；建单幂等键重放")
    void locationCode_uniqueWithinWarehouse() {
        long wh1 = data.warehouse("LOC-WH1");
        long wh2 = data.warehouse("LOC-WH2");

        WarehouseLocation l1 = locations.create(wh1,
                new WarehouseLocationApplicationService.CreateCommand(
                        "A-01", "A 区 01", 40L, true, "req-loc-1"));
        locations.create(wh1, new WarehouseLocationApplicationService.CreateCommand(
                "B-01", "B 区 01", 60L, true, null));
        WarehouseLocation other = locations.create(wh2,
                new WarehouseLocationApplicationService.CreateCommand(
                        "A-01", "另一个仓的 A-01", 10L, true, null));
        assertThat(other.warehouseId()).isEqualTo(wh2);

        assertThatThrownBy(() -> locations.create(wh1,
                new WarehouseLocationApplicationService.CreateCommand(
                        "A-01", "重复", 5L, true, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_LOCATION_DUPLICATED)
                .hasMessageContaining("A-01");

        WarehouseLocation replay = locations.create(wh1,
                new WarehouseLocationApplicationService.CreateCommand(
                        "A-01", "A 区 01", 40L, true, "req-loc-1"));
        assertThat(replay.id()).as("幂等键命中不重复插入").isEqualTo(l1.id());
        assertThat(locations.list(wh1, false)).hasSize(2);
    }

    @Test
    @CaseId("TC-MDM-048")
    @DisplayName("★ 容量守恒：40+60=100 一致；超限 409；停用后核对如实报差额；启用恢复")
    void locationCapacity_conservation() {
        long wh = data.warehouse("CAP-WH");
        assertThat(locations.setCapacity(wh, 100).capacityQty()).isEqualTo(100);

        WarehouseLocation l40 = locations.create(wh,
                new WarehouseLocationApplicationService.CreateCommand(
                        "L40", "40 区", 40L, true, null));
        WarehouseLocation l60 = locations.create(wh,
                new WarehouseLocationApplicationService.CreateCommand(
                        "L60", "60 区", 60L, true, null));

        WarehouseLocationApplicationService.Reconcile balanced = locations.reconcile(wh);
        assertThat(balanced.warehouseCapacityQty()).isEqualTo(100);
        assertThat(balanced.locationCapacityQty()).isEqualTo(100);
        assertThat(balanced.difference()).isZero();
        assertThat(balanced.enabledLocationCount()).isEqualTo(2);
        assertThat(balanced.totalLocationCount()).isEqualTo(2);
        assertThat(balanced.consistent()).isTrue();

        assertThatThrownBy(() -> locations.create(wh,
                new WarehouseLocationApplicationService.CreateCommand(
                        "L1", "超额 1", 1L, true, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_LOCATION_CAPACITY_EXCEEDED)
                .hasMessageContaining("仓库容量");

        assertThatThrownBy(() -> locations.update(wh, l60.id(),
                new WarehouseLocationApplicationService.UpdateCommand("60 区", 61L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_LOCATION_CAPACITY_EXCEEDED);

        assertThatThrownBy(() -> locations.setCapacity(wh, 50))
                .as("仓库容量不能收缩到低于已分配库位")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_LOCATION_CAPACITY_EXCEEDED);

        locations.disable(wh, l60.id());
        WarehouseLocationApplicationService.Reconcile gap = locations.reconcile(wh);
        assertThat(gap.locationCapacityQty()).isEqualTo(40);
        assertThat(gap.difference()).isEqualTo(60);
        assertThat(gap.enabledLocationCount()).isEqualTo(1);
        assertThat(gap.totalLocationCount()).isEqualTo(2);
        assertThat(gap.consistent()).isFalse();

        locations.enable(wh, l60.id());
        assertThat(locations.reconcile(wh).consistent()).isTrue();
        assertThat(l40.capacityQty()).isEqualTo(40);
    }

    @Test
    @CaseId("TC-MDM-049")
    @DisplayName("★ 停用库位不触碰库存：余额/流水条数与实存不变，停用库位仍可查")
    void disableLocation_doesNotTouchInventoryHistory() {
        long wh = data.warehouse("HIST-WH");
        long product = data.product("HIST-P", "历史商品");
        long sku = data.sku(product, "白色", "L");
        data.stock(sku, wh, "HIST-B1", null, 10);
        data.ledger(sku, wh, "HIST-B1", 10, "PURCHASE_IN", "PO-HIST-1");

        int balancesBefore = count(
                "SELECT COUNT(*) FROM inventory_balances WHERE warehouse_id = ?", wh);
        int ledgerBefore = count(
                "SELECT COUNT(*) FROM inventory_ledger WHERE warehouse_id = ?", wh);

        WarehouseLocation location = locations.create(wh,
                new WarehouseLocationApplicationService.CreateCommand(
                        "H-01", "历史区", 20L, true, null));
        locations.disable(wh, location.id());

        assertThat(count("SELECT COUNT(*) FROM inventory_balances WHERE warehouse_id = ?", wh))
                .isEqualTo(balancesBefore);
        assertThat(count("SELECT COUNT(*) FROM inventory_ledger WHERE warehouse_id = ?", wh))
                .isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("""
                SELECT on_hand_qty FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = 'HIST-B1'
                """, Integer.class, sku, wh)).isEqualTo(10);

        assertThat(locations.require(wh, location.id()).enabled()).isFalse();
        assertThat(locations.list(wh, false)).hasSize(1);
        assertThat(locations.list(wh, true)).isEmpty();
    }

    // ─────────────────────────── 商品规格属性 ───────────────────────────

    @Test
    @CaseId("TC-MDM-050")
    @DisplayName("属性：全量替换/清空；重复键 400；超限 400；跨租户商品 404")
    void productAttributes_replaceAndValidate() {
        long product = data.product("ATTR-P", "属性商品");

        List<ProductAttribute> saved = attributes.replace(product, List.of(
                new ProductAttribute("面料", "纯棉"),
                new ProductAttribute("版型", "直筒")));
        assertThat(saved).extracting(ProductAttribute::key)
                .containsExactlyInAnyOrder("面料", "版型");

        assertThat(attributes.replace(product, List.of(
                new ProductAttribute("面料", "亚麻"),
                new ProductAttribute("版型", "直筒"))))
                .filteredOn(a -> a.key().equals("面料"))
                .extracting(ProductAttribute::value)
                .containsExactly("亚麻");

        assertThat(attributes.replace(product, List.of())).isEmpty();
        assertThat(attributes.list(product)).isEmpty();

        assertThatThrownBy(() -> attributes.replace(product, List.of(
                new ProductAttribute("面料", "纯棉"),
                new ProductAttribute("面料", "亚麻"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID)
                .hasMessageContaining("重复");

        List<ProductAttribute> tooMany = new ArrayList<>();
        for (int i = 0; i < ProductAttributeApplicationService.MAX_ENTRIES + 1; i++) {
            tooMany.add(new ProductAttribute("k" + i, "v" + i));
        }
        assertThatThrownBy(() -> attributes.replace(product, tooMany))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID);

        assertThatThrownBy(() -> TenantContext.callIn(TenantId.DEMO,
                () -> attributes.list(product)))
                .as("租户 2 读租户 1 的商品属性必须 404")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);

        assertThatThrownBy(() -> TenantContext.callIn(TenantId.DEMO,
                () -> attributes.replace(product, List.of(new ProductAttribute("面料", "棉")))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);
    }

    @Test
    @CaseId("TC-MDM-051")
    @DisplayName("★ 属性不破坏 SKU 矩阵：矩阵仍生成/跳过，颜色×尺码唯一键仍生效，季节波段保留")
    void productAttributes_doNotBreakSkuMatrix() {
        long product = data.product("ATTR-MATRIX-P", "矩阵商品");

        SkuApplicationService.MatrixResult first = skus.createMatrix(product,
                List.of("白色", "黑色"), List.of("S", "M"), 12900, true);
        assertThat(first.created()).hasSize(4);
        assertThat(first.skipped()).isZero();

        attributes.replace(product, List.of(
                new ProductAttribute("面料", "纯棉"),
                new ProductAttribute("版型", "直筒")));

        SkuApplicationService.MatrixResult replay = skus.createMatrix(product,
                List.of("白色", "黑色"), List.of("S", "M"), 12900, true);
        assertThat(replay.created()).isEmpty();
        assertThat(replay.skipped()).isEqualTo(4);

        assertThatThrownBy(() -> skus.create(new SkuApplicationService.CreateCommand(
                product, "白色", "S", null, 12900, true)))
                .as("颜色×尺码唯一键不因属性写入而放宽")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_SKU_DUPLICATED);

        long skuId = first.created().get(0).id();
        Sku updated = skus.updateDimensions(skuId,
                new SkuApplicationService.DimensionsCommand(
                        "SS26", "B1", LocalDate.of(2026, 2, 1)));
        assertThat(updated.seasonCode()).isEqualTo("SS26");
        assertThat(updated.bandCode()).isEqualTo("B1");

        attributes.replace(product, List.of(new ProductAttribute("洗涤", "手洗")));
        Sku reread = skus.require(skuId);
        assertThat(reread.seasonCode()).isEqualTo("SS26");
        assertThat(reread.bandCode()).isEqualTo("B1");
        assertThat(reread.launchDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(skus.findByProduct(product)).hasSize(4);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static CustomerAddressApplicationService.CreateCommand address(
            String key, String recipient, Boolean isDefault, String requestId) {
        return new CustomerAddressApplicationService.CreateCommand(
                key, recipient, "13800000000", "浙江省", "杭州市", "西湖区",
                "文三路 " + key, isDefault, true, requestId);
    }

    private List<Long> defaultsOf(long customerId) {
        return addresses.list(customerId, false).stream()
                .filter(CustomerAddress::isDefault)
                .map(CustomerAddress::id)
                .collect(Collectors.toList());
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
