package com.erp.nl.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 自然语言意图（FR-SYS-04）。
 *
 * <p>{@link #code()} 同时是 OpenAI tool 名称与契约 {@code NlCommandResult.intent} 的取值，
 * 三处必须逐字一致 —— 否则"模型返回了工具、后端却不认识"这类问题只能在上线后暴露。
 *
 * <p>{@link #write()} 只标记"会产生写操作"，具体权限与业务约束仍由调度器
 * 调用既有 ApplicationService 决定；本枚举不复制任何业务规则。
 */
public enum NlIntent {

    HELP("help", false),
    QUERY_INVENTORY("query_inventory", false),
    LIST_DOCUMENTS("list_documents", false),
    GET_DOCUMENT("get_document", false),
    INVENTORY_SUMMARY("inventory_summary", false),
    QUERY_MASTER_DATA("query_master_data", false),
    CREATE_PURCHASE_ORDER("create_purchase_order", true),
    CREATE_SALES_ORDER("create_sales_order", true);

    private final String code;
    private final boolean write;

    NlIntent(String code, boolean write) {
        this.code = code;
        this.write = write;
    }

    public String code() {
        return code;
    }

    public boolean write() {
        return write;
    }

    /** 按 tool 名称 / 意图代码查找；大小写与连字符不敏感（{@code query-inventory} 也可）。 */
    public static Optional<NlIntent> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (NlIntent intent : values()) {
            if (intent.code.equals(normalized)) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }

    public static List<String> allCodes() {
        return java.util.Arrays.stream(values()).map(NlIntent::code).toList();
    }
}
