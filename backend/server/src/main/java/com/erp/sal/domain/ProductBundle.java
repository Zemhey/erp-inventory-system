package com.erp.sal.domain;

import java.util.List;

/**
 * 组合商品/套装（FR-SAL-10）。
 *
 * <p><b>套装不持有库存</b>：它不是 SKU，没有 {@code inventory_balances} 行、
 * 也不会产生库存流水。可用量由各组件可用量按配比取最小值派生（INV-16）。
 *
 * @param priceAmount 展示价（分），不参与结算；结算按组件行金额重算
 */
public record ProductBundle(long id, String code, String name, long priceAmount,
                            boolean enabled, List<BundleItem> items) {

    public ProductBundle {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
