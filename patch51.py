p='backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java'
s=open(p,encoding='utf-8').read()

old="""    @ParameterizedTest(name = "[{index}] {0} / {1} / {2}")
    @MethodSource("matrix")
    @CaseId("TC-IAM-024")
    @DisplayName("★ 越权矩阵：8 资源 × 5 方式 × 3 身份，一律 404（不是 403）或 401（无 token）")
    void everyCell_mustNotLeak(Resource resource, Vector vector, Identity identity)
            throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, identity)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        if (identity == Identity.ANONYMOUS) {
            // 无 token：401 是唯一正确答案（"你该去登录"，与资源是否存在无关）
            assertThat(status)
                    .as("★ [%s/%s/匿名] 必须 401，实际 %d，响应=%s",
                            resource, vector, status, body)
                    .isEqualTo(401);
            assertThat(body).contains("IAM_UNAUTHENTICATED");
            return;
        }

        // ★ 关键断言：404 而非 403 —— 不泄露资源存在性
        assertThat(status)
                .as("★ [%s/%s/%s] 越权必须返回 404（不是 403 —— 403 会泄露资源存在性），"
                        + "实际 %d，响应=%s", resource, vector, identity, status, body)
                .isEqualTo(404);
        // 403 与 200 是两个必须明确排除的错误答案：
        //   403 → 泄露存在性；200 → 真的泄露了数据
        assertThat(status).as("越权绝不能返回 403").isNotEqualTo(403);
        assertThat(status).as("越权绝不能返回 200").isNotEqualTo(200);
        // 业务码也必须是不存在语义，而不是"无权限"
        assertThat(body)
                .as("★ 越权的响应体不能出现 IAM_FORBIDDEN（那等于承认资源存在）")
                .doesNotContain("IAM_FORBIDDEN");
    }"""

new="""    /**
     * ★ 每一格的<b>期望结果</b>（这张表本身就是需求的一部分）。
     *
     * <p>为什么要逐格写期望、而不是"统统 404"：
     * <b>商品与 SKU 是主数据，没有仓库维度</b>，数据权限对它们的正确行为是<b>不过滤</b>
     * （否则选品下拉会全空、报表明细全是 id）。把这两行也断言成 404，
     * 得到的不是"更严格的安全"，而是一张自欺欺人的表 ——
     * 它会在接口正常时红、在接口全坏时绿。
     *
     * <p>因此本矩阵有两类格子，且两类都必须在同一张表里被覆盖：
     * <ul>
     *   <li><b>受数据权限约束的 6 个资源</b> → 越权一律 <b>404</b>（不是 403）；</li>
     *   <li><b>主数据 2 个资源（商品/SKU）</b> → 已登录即 <b>200</b>，
     *       这一组同时充当<b>对照</b>：它证明"404 不是接口全坏造成的"。
     *       没有这组对照，矩阵可以在一套全坏的接口上跑绿。</li>
     *   <li>匿名的 40 格 → 一律 <b>401</b>（与服务端有没有这个资源无关）。</li>
     * </ul>
     */
    private static int expectedStatus(Resource resource, Identity identity) {
        if (identity == Identity.ANONYMOUS) {
            return 401;
        }
        return switch (resource) {
            // 主数据：无仓库维度 → 不过滤
            case PRODUCT, SKU -> 200;
            // 其余 6 个资源：受数据权限约束 → 越权即不存在
            default -> 404;
        };
    }

    @ParameterizedTest(name = "[{index}] {0} / {1} / {2}")
    @MethodSource("matrix")
    @CaseId("TC-IAM-024")
    @DisplayName("★ 越权矩阵：8 资源 × 5 方式 × 3 身份；受约束资源一律 404（不是 403），主数据作对照 200")
    void everyCell_mustNotLeak(Resource resource, Vector vector, Identity identity)
            throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, identity)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int expected = expectedStatus(resource, identity);

        if (identity == Identity.ANONYMOUS) {
            assertThat(status)
                    .as("★ [%s/%s/匿名] 必须 401，实际 %d，响应=%s",
                            resource, vector, status, body)
                    .isEqualTo(401);
            assertThat(body).contains("IAM_UNAUTHENTICATED");
            return;
        }

        assertThat(status)
                .as("★ [%s/%s/%s] 期望 %d，实际 %d，响应=%s",
                        resource, vector, identity, expected, status, body)
                .isEqualTo(expected);

        if (expected == 404) {
            // 403 与 200 是两个必须明确排除的错误答案：
            //   403 → 泄露存在性；200 → 真的泄露了数据
            assertThat(status).as("越权绝不能返回 403").isNotEqualTo(403);
            assertThat(status).as("越权绝不能返回 200").isNotEqualTo(200);
            assertThat(body)
                    .as("★ 越权的响应体不能出现 IAM_FORBIDDEN（那等于承认资源存在）")
                    .doesNotContain("IAM_FORBIDDEN");
        }
    }

    /**
     * ★ 矩阵自检：受约束的格子必须真的期望 404、对照的格子必须真的期望 200。
     *
     * <p>这条用例守的是"期望表本身被悄悄改宽"：若有人把 {@link #expectedStatus}
     * 改成一律 200（"反正测试红了很烦"），矩阵里 480 个断言会全部失去意义，
     * 而表面上仍然"全部通过"。这与 {@code ArchitectureTest} 的规则自检同一个思路。
     */
    @Test
    @CaseId("TC-IAM-043")
    @DisplayName("★ 矩阵期望表自检：6 个受约束资源必须 404、2 个主数据资源必须 200")
    void expectationTable_itselfIsChecked() {
        for (Resource r : Resource.values()) {
            for (Identity i : Identity.values()) {
                int expected = expectedStatus(r, i);
                if (i == Identity.ANONYMOUS) {
                    assertThat(expected).as("匿名一律 401").isEqualTo(401);
                } else if (r == Resource.PRODUCT || r == Resource.SKU) {
                    assertThat(expected)
                            .as("主数据 %s 对已登录用户应为 200（它没有仓库维度）", r)
                            .isEqualTo(200);
                } else {
                    assertThat(expected)
                            .as("受约束资源 %s 越权必须 404", r)
                            .isEqualTo(404);
                }
            }
        }
        // 明确数量：6 个受约束 × 5 方式 × 2 身份 = 60 个 404 格
        long denied = 0;
        long allowed = 0;
        for (Resource r : Resource.values()) {
            for (Vector v : Vector.values()) {
                for (Identity i : Identity.values()) {
                    if (expectedStatus(r, i) == 404) {
                        denied++;
                    } else if (expectedStatus(r, i) == 200) {
                        allowed++;
                    }
                }
            }
        }
        assertThat(denied).as("★ 404（越权被拒）的格子数").isEqualTo(60);
        assertThat(allowed).as("★ 200（主数据对照）的格子数").isEqualTo(20);
    }"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("matrix expectation table added")
