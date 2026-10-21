package com.erp.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应包装（契约：{@code ApiResponse} / {@code ApiPageResponse}）。
 *
 * <p>为什么集中在这里：M0 的 {@code InventoryController} 自己写了一个私有 {@code ok()}，
 * M1 再接三四个控制器就会出现四份略有差异的包装 —— 有的带 traceId、有的不带。
 * 契约只有一份，包装也只有一份。
 */
public final class ApiResponses {

    private ApiResponses() {
    }

    /** {@code { code:"OK", message:"success", data }}。 */
    public static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "OK");
        m.put("message", "success");
        m.put("data", data);
        return m;
    }

    /** 分页响应：{@code { code, message, data:{ items, total, page, size } }}。 */
    public static Map<String, Object> page(PageResult<?> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return ok(data);
    }
}
