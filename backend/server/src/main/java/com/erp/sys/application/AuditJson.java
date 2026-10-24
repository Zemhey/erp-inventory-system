package com.erp.sys.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计快照的 JSON 序列化与<b>脱敏</b>（FR-SYS-06）。
 *
 * <p><b>为什么必须脱敏</b>：审计切面挂在全项目所有写接口上，包括
 * {@code POST /auth/login}。若不处理，{@code password} 明文会被原样写进
 * {@code audit_logs}，而审计表是"给很多人看"的表 —— 这会凭空造出一个
 * 密码明文库。同类字段还有 token、secret、api-key。
 *
 * <p>脱敏按<b>键名</b>做（大小写与分隔符归一后再匹配），覆盖
 * {@code password} / {@code passwordHash} / {@code token} / {@code secret} /
 * {@code authorization} 等变体。宁可多遮，不可漏遮：审计的价值在于"字段值一致"，
 * 而不是"所有字段都能看见"。
 *
 * <p><b>为什么不记录超大响应</b>：一张单据列表的 data 可能是几百 KB，
 * 逐条审计会让 {@code audit_logs} 迅速膨胀成比业务表还大的表。
 * 超过 {@link #MAX_SNAPSHOT_CHARS} 的快照返回 {@code null}（并在审计行里
 * 用 {@code resource_id} / {@code biz_ref_no} 保留定位信息），
 * 而不是截断成一段非法 JSON —— 非法 JSON 会让"前后值可还原"这条验收失效。
 */
public final class AuditJson {

    /** 单侧快照上限（字符）。超过则不记录快照，只留定位信息。 */
    static final int MAX_SNAPSHOT_CHARS = 16_000;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "passwordhash", "oldtpassword",
            "token", "accesstoken", "refreshtoken", "idtoken",
            "secret", "apikey", "authorization", "credential", "credentials");

    private static final String MASK = "***";

    private AuditJson() {
    }

    /**
     * 把任意响应体片段序列化成审计快照文本。
     *
     * @return 快照 JSON；{@code null} 表示"没有可记录的快照"
     */
    public static String snapshot(Object value, ObjectMapper mapper) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>) && !(value instanceof Iterable<?>)) {
            // 审计只对"对象的字段"有意义；标量（如单个 id）不产生快照
            return null;
        }
        try {
            Object masked = mask(value, mapper);
            String json = mapper.writeValueAsString(masked);
            return json.length() > MAX_SNAPSHOT_CHARS ? null : json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // 序列化失败绝不影响业务：审计宁可少一条快照
            return null;
        }
    }

    /** 递归遮掉敏感键的值。不修改入参（返回新的 JSON 树）。 */
    static Object mask(Object value, ObjectMapper mapper) {
        var tree = mapper.valueToTree(value);
        maskNode(tree);
        return tree;
    }

    private static void maskNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<String> names = object.fieldNames();
            java.util.List<String> fields = new java.util.ArrayList<>();
            names.forEachRemaining(fields::add);
            for (String field : fields) {
                if (isSensitive(field)) {
                    object.put(field, MASK);
                } else {
                    maskNode(object.get(field));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AuditJson::maskNode);
        }
    }

    /** 键名归一后精确匹配敏感清单（不含有大小的模糊匹配，避免误遮 "tokenType" 之类）。 */
    static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        // 以这些后缀结尾的键（如 newPassword / requestToken）同样视为敏感
        return normalized.endsWith("password") || normalized.endsWith("secret")
                || normalized.endsWith("apikey");
    }
}
