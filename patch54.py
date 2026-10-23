import io

p = r'backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java'
s = io.open(p, encoding='utf-8').read()

start = s.index('    /**\n     * ★ 每一格的<b>期望</b>')
end = s.index('    /**\n     * 把一个 (资源, 方式, 身份) 组合落成一个真实请求。')
new = '''    /**
     * ★ 每一格的<b>期望</b>（这张表本身就是需求的一部分，必须与接口语义严格对齐）。
     *
     * <p><b>为什么不能"统统断言 404"、也不能"统统断言空结果"</b>——这是本期
     * 最容易写出假绿/假红的地方。越权的正确响应形态取决于<b>取数形状</b>，
     * 一共有四种，且第四种最容易被漏掉：
     *
     * <table border="1">
     *   <caption>越权响应形态</caption>
     *   <tr><th>形态</th><th>取数形状</th><th>正确响应</th><th>为什么</th></tr>
     *   <tr><td>A</td><td>按 id 直查<b>单张单据</b></td><td><b>404</b></td>
     *       <td>条件拼进 SQL 后查不出行。返回 403 会泄露"这个 id 存在"，
     *           攻击者按 id 遍历即可枚举整个 id 空间。</td></tr>
     *   <tr><td>B</td><td>列表 / 导出，<b>且请求方在本仓有数据</b></td><td><b>200 + 只有本仓的行</b></td>
     *       <td>收窄的语义是"看不见别仓"，不是"什么都看不见"。
     *           断言成空结果会把正确实现判成红的 —— 也会掩盖
     *           "过滤条件误写成 1=0"这种真缺陷（那种情况下本仓数据也会消失）。</td></tr>
     *   <tr><td>C</td><td>列表 / 聚合，<b>请求方在本仓无数据</b>（本用例的库存/报表/结算）</td>
     *       <td><b>200 + 空结果</b></td>
     *       <td>硬验收第 3 条的原话："A 仓仓管查 B 仓库存 → <b>空结果</b>（不是 403）"。</td></tr>
     *   <tr><td>D</td><td>主数据（商品 / SKU）</td><td><b>200 + 有内容</b></td>
     *       <td>它们没有仓库维度，数据权限对它们<b>不该过滤</b> ——
     *           否则选品下拉全空、报表明细只剩 id。这一组同时充当<b>对照组</b>。</td></tr>
     * </table>
     *
     * <p><b>★ 本用例的关键断言是"不得出现 B 仓数据"，而不是"必须为空"。</b>
     * 前者在正确实现上恒成立；后者只在"恰好本仓也没数据"时成立 ——
     * 那属于用例造数的偶然性，不该写进断言。
     */
    private enum Expect {
        /** A：404 —— 按 id 直查越权，查不出行。 */
        NOT_FOUND,
        /** B/C：200，且响应里不得出现 B 仓数据（可能为空）。 */
        SCOPED_OK,
        /** D：200 且有内容 —— 主数据不过滤（同时是对照组）。 */
        DATA_OK
    }

    private static Expect expectationFor(Resource resource, Vector vector) {
        // D：主数据无仓库维度 → 不过滤（对照组）
        if (resource == Resource.PRODUCT || resource == Resource.SKU) {
            return Expect.DATA_OK;
        }
        // A：只有"三张单据 + 按 id 直查/关联反查单张"是 404
        if ((vector == Vector.DIRECT_ID || vector == Vector.ASSOCIATION)
                && (resource == Resource.PURCHASE_ORDER
                    || resource == Resource.SALES_ORDER
                    || resource == Resource.RETURN_ORDER)) {
            return Expect.NOT_FOUND;
        }
        // B/C：其余全部是"列表类"取数 → 200 + 只见本仓
        return Expect.SCOPED_OK;
    }

    private static int expectedStatus(Resource resource, Vector vector, Identity identity) {
        if (identity == Identity.ANONYMOUS) {
            return 401;
        }
        return expectationFor(resource, vector) == Expect.NOT_FOUND ? 404 : 200;
    }

    @ParameterizedTest(name = "[{index}] {0} / {1} / {2}")
    @MethodSource("matrix")
    @CaseId("TC-IAM-024")
    @DisplayName("★ 越权矩阵：单据直查 404、列表类 200 只见本仓（不出现 B 仓数据）、主数据作对照")
    void everyCell_mustNotLeak(Resource resource, Vector vector, Identity identity)
            throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, identity)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);

        if (identity == Identity.ANONYMOUS) {
            assertThat(status)
                    .as("★ [%s/%s/匿名] 必须 401，实际 %d，响应=%s",
                            resource, vector, status, body)
                    .isEqualTo(401);
            assertThat(body).contains("IAM_UNAUTHENTICATED");
            return;
        }

        Expect expect = expectationFor(resource, vector);
        assertThat(status)
                .as("★ [%s/%s/%s] 期望 %s（HTTP %d），实际 %d，响应=%s",
                        resource, vector, identity, expect,
                        expect == Expect.NOT_FOUND ? 404 : 200, status, body)
                .isEqualTo(expect == Expect.NOT_FOUND ? 404 : 200);

        switch (expect) {
            case NOT_FOUND -> {
                // 403 与 200 是两个必须明确排除的错误答案：
                //   403 → 泄露存在性；200 → 真的泄露了数据
                assertThat(status).as("按 id 越权绝不能返回 403（会泄露存在性）")
                        .isNotEqualTo(403);
                assertThat(body)
                        .as("★ 越权的响应体不能出现 IAM_FORBIDDEN（那等于承认资源存在）")
                        .doesNotContain("IAM_FORBIDDEN");
                assertThat(body).containsPattern(
                        "\\\\"code\\\\":\\\\"(SYS_NOT_FOUND|PUR_ORDER_NOT_FOUND"
                                + "|SAL_ORDER_NOT_FOUND|RMA_ORDER_NOT_FOUND)\\\\"");
            }
            case SCOPED_OK -> {
                assertThat(status).as("列表类越权只能 200，不得 403").isNotEqualTo(403);
                // ★ 本用例最核心的一句：**不得出现 B 仓的数据**
                assertThat(body)
                        .as("★ [%s/%s] 收窄后不得出现 B 仓（warehouseId=%d）的数据，实际=%s",
                                resource, vector, warehouseB, body)
                        .doesNotContain("\\\\"warehouseId\\\\":" + warehouseB);
                // 空结果与"只有本仓的行"都是合法的（见 Expect 的 B/C 两种形态）
            }
            case DATA_OK -> {
                assertThat(body)
                        .as("★ 主数据 %s 不该被数据权限过滤，必须 200 且有内容", resource)
                        .contains("\\\\"code\\\\":\\\\"OK\\\\"");
                assertThat(body).as("主数据响应不应为空列表（对照组必须真有内容）")
                        .doesNotContain("\\\\"items\\\\":[]");
                assertThat(body).doesNotContain("\\\\"data\\\\":[]");
            }
        }
    }

    /**
     * ★ 矩阵期望表自检：三类形态的格子数必须固定。
     *
     * <p>守的是"期望表本身被悄悄改宽"：若有人把 {@link #expectationFor} 改成一律
     * {@link Expect#SCOPED_OK}（"反正测试红了很烦"），"单据直查必须 404"这个
     * 面试重点就会静默消失，而矩阵表面仍然全绿。
     * 这与 {@code ArchitectureTest} 的规则自检同一个思路。
     */
    @Test
    @CaseId("TC-IAM-043")
    @DisplayName("★ 矩阵期望表自检：404 / 只见本仓 / 主数据对照 三类格子数必须固定")
    void expectationTable_itselfIsChecked() {
        long notFound = 0;
        long scoped = 0;
        long dataOk = 0;
        for (Resource r : Resource.values()) {
            for (Vector v : Vector.values()) {
                switch (expectationFor(r, v)) {
                    case NOT_FOUND -> notFound++;
                    case SCOPED_OK -> scoped++;
                    case DATA_OK -> dataOk++;
                }
                assertThat(expectedStatus(r, v, Identity.ANONYMOUS))
                        .as("匿名一律 401（与资源是否存在无关）").isEqualTo(401);
            }
        }
        // 3 张单据 × (直查 + 关联) = 6
        assertThat(notFound).as("★ 404 的格子（3 张单据 × 直查+关联）").isEqualTo(6);
        // 2 个主数据 × 5 种方式 = 10
        assertThat(dataOk).as("★ 主数据对照（2 资源 × 5 方式）").isEqualTo(10);
        assertThat(scoped).as("★ 只见本仓的格子（40 − 6 − 10）").isEqualTo(24);
        assertThat(notFound + dataOk + scoped)
                .as("三类必须覆盖全部 8×5=40 个资源×方式组合")
                .isEqualTo(40);
    }

'''
s = s[:start] + new + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print("expectation table v3 written")
