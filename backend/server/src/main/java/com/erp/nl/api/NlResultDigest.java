package com.erp.nl.api;

import com.erp.nl.domain.NlCommandResult;
import com.erp.nl.domain.NlIntent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 从执行结果里挑出"落成了哪张单"（S5/S6 的档案回填）。
 *
 * <p><b>为什么按 Map 取值而不是强类型</b>：{@link NlCommandResult#data()} 的形状由意图决定
 * （库存是列表、建单是单据对象），NL 层刻意不对它做类型收窄——一旦收窄，
 * 加一个新意图就得改这里。取不到就留空，审计里显示"-"，
 * 比因为"字段名变了"直接 500 要好得多。
 *
 * <p>放在 api 包而不是某个控制器里：两个入口（`/nl/command` 与 `/nl/pending/{id}/execute`）
 * 都要写同一条档案，逻辑只能有一份。
 */
final class NlResultDigest {

    private NlResultDigest() {
    }

    /** 意图 → 单据类型（写进档案的 doc_type）。非建单意图返回 null。 */
    static String docType(NlIntent intent) {
        return switch (intent) {
            case CREATE_PURCHASE_ORDER -> "PURCHASE";
            case CREATE_SALES_ORDER -> "SALES";
            default -> null;
        };
    }

    static Long docId(NlCommandResult result) {
        Object value = read(result, "id");
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String docNo(NlCommandResult result) {
        Object value = read(result, "orderNo");
        if (value == null) {
            value = read(result, "docNo");
        }
        return value == null ? null : String.valueOf(value);
    }

    static String toJson(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static Object read(NlCommandResult result, String key) {
        return result.data() instanceof Map<?, ?> map ? map.get(key) : null;
    }
}
