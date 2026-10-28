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

    /**
     * 自述：回答"你是什么模型 / 你是谁 / 现在用的是谁"。
     *
     * <p><b>为什么这也要占一个意图</b>：早期没有它时，用户问一句"你是什么模型"，
     * 模型不返回任何工具调用，后端判成"无法识别的指令"直接报错 ——
     * 一个接了真模型的系统，连自己是什么都答不上来，很难让人相信它真的在调模型。
     *
     * <p>只返回模型名与生效方式，**不含接口地址与密钥**：模型名是用户自己的配置，
     * 端点地址属于内部信息，想看完整配置得去「系统 → AI 接入」（需要 {@code sys:config}）。
     */
    WHO_AM_I("who_am_i", false),

    QUERY_INVENTORY("query_inventory", false),
    LIST_DOCUMENTS("list_documents", false),
    GET_DOCUMENT("get_document", false),
    INVENTORY_SUMMARY("inventory_summary", false),
    QUERY_MASTER_DATA("query_master_data", false),

    /**
     * 自定义查询（text-to-SQL）：模型自己写一条只读 SELECT。
     *
     * <p><b>为什么固定工具不够</b>：{@link #LIST_DOCUMENTS} 这类工具的参数是写死的，
     * 只能表达"设计者事先想到的问法"。用户问"哪个客户贡献了最多毛利""上周退货率是多少"，
     * 没有任何一个工具装得下。加工具等于加一种问法，永远追不上用户。
     *
     * <p><b>为什么它不是"更危险"</b>：仍然只读。语句在白名单视图上执行
     * （{@code ai} schema，已按租户过滤），有 8 秒超时与 200 行上限，
     * 且写关键字与系统表在入口就被拒。风险面是"查得慢/查得杂"，不是"改错数据"。
     */
    SQL_QUERY("sql_query", false),

    /**
     * 目标级意图（composite）：一次调用内部组合"可用量 + 在途采购 + 上下限 + 补货公式"。
     *
     * <p>与其余意图的区别不是"更复杂"，而是**表达的单位不同**：别的意图对应"一次操作"，
     * 它对应"一个目标"。用户说"该补什么货"，原本需要自己查库存、再查在途、再算差额，
     * 现在一句话拿到带依据的建议清单。
     *
     * <p>仍然只读：不建单、不预占，因此风险为零；建议清单要不要变成采购草稿，
     * 由人在页面上决定（这也是它敢做成一句话的原因）。
     */
    REPLENISH_ADVICE("replenish_advice", false),

    /**
     * 提交单据（FR-V4-03 · S7 受限 L2 的第一档）。
     *
     * <p><b>为什么只开放"提交"，不开放"审核"</b>：这两件事在业务上不是一回事。
     * 提交是**跑腿**——把一张草稿送到它该去的地方（待审），人可以随时撤回重来；
     * 审核是**签字**——决定它过不过，签了就把责任落在这个人头上。
     * AI 可以替人跑腿，不该替人签字。同理入库/出库动的是账和货，必须人看着实物做。
     *
     * <p>仍然算写操作：需要 {@code pur:submit} / {@code sal:submit} 权限，
     * 必须走「计划 → 复核 → 人确认」，并且全程留审计。
     */
    SUBMIT_DOCUMENT("submit_document", true),

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
