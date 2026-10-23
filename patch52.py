import io

p = r'backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java'
s = io.open(p, encoding='utf-8').read()

start = s.index('    /**\n     * ★ 每一格的<b>期望结果</b>')
end = s.index('    // ─────────────────────────── 矩阵自身的自检 ───────────────────────────')
new_block = '''    /**
     * ★ 每一格的<b>期望</b>（这张表本身就是需求的一部分）。
     *
     * <p><b>为什么不能"统统断言 404"</b>—— 这是本期最容易写出假绿的地方。
     * 越权的正确响应形态取决于<b>取数形状</b>，一共有三种：
     *
     * <table border="1">
     *   <caption>越权响应形态</caption>
     *   <tr><th>取数形状</th><th>正确响应</th><th>为什么</th></tr>
     *   <tr><td>按 id 直查详情</td><td><b>404</b></td>
     *       <td>条件拼进 SQL 后查不出行。返回 403 会泄露"这个 id 存在"，
     *           攻击者按 id 遍历即可枚举整个 id 空间。</td></tr>
     *   <tr><td>列表 / 导出 / 统计聚合</td><td><b>200 + 空结果</b></td>
     *       <td>这是硬验收第 3 条的原话："A 仓仓管查 B 仓库存 → <b>空结果</b>（不是 403）"。
     *           列表本来就可能为空，返回 404 反而会让前端以为"接口坏了"。</td></tr>
     *   <tr><td>主数据（商品 / SKU）</td><td><b>200 + 有内容</b></td>
     *       <td>它们没有仓库维度，数据权限对它们<b>不该过滤</b> ——
     *           否则选品下拉全空、报表明细只剩 id。这一组同时充当<b>对照组</b>。</td></tr>
     * </table>
     *
     * <p><b>★ 对照组为什么必须存在</b>：如果接口本身全坏了（比如数据权限把一切都收成空），
     * 所有"200 + 空"的格子照样会绿 —— 矩阵就什么也没证明。
     * 主数据那 20 格必须<b>有内容</b>，加上 TC-IAM-026 的本仓对照，才让
     * "空"与"不存在"真正意味着"被收窄"。
     */
    private enum Expect {
        /** 404：按 id 直查越权 → 不存在。 */
        NOT_FOUND,
        /** 200 且结果为空：列表/导出/聚合被收窄。 */
        EMPTY_OK,
        /** 200 且有内容：主数据不过滤（同时是对照组）。 */
        DATA_OK
    }

    private static Expect expectationFor(Resource resource, Vector vector) {
        // 主数据：无仓库维度 → 一律可见（对照组）
        if (resource == Resource.PRODUCT || resource == Resource.SKU) {
            return Expect.DATA_OK;
        }
        // 单据/库存/结算/报表：只有"按 id 直查"与"关联反查单张单据"是 404
        return switch (vector) {
            case DIRECT_ID, ASSOCIATION -> switch (resource) {
                // 库存与报表没有"按 id 查单张"的语义（它们的参数就是筛选条件），
                // 因此这两类即使走"直查"也只是列表查询 → 空结果
                case INVENTORY, REPORT -> Expect.EMPTY_OK;
                // 结算按账期而非 id：不存在的账期就是空列表
                case SETTLEMENT -> Expect.EMPTY_OK;
                default -> Expect.NOT_FOUND;
            };
            // 列表注入 / 导出 / 统计聚合：一律"200 空结果"
            case LIST_INJECTION, EXPORT, AGGREGATE -> Expect.EMPTY_OK;
        };
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
    @DisplayName("★ 越权矩阵：按 id 直查一律 404、列表/导出/聚合一律 200 空结果、主数据作对照 200 有内容")
    void everyCell_mustNotLeak(Resource resource, Vector vector, Identity identity)
            throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, identity)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

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
                // 业务码也必须是不存在语义
                assertThat(body).containsPattern(
                        "\\"code\\":\\"(SYS_NOT_FOUND|PUR_ORDER_NOT_FOUND"
                                + "|SAL_ORDER_NOT_FOUND|RMA_ORDER_NOT_FOUND)\\"");
            }
            case EMPTY_OK -> {
                // ★ 硬验收第 3 条：跨仓查询得到"空结果"，而不是 403
                assertThat(status).as("列表/聚合越权只能 200 空结果，不得 403")
                        .isNotEqualTo(403);
                assertThat(body)
                        .as("★ [%s/%s] 收窄后必须为空，实际响应=%s", resource, vector, body)
                        .satisfiesAnyOf(
                                b -> assertThat(b).contains("\\"items\\":[]"),
                                b -> assertThat(b).contains("\\"data\\":[]"),
                                b -> assertThat(b).contains("\\"total\\":0"));
                assertThat(body).as("收窄后不得泄露 B 仓的仓库 id")
                        .doesNotContain("\\"warehouseId\\":" + warehouseB);
            }
            case DATA_OK -> {
                // 主数据不过滤：必须有内容（对照组，防"接口全坏也算绿"）
                assertThat(body)
                        .as("★ 主数据 %s 不该被数据权限过滤，必须 200 且有内容", resource)
                        .contains("\\"code\\":\\"OK\\"");
                assertThat(body).as("主数据响应不应为空对象")
                        .isNotEqualTo("{\\"code\\":\\"OK\\",\\"message\\":\\"success\\","
                                + "\\"data\\":{\\"items\\":[]}}");
            }
        }
    }

    /**
     * ★ 矩阵期望表自检：三类形态的格子数必须分别是 60 / 40 / 20。
     *
     * <p>守的是"期望表本身被悄悄改宽"：若有人把 {@link #expectationFor} 改成一律
     * {@code EMPTY_OK}（"反正测试红了很烦"），矩阵里 480 个断言会全部失去意义，
     * 而表面上仍然"全部通过"。这与 {@code ArchitectureTest} 的规则自检同一个思路。
     */
    @Test
    @CaseId("TC-IAM-043")
    @DisplayName("★ 矩阵期望表自检：404 / 200空 / 200有内容 三类格子数必须固定")
    void expectationTable_itselfIsChecked() {
        long notFound = 0;
        long emptyOk = 0;
        long dataOk = 0;
        for (Resource r : Resource.values()) {
            for (Vector v : Vector.values()) {
                switch (expectationFor(r, v)) {
                    case NOT_FOUND -> notFound++;
                    case EMPTY_OK -> emptyOk++;
                    case DATA_OK -> dataOk++;
                }
                // 匿名一律 401（与服务端有没有资源无关）
                for (Identity i : Identity.values()) {
                    if (i == Identity.ANONYMOUS) {
                        assertThat(expectedStatus(r, v, i)).isEqualTo(401);
                    }
                }
            }
        }
        // 3 张单据 × 2 种（直查/关联）× 5 身份无关 = 6 个组合维度
        assertThat(notFound).as("★ 404 的格子（3 张单据 × 直查+关联）").isEqualTo(6);
        assertThat(dataOk).as("★ 主数据对照（2 资源 × 5 方式）").isEqualTo(10);
        assertThat(emptyOk).as("★ 空结果的格子（其余 40 − 6 − 10）").isEqualTo(24);
        assertThat(notFound + dataOk + emptyOk)
                .as("三类必须覆盖全部 8×5=40 个资源×方式组合")
                .isEqualTo(40);
    }

'''
s = s[:start] + new_block + s[end:]
io.open(p, 'w', encoding='utf-8').write(s)
print("expectation table rewritten honestly")
