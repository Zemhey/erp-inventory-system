package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.PostgresTestBase;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约路径 ↔ 实现映射门禁。
 *
 * <p><b>为什么需要它</b>：M3-S1 实测发现 {@code GET /inventory/ledger} 在契约、
 * 追踪矩阵、前端 client 里都存在，唯独 Controller 没有映射 —— 请求直接 500。
 * 原有 contract-check 只校验 YAML 语法与幂等键，属于"文档自洽"，证明不了
 * <b>服务端真的提供了这个路径</b>。
 *
 * <p>本测试不解析源码文本，而是直接从 Spring 的 {@link RequestMappingHandlerMapping}
 * 取<b>实际注册</b>的处理器 —— 解析源码会漏掉组合路径、条件映射，
 * 而注册表就是运行时真正接受请求的那份事实。
 *
 * <p><b>两个方向都查</b>：
 * <ul>
 *   <li>契约 → 实现：契约写了却没实现（G1 的病）。</li>
 *   <li>实现 → 契约：实现了却没写进契约（契约不再是单一事实源）。</li>
 * </ul>
 *
 * <p>存量债务放在两张显式清单里，并<b>反向校验清单会过期</b>：
 * 一旦某条债务被补上，测试会失败并要求删除豁免。
 * 一条永远绿的豁免清单等于没有检查。
 */
@SpringBootTest
@DisplayName("契约 · 路径与实现映射一致性")
class ApiContractMappingIT extends PostgresTestBase {

    /**
     * 契约里有、但尚未实现的接口。
     * 每一条都是待办，不是永久豁免；实现后必须从清单删除。
     *
     * <p>M2 的四批交付已全部清空本清单：S1 交付 8 个 {@code /return-orders}、
     * S2 交付 3 个 {@code /settlements}、S3 交付 3 个 {@code /reports}。
     * 保持为空是 M2 的 DoD（计划书 §2.3 / §4.7）；将来若再出现"契约先行、实现后置"，
     * 新条目必须带上里程碑编号，并且由实现者本人删除。
     */
    private static final Set<String> CONTRACT_ONLY = Set.of();

    /**
     * 已实现、但契约尚未补齐的存量接口（M1 先于契约更新造成）。
     *
     * <p>这是<b>文档债</b>，不是可接受的终态：契约的承诺是"单一事实源"。
     * 任何新增的实现接口都不允许再加入本清单 —— 新增者必须直接写进契约，
     * 否则本测试红。S9 更新索引时统一清偿这批债务。
     */
    private static final Map<String, String> UNDOCUMENTED_DEBT = new LinkedHashMap<>();

