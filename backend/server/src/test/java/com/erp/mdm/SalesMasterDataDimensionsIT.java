package com.erp.mdm;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.SkuFilter;
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

/** 季节/年份/波段与仓库优先级（FR-MDM-08、FR-SAL-11 主数据侧）。 */
@SpringBootTest
@DisplayName("主数据 · 季节/波段/年份与仓库优先级")
class SalesMasterDataDimensionsIT extends PostgresTestBase {

    @Autowired
    private SkuApplicationService skus;
    @Autowired
    private WarehouseApplicationService warehouses;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long productId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        productId = data.product("P-DIM", "维度测试商品");
    }

    @Test
    @CaseId("TC-MDM-020")
    @DisplayName("SKU 创建写入季节/波段/上市日期，年份由上市日期派生")
    void createSku_withSeasonBandLaunchDate() {
        Sku sku = skus.create(new SkuApplicationService.CreateCommand(
                productId, "白色", "L", "6901000201", 12900L, true,
                "2026SS", "BAND-01", LocalDate.of(2026, 3, 1)));

        assertThat(sku.seasonCode()).isEqualTo("2026SS");
        assertThat(sku.bandCode()).isEqualTo("BAND-01");
        assertThat(sku.launchDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(sku.seasonYear()).isEqualTo(2026);
    }

    @Test
    @CaseId("TC-MDM-021")
    @DisplayName("SKU 更新维度：只改季节/波段不丢基础字段，null 表示保持原值")
    void updateSkuDimensions() {
        Sku sku = skus.create(new SkuApplicationService.CreateCommand(
                productId, "白色", "L", "6901000202", 12900L, true,
                "2025AW", "BAND-A", LocalDate.of(2025, 9, 1)));

        Sku updated = skus.updateDimensions(sku.id(),
                new SkuApplicationService.DimensionsCommand("2026SS", "", null));

        assertThat(updated.seasonCode()).isEqualTo("2026SS");
        assertThat(updated.bandCode()).isNull();
        assertThat(updated.launchDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(updated.retailPriceAmount()).isEqualTo(12900L);
    }

    @Test
    @CaseId("TC-MDM-022")
    @DisplayName("按季节编码筛选：只返回匹配 SKU")
    void filterBySeasonCode() {
        createSku("白", "L", "2026SS", "B1", LocalDate.of(2026, 3, 1));
        createSku("黑", "M", "2025AW", "A1", LocalDate.of(2025, 9, 1));

        var page = skus.page(new SkuFilter(
                null, null, null, "2026SS", null, null, null, null), 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(s -> assertThat(s.seasonCode()).isEqualTo("2026SS"));
    }

    @Test
    @CaseId("TC-MDM-023")
    @DisplayName("按波段 + 上市日期区间筛选")
    void filterByBandAndLaunchDateRange() {
        createSku("白", "L", "2026SS", "BAND-01", LocalDate.of(2026, 3, 1));
        createSku("黑", "M", "2026SS", "BAND-02", LocalDate.of(2026, 4, 1));
        createSku("蓝", "S", "2026AW", "BAND-01", LocalDate.of(2026, 9, 1));

        var page = skus.page(new SkuFilter(
                null, null, null, null, "BAND-01",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null), 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).launchDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @CaseId("TC-MDM-024")
    @DisplayName("按年份筛选：seasonYear 从 launch_date 派生，不需要冗余列")
    void filterBySeasonYear() {
        createSku("白", "L", "2026SS", "B1", LocalDate.of(2026, 3, 1));
        createSku("黑", "M", "2025AW", "A1", LocalDate.of(2025, 9, 1));

        var page = skus.page(new SkuFilter(
                null, null, null, null, null, null, null, 2025), 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).seasonYear()).isEqualTo(2025);
    }

    @Test
    @CaseId("TC-MDM-025")
    @DisplayName("仓库优先级：创建/更新生效，列表按 priority 降序；缺省为 100")
    void warehousePriority_sortsAndDefaults() {
        long normal = warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-DIM-N", "普通仓", null, true, 500)).id();
        long high = warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-DIM-H", "高优仓", null, true, 900)).id();
        long low = warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-DIM-L", "低优仓", null, true, 100)).id();
        long defaulted = warehouses.create(new WarehouseApplicationService.CreateCommand(
                "WH-DIM-D", "默认仓", null, true)).id();

        assertThat(warehouses.require(defaulted).priority()).isEqualTo(100);
        List<Long> ids = warehouses.list(true).stream()
                .map(w -> w.id()).toList();
        assertThat(ids).containsExactly(high, normal, low, defaulted);

        warehouses.update(low, new WarehouseApplicationService.UpdateCommand(
                "低优仓", null, true, 950));
        assertThat(warehouses.list(true).get(0).id()).isEqualTo(low);
    }

    @Test
    @CaseId("TC-MDM-026")
    @DisplayName("SKU 全量更新：基础字段与季节/波段同时更新，编码不变")
    void updateSku_fullCommand() {
        Sku sku = createSku("灰", "M", "2025AW", "B1", LocalDate.of(2025, 9, 1));

        Sku updated = skus.update(sku.id(), new SkuApplicationService.UpdateCommand(
                "6901000260", 20000L, true, "2027SS", "BAND-N",
                LocalDate.of(2027, 1, 1)));

        assertThat(updated.barcode()).isEqualTo("6901000260");
        assertThat(updated.retailPriceAmount()).isEqualTo(20000L);
        assertThat(updated.seasonCode()).isEqualTo("2027SS");
        assertThat(updated.bandCode()).isEqualTo("BAND-N");
        assertThat(updated.launchDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(updated.seasonYear()).isEqualTo(2027);
    }

    private Sku createSku(String color, String size, String season, String band, LocalDate launch) {
        return skus.create(new SkuApplicationService.CreateCommand(
                productId, color, size, null, 1000L, true, season, band, launch));
    }
}
