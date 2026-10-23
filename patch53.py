import io

p = r'backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java'
s = io.open(p, encoding='utf-8').read()

anchor = '    // ─────────────────────────── 矩阵自身的自检 ───────────────────────────'
assert anchor in s

block = '''    /**
     * 把一个 (资源, 方式, 身份) 组合落成一个真实请求。
     *
     * <p><b>★ 每个分支都必须用"真实存在但属于 B 仓"的 id</b>：
     * 若用不存在的 id，404 就是"本来就没有"，测不出权限。
     * 这是越权矩阵最容易写错的地方 —— 用例绿了，但它证明的是"接口会 404"，
     * 而不是"越权被挡住"。
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            requestFor(Resource resource, Vector vector, Identity identity) {
        String token = switch (identity) {
            case OTHER_WAREHOUSE_KEEPER -> keeperToken;
            case OWN_WAREHOUSE_VIEWER -> viewerToken;
            case ANONYMOUS -> null;
        };
        var req = switch (resource) {
            case PRODUCT -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/products/" + productA);
                case LIST_INJECTION -> get("/api/v1/products")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/products").param("export", "csv");
                case ASSOCIATION -> get("/api/v1/skus")
                        .param("productId", String.valueOf(productA));
                case AGGREGATE -> get("/api/v1/products").param("count", "true");
            };
            case SKU -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/skus/" + skuA);
                case LIST_INJECTION -> get("/api/v1/skus")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/skus").param("export", "csv");
                case ASSOCIATION -> get("/api/v1/skus")
                        .param("productId", String.valueOf(productA));
                case AGGREGATE -> get("/api/v1/skus").param("count", "true");
            };
            case INVENTORY -> switch (vector) {
                // 直查：用 B 仓的 warehouseId —— 最典型的"跨仓直查"
                case DIRECT_ID -> get("/api/v1/inventory")
                        .param("skuId", String.valueOf(skuA))
                        .param("warehouseId", String.valueOf(warehouseB));
                case LIST_INJECTION -> get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(warehouseB))
                        .param("onlyAvailable", "true");
                case EXPORT -> get("/api/v1/inventory").param("export", "csv")
                        .param("warehouseId", String.valueOf(warehouseB));
                // 关联查询：从流水反查别仓的每一笔进出
                case ASSOCIATION -> get("/api/v1/inventory/ledger")
                        .param("warehouseId", String.valueOf(warehouseB));
                case AGGREGATE -> get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(warehouseB));
            };
            case PURCHASE_ORDER -> switch (vector) {
                case DIRECT_ID, ASSOCIATION -> get("/api/v1/purchase-orders/" + poB);
                case LIST_INJECTION -> get("/api/v1/purchase-orders")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/purchase-orders").param("export", "csv");
                case AGGREGATE -> get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("warehouseId", String.valueOf(warehouseB));
            };
            case SALES_ORDER -> switch (vector) {
                case DIRECT_ID, ASSOCIATION -> get("/api/v1/sales-orders/" + soB);
                case LIST_INJECTION -> get("/api/v1/sales-orders")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/sales-orders").param("export", "csv");
                case AGGREGATE -> get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("warehouseId", String.valueOf(warehouseB));
            };
            case RETURN_ORDER -> switch (vector) {
                case DIRECT_ID, ASSOCIATION -> get("/api/v1/return-orders/" + roB);
                case LIST_INJECTION -> get("/api/v1/return-orders")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/return-orders").param("export", "csv");
                case AGGREGATE -> get("/api/v1/return-orders")
                        .param("originSalesOrderId", String.valueOf(soB));
            };
            case SETTLEMENT -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/settlements/periods")
                        .param("period", "2026-03");
                case LIST_INJECTION -> get("/api/v1/settlements/periods")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/settlements/periods").param("export", "csv");
                case ASSOCIATION -> get("/api/v1/settlements/reconcile")
                        .param("period", "2026-03");
                case AGGREGATE -> get("/api/v1/settlements/periods");
            };
            case REPORT -> switch (vector) {
                case DIRECT_ID, LIST_INJECTION ->
                        get("/api/v1/reports/inventory-summary")
                                .param("period", "2026-03")
                                .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("export", "csv");
                case ASSOCIATION -> get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("warehouseId", String.valueOf(warehouseB));
                case AGGREGATE -> get("/api/v1/reports/inventory-summary")
                        .param("period", "2026-03");
            };
        };
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }

''' + anchor
s = s.replace(anchor, block, 1)
io.open(p, 'w', encoding='utf-8').write(s)
print("requestFor restored")