    static {
        // 认证：M1 才引入 IAM，契约尚未补
        UNDOCUMENTED_DEBT.put("POST /api/v1/auth/login", "IAM 契约待补");
        UNDOCUMENTED_DEBT.put("GET /api/v1/auth/me", "IAM 契约待补");

        // 库存内部能力：M0/M1 为联调与校验暴露
        UNDOCUMENTED_DEBT.put("POST /api/v1/inventory/receipts", "内部控制端点，待决定是否对外");
        UNDOCUMENTED_DEBT.put("POST /api/v1/inventory/shipments", "内部控制端点，待决定是否对外");
        UNDOCUMENTED_DEBT.put("GET /api/v1/inventory/consistency-check", "运维/对账端点，待补契约");

        // 主数据：CRUD 的更新/停用/详情
        UNDOCUMENTED_DEBT.put("PUT /api/v1/products/{}", "商品更新，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/products/{}/disable", "商品停用，契约待补");
        UNDOCUMENTED_DEBT.put("GET /api/v1/skus/{}", "SKU 详情，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/skus/matrix", "SKU 矩阵生成，契约待补");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/skus/{}", "SKU 更新，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/skus/{}/disable", "SKU 停用，契约待补");

        // 单据工作流动作：M1 实现，契约只写了 receive/ship/void
        UNDOCUMENTED_DEBT.put("POST /api/v1/purchase-orders/{}/submit", "进货工作流，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/purchase-orders/{}/approve", "进货工作流，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/purchase-orders/{}/reject", "进货工作流，契约待补");
        UNDOCUMENTED_DEBT.put("GET /api/v1/sales-orders/{}", "出货单详情，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/{}/submit", "出货工作流，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/{}/approve", "出货工作流，契约待补");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/{}/reject", "出货工作流，契约待补");

        // ── V2-3 销售闭环（feat/v23-sal-loop）──
        // 本泳道不是契约 owner：以下条目仅为本地门禁放行，
        // 合并时由清场删除 debt 并把 .plan/V2-3-契约申请.md 落进 openapi.yaml。
        UNDOCUMENTED_DEBT.put("GET /api/v1/sales-quotes", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-quotes", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/sales-quotes/{}", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-quotes/{}/submit", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-quotes/{}/approve", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-quotes/{}/reject", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-quotes/{}/void", "V2-3 报价单，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-quotes/{}/convert", "V2-3 报价转化，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/auto-split", "V2-3 自动选仓，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/product-bundles", "V2-3 套装，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/product-bundles", "V2-3 套装，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/product-bundles/{}", "V2-3 套装，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/product-bundles/{}", "V2-3 套装，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/product-bundles/{}/disable", "V2-3 套装，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/product-bundles/{}/availability", "V2-3 套装可用量，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/shipments", "V2-3 发货作业，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/shipments/{}", "V2-3 发货作业，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/sales-orders/{}/shipments", "V2-3 发货计划，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/shipments/{}/scan", "V2-3 扫码，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/shipments/{}/confirm", "V2-3 发货确认，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/receivables", "V2-3 应收，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/receivables/summary", "V2-3 应收，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/receivables/{}", "V2-3 应收，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("POST /api/v1/customer-receipts", "V2-3 收款核销，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/customer-receipts", "V2-3 收款核销，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/customer-receipts/{}", "V2-3 收款核销，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("GET /api/v1/customers/{}/credit", "V2-3 信用，合并时由清场删除 debt 并落契约");
        UNDOCUMENTED_DEBT.put("PUT /api/v1/customers/{}/credit", "V2-3 信用，合并时由清场删除 debt 并落契约");
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("★ 契约路径全部有实现；实现路径全部有契约（存量债务清单会过期）")
    void contractAndImplementation_mustMatch() throws Exception {
        Set<String> contract = contractOperations();
        Set<String> actual = actualOperations();

        // 门禁自检：解析/枚举失败时集合会异常小，不能让它"静默通过"
        assertThat(contract)
                .as("契约解析结果过少，疑似 openapi.yaml 解析失败")
                .hasSizeGreaterThan(20);
        assertThat(actual)
                .as("Spring 映射枚举结果过少，疑似 RequestMappingHandlerMapping 注入失败")
                .hasSizeGreaterThan(30);

        Set<String> missingInImplementation = new TreeSet<>(contract);
        missingInImplementation.removeAll(actual);
        Set<String> undocumented = new TreeSet<>(actual);
        undocumented.removeAll(contract);

        // ① 契约 → 实现
        Set<String> unexpectedMissing = new TreeSet<>(missingInImplementation);
        unexpectedMissing.removeAll(CONTRACT_ONLY);
        assertThat(unexpectedMissing)
                .as("契约声明了这些操作，但 Controller 没有提供（G1 同类缺陷）")
                .isEmpty();

        // ② 实现 → 契约
        Set<String> unexpectedUndocumented = new TreeSet<>(undocumented);
        unexpectedUndocumented.removeAll(UNDOCUMENTED_DEBT.keySet());
        assertThat(unexpectedUndocumented)
                .as("这些接口已实现但没写进契约 —— 契约不是单一事实源了。"
                        + "新增接口请先改 openapi.yaml，不要往存量债务清单里加")
                .isEmpty();

        // ③ 豁免必须仍然有效：已实现/已补契约后，清单必须删条目
        for (String op : CONTRACT_ONLY) {
            assertThat(missingInImplementation)
                    .as("契约豁免已过期：%s 已经实现，请从 CONTRACT_ONLY 删除", op)
                    .contains(op);
        }
        for (Map.Entry<String, String> debt : UNDOCUMENTED_DEBT.entrySet()) {
            assertThat(undocumented)
                    .as("文档债豁免已过期：%s 已写入契约，请从 UNDOCUMENTED_DEBT 删除（原登记原因：%s）",
                            debt.getKey(), debt.getValue())
                    .contains(debt.getKey());
        }
    }

    private static Set<String> contractOperations() throws Exception {
        Path specFile = findContractFile();
        try (InputStream in = Files.newInputStream(specFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = new Yaml().load(in);
            String basePath = basePath(spec);

            @SuppressWarnings("unchecked")
            Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
            Set<String> operations = new TreeSet<>();
            for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) pathEntry.getValue();
                for (RequestMethod method : RequestMethod.values()) {
                    String key = method.name().toLowerCase();
                    if (item.containsKey(key)) {
                        operations.add(method.name() + " "
                                + normalizePath(basePath + pathEntry.getKey()));
                    }
                }
            }
            return operations;
        }
    }

    private Set<String> actualOperations() {
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.erp.")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : methods) {
                    operations.add(method.name() + " " + normalizePath(pattern));
                }
            }
        }
        return new LinkedHashSet<>(operations);
    }

    /** 把 {@code {id}} / {@code {reservationId}} 统一成 {@code {}}，只比较结构。 */
    private static String normalizePath(String path) {
        String normalized = path.replaceAll("\\{[^}]+}", "{}");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String basePath(Map<String, Object> spec) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) spec.get("servers");
        String url = servers.get(0).get("url").toString();
        String path = URI.create(url).getPath();
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * 从测试工作目录向上找契约文件：Gradle 的测试工作目录是 backend/server，
     * 而契约在仓库根的 packages/ 下。不写死相对层级，避免目录调整后静默读错文件。
     */
    private static Path findContractFile() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("packages/api-contract/openapi.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 packages/api-contract/openapi.yaml，"
                + "请确认测试是在仓库内运行的");
    }
}
