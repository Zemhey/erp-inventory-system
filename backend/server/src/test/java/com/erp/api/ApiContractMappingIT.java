package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import java.io.InputStream;
import java.lang.reflect.Parameter;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
     * 已实现、但契约尚未补齐的存量接口。
     *
     * <p>这是<b>文档债</b>，不是可接受的终态：契约的承诺是"单一事实源"。
     * 任何新增的实现接口都不允许加入本清单 —— 新增者必须直接写进契约。
     *
     * <p><b>空集是 ADR-0008 的 DoD，由 {@code TC-API-095} 直接断言</b>，
     * 而不是靠约定：
     * <ul>
     *   <li>2026-10-24「契约清偿」批清零 43 条（legacy 17 + V2-6 26），契约 90 → 133；
     *       明细见 {@code .plan/契约清偿-报告.md}。</li>
     *   <li>同日之后的 V2-3 销售闭环曾临时登记 28 条（时序问题：该泳道在清偿之后才合并），
     *       已由「契约清偿 2」全部写入契约并整段删除，契约 133 → 161；
     *       对照表见 {@code .plan/V2-C2-契约清偿2.md}。</li>
     * </ul>
     *
     * <p>这 71 条的成因都一样："一次登记看起来合理，5 条泳道各自登记 = 欠账复利"。
     * 所以这里刻意不留任何豁免，也不再保留"分段注释"的位置。
     */
    private static final Map<String, String> UNDOCUMENTED_DEBT = new LinkedHashMap<>();

    /**
     * 「契约清偿 2」批写入契约的 V2-3 销售闭环 28 个操作（路径参数统一成 {@code {}}）。
     *
     * <p>把它们显式钉在这里，是为了让"契约覆盖实现"这件事<b>可回归</b>：
     * 只要有人从 {@code openapi.yaml} 里删掉这 28 条中的任何一条，
     * 而 Controller 里还留着映射，{@code contractAndImplementation_mustMatch} 会红；
     * 但如果连实现一起删了，就没人报警 —— 本集合补上这个缺口。
     */
    private static final Set<String> V23_OPERATIONS = Set.of(
            "GET /api/v1/sales-quotes",
            "POST /api/v1/sales-quotes",
            "GET /api/v1/sales-quotes/{}",
            "POST /api/v1/sales-quotes/{}/submit",
            "POST /api/v1/sales-quotes/{}/approve",
            "POST /api/v1/sales-quotes/{}/reject",
            "POST /api/v1/sales-quotes/{}/void",
            "POST /api/v1/sales-quotes/{}/convert",
            "POST /api/v1/sales-orders/auto-split",
            "GET /api/v1/product-bundles",
            "POST /api/v1/product-bundles",
            "GET /api/v1/product-bundles/{}",
            "PUT /api/v1/product-bundles/{}",
            "POST /api/v1/product-bundles/{}/disable",
            "GET /api/v1/product-bundles/{}/availability",
            "GET /api/v1/shipments",
            "GET /api/v1/shipments/{}",
            "POST /api/v1/sales-orders/{}/shipments",
            "POST /api/v1/shipments/{}/scan",
            "POST /api/v1/shipments/{}/confirm",
            "GET /api/v1/receivables",
            "GET /api/v1/receivables/summary",
            "GET /api/v1/receivables/{}",
            "POST /api/v1/customer-receipts",
            "GET /api/v1/customer-receipts",
            "GET /api/v1/customer-receipts/{}",
            "GET /api/v1/customers/{}/credit",
            "PUT /api/v1/customers/{}/credit");

    /**
     * V2-3 写操作中<b>真正消费</b> {@code X-Request-Id} 的那 6 个。
     * 其余写操作（状态迁移型与纯开关）的幂等由状态机/唯一约束/DB 条件 UPDATE 保证，
     * 契约不得声明幂等键 —— 声明了就是承诺实现做不到的事。
     */
    private static final Set<String> V23_IDEMPOTENT_OPERATIONS = Set.of(
            "POST /api/v1/sales-quotes",
            "POST /api/v1/sales-quotes/{}/convert",
            "POST /api/v1/sales-orders/auto-split",
            "POST /api/v1/sales-orders/{}/shipments",
            "POST /api/v1/shipments/{}/confirm",
            "POST /api/v1/customer-receipts");

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

    @Test
    @CaseId("TC-API-095")
    @DisplayName("★ 文档债豁免集保持空集；V2-3 的 28 个操作确实写在契约里且结构完整（契约清偿 2 的 DoD）")
    void debtExemption_mustStayEmpty() throws Exception {
        assertThat(UNDOCUMENTED_DEBT)
                .as("UNDOCUMENTED_DEBT 必须是空集（ADR-0008）：实现接口直接写进 openapi.yaml，"
                        + "不允许再登记豁免。历史上累计的 71 条已全部清偿")
                .isEmpty();
        assertThat(CONTRACT_ONLY)
                .as("CONTRACT_ONLY 必须是空集：契约不得承诺实现没有的路径")
                .isEmpty();
        assertThat(V23_OPERATIONS)
                .as("本批登记的操作数就是 28（与契约 133 → 161 的增量一致）")
                .hasSize(28);

        Map<String, Map<String, Object>> contract = contractOperationIndex(loadSpec());
        for (String operation : V23_OPERATIONS) {
            Map<String, Object> definition = contract.get(operation);
            assertThat(definition)
                    .as("契约缺少 V2-3 操作：%s", operation)
                    .isNotNull();
            assertThat(definition.get("operationId"))
                    .as("%s 缺 operationId（TS SDK 生成与追踪矩阵都以它为键）", operation)
                    .isNotNull();
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) definition.get("tags");
            assertThat(tags)
                    .as("%s 缺 tags（契约按模块分组）", operation)
                    .isNotNull()
                    .isNotEmpty();
            @SuppressWarnings("unchecked")
            Map<String, Object> responses = (Map<String, Object>) definition.get("responses");
            assertThat(responses)
                    .as("%s 缺 responses", operation)
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    @CaseId("TC-API-096")
    @DisplayName("★ V2-3 写操作的 X-Request-Id：契约声明 ⇔ 实现消费，两个方向都必须对上")
    void idempotencyKey_declarationMatchesImplementation() throws Exception {
        Map<String, Map<String, Object>> contract = contractOperationIndex(loadSpec());
        Map<String, Boolean> implemented = requestIdConsumptionByOperation();

        // ★ 比对范围刻意只圈本批 28 条，不是全契约：
        //   主数据 8 个写端点（warehouses/suppliers/customers/products/skus）的
        //   "契约声明了但实现不消费" 属于 V2-C1 泳道（它们正在改实现去消费 X-Request-Id）。
        //   把它们纳入会让本泳道的门禁替别人红灯 —— 与 contract-check.py 的残留同理。
        for (String operation : V23_OPERATIONS) {
            if (!operation.startsWith("POST ") && !operation.startsWith("PUT ")) {
                continue;
            }
            Map<String, Object> definition = contract.get(operation);
            assertThat(definition).as("契约缺少该写操作：%s", operation).isNotNull();
            Boolean consumes = implemented.get(operation);
            assertThat(consumes)
                    .as("实现里没有这个映射：%s（契约写了却没人接）", operation)
                    .isNotNull();
            boolean declared = declaresRequestId(definition);
            assertThat(declared)
                    .as("%s：契约%s声明 X-Request-Id，实现%s消费它 —— 两边必须一致。"
                            + "声明了却不消费 = 契约承诺实现做不到的事；消费了却不声明 = 幂等键漏写",
                            operation, declared ? "" : "未", consumes ? "" : "未")
                    .isEqualTo(consumes);
        }

        Set<String> actuallyConsuming = new TreeSet<>();
        for (String operation : V23_OPERATIONS) {
            if (Boolean.TRUE.equals(implemented.get(operation))) {
                actuallyConsuming.add(operation);
            }
        }
        assertThat(actuallyConsuming)
                .as("V2-3 里消费 X-Request-Id 的写操作集合变了："
                        + "请同步改契约声明与 V23_IDEMPOTENT_OPERATIONS（这张清单本身也会过期）")
                .isEqualTo(V23_IDEMPOTENT_OPERATIONS);
    }

    /**
     * 从 {@link RequestMappingHandlerMapping} 取每个操作**是否真的**声明了
     * {@code @RequestHeader("X-Request-Id")}。
     *
     * <p>读注册表而不是读源码文本：{@code scripts/contract-check.py} 用正则扫签名，
     * 已经被相邻方法的注解串味过一次（见其文件头说明）。这里拿的是编译后的方法参数，
     * 不存在窗口误判。
     */
    private Map<String, Boolean> requestIdConsumptionByOperation() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.erp.")) {
                continue;
            }
            boolean consumes = consumesRequestId(handler);
            RequestMappingInfo info = entry.getKey();
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    result.put(method.name() + " " + normalizePath(pattern), consumes);
                }
            }
        }
        return result;
    }

    private static boolean consumesRequestId(HandlerMethod handler) {
        for (Parameter parameter : handler.getMethod().getParameters()) {
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header == null) {
                continue;
            }
            String name = header.value().isEmpty() ? header.name() : header.value();
            if ("X-Request-Id".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 该契约操作是否声明了 {@code X-Request-Id} 请求头（{@code $ref} 或就地定义都算）。 */
    @SuppressWarnings("unchecked")
    private static boolean declaresRequestId(Map<String, Object> operation) {
        List<Object> parameters = (List<Object>) operation.getOrDefault("parameters", List.of());
        for (Object raw : parameters) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> parameter = (Map<String, Object>) raw;
            if ("#/components/parameters/RequestId".equals(parameter.get("$ref"))) {
                return true;
            }
            if ("X-Request-Id".equals(parameter.get("name"))
                    && "header".equals(parameter.get("in"))) {
                return true;
            }
        }
        return false;
    }

    /** 规范化操作键（如 {@code GET /api/v1/shipments/{}}）→ 该操作的契约定义。 */
    private static Map<String, Map<String, Object>> contractOperationIndex(Map<String, Object> spec) {
        String basePath = basePath(spec);
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> operationEntry : item.entrySet()) {
                String method = operationEntry.getKey().toUpperCase();
                if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> operation = (Map<String, Object>) operationEntry.getValue();
                index.put(method + " " + normalizePath(basePath + pathEntry.getKey()), operation);
            }
        }
        return index;
    }

    private static Map<String, Object> loadSpec() throws Exception {
        Path specFile = findContractFile();
        try (InputStream in = Files.newInputStream(specFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = new Yaml().load(in);
            return spec;
        }
    }

    private static Set<String> contractOperations() throws Exception {
        Map<String, Object> spec = loadSpec();
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
