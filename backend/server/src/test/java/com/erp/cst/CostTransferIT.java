package com.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.cst.application.CostApplicationService;
import com.erp.cst.domain.CostLayer;
import com.erp.cst.repository.CostLayerRepository;
import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService.TransferLineCommand;
import com.erp.inv.domain.InventoryTransfer;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 成本引擎 × V2-1 真实调拨链路（TC-CST-062）。
 *
 * <p>用真实的 {@code InventoryOperationsApplicationService} 建单/到货（两段式、
 * 调拨行 unitCostAmount 为空），再用成本引擎重算，验证：
 * 调入成本 = 调出成本，两仓合计守恒；到货后再出库仍按调拨带入的成本结转。
 */
@SpringBootTest
@DisplayName("成本 · 调拨守恒（V2-1 真实链路）")
class CostTransferIT extends PostgresTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private InventoryOperationsApplicationService operations;
    @Autowired
    private CostApplicationService costs;
    @Autowired
    private CostLayerRepository layers;

    private TestDataFactory data;
    private long warehouseA;
    private long warehouseB;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseA = data.warehouse("WH-CST-TR-A");
        warehouseB = data.warehouse("WH-CST-TR-B");
        long productId = data.product("P-CST-TR", "调拨成本商品");
        skuId = data.sku(productId, "灰色", "M");
    }

    @Test
    @CaseId("TC-CST-062")
    @DisplayName("真实调拨（无 unitCostAmount）：调入成本=调出成本，两仓合计守恒，调拨后出库成本正确")
    void realTransferPairsCostAndConserves() {
        inventory.receiveIn(skuId, warehouseA, "B1", null, null, 10,
                "PURCHASE_IN", "PO-CST-TR-1", null, 1000L, "req-cst-tr-stock");

        InventoryTransfer transfer = operations.createTransfer(warehouseA, warehouseB,
                null, null,
                List.of(new TransferLineCommand(skuId, "B1", 4, null)),
                "req-cst-tr-create").transfer();
        operations.receiveTransfer(transfer.id(), "req-cst-tr-receive");

        costs.recalculate();

        CostLayer out = layer("TRANSFER_OUT");
        CostLayer in = layer("TRANSFER_IN");
        assertThat(out.totalCostAmount()).isEqualTo(4_000);
        assertThat(in.totalCostAmount()).isEqualTo(4_000);
        assertThat(in.unitCostAmount()).isEqualTo(1000);
        assertThat(lastPoolCost(warehouseA)).isEqualTo(6_000);
        assertThat(lastPoolCost(warehouseB)).isEqualTo(4_000);
        assertThat(layers.endingCost()).isEqualTo(10_000);

        // 到货后再从目的仓出库：成本应沿用调拨带入的 1000/件，而不是目的仓原来的 0
        CostTestSupport.ledger(jdbc, skuId, warehouseB, "B1", -2, "SALES_OUT",
                "SO-AFTER-TRANSFER", null, OffsetDateTime.now().plusMinutes(5));
        costs.recalculate();

        CostLayer sale = layer("SALES_OUT");
        assertThat(sale.unitCostAmount()).isEqualTo(1000);
        assertThat(sale.totalCostAmount()).isEqualTo(2_000);
        assertThat(lastPoolCost(warehouseB)).isEqualTo(2_000);
        assertThat(lastPoolCost(warehouseA)).isEqualTo(6_000);
        assertThat(layers.endingCost()).isEqualTo(8_000);
    }

    private CostLayer layer(String bizType) {
        return layers.findAll().stream()
                .filter(layer -> layer.bizType().equals(bizType))
                .findFirst().orElseThrow();
    }

    private long lastPoolCost(long warehouseId) {
        List<CostLayer> pool = layers.findByPool(skuId, warehouseId);
        return pool.get(pool.size() - 1).poolCostAfter();
    }
}
