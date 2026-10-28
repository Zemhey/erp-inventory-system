package com.erp.shared.nl;

/**
 * 自然语言模型接入的运行时配置（FR-SYS-04）。
 *
 * <p><b>为什么放在 {@code shared} 而不是 {@code nl}</b>：
 * 配置的存储实现必须落在 {@code com.erp.sys}（只有它能碰仓储），而架构规则
 * 规定 {@code sys} 只能依赖 {@code shared} 与 {@code iam.security} —— 若把本记录
 * 放进 {@code com.erp.nl}，sys 一侧的适配器就得反向依赖业务模块，
 * 直接触发"审计模块只依赖 shared"那条规则。放在 shared 是两边都能用的唯一位置。
 *
 * <p><b>{@code apiKey} 是明文</b>：它只在内存里流转，用于拼 HTTP 请求头。
 * 落库前由 {@code com.erp.sys.application.SecretBox} 加密；对外接口
 * （{@code GET /nl/settings}）绝不返回它，只返回 {@code keyMasked}。
 * 这个类刻意不带 {@code toString()} 默认实现之外的任何输出能力，
 * 避免某天有人 {@code log.info("{}", settings)} 把 key 打进日志。
 *
 * @param parser   {@code stub}（关键词规则）或 {@code openai}（大模型）
 * @param reviewer {@code rule}（仅确定性规则）或 {@code openai}（规则 + 模型复核）
 * @param baseUrl  OpenAI 兼容端点根地址，无尾斜杠
 * @param model    模型名
 * @param apiKey   明文 API Key；空串表示未配置
 */
public record NlModelSettings(
        String parser,
        String reviewer,
        String baseUrl,
        String model,
        String apiKey) {

    public static final String PARSER_STUB = "stub";
    public static final String PARSER_OPENAI = "openai";
    public static final String REVIEWER_RULE = "rule";
    public static final String REVIEWER_OPENAI = "openai";

    /** 兜底配置：与 V26 迁移的种子值一致，DB 缺行时也不会返回 null。 */
    public static NlModelSettings defaults() {
        return new NlModelSettings(PARSER_STUB, REVIEWER_RULE,
                "https://api.deepseek.com/v1", "deepseek-chat", "");
    }

    /** 解析器是否真的可用：选了 openai 但没 key，等同于没配。 */
    public boolean llmEnabled() {
        return PARSER_OPENAI.equalsIgnoreCase(parser) && !apiKey.isBlank();
    }

    /** 复核是否走模型：受 key 与 parser 无关，独立判断。 */
    public boolean aiReviewEnabled() {
        return REVIEWER_OPENAI.equalsIgnoreCase(reviewer) && !apiKey.isBlank();
    }

    /**
     * 掩码：只留最后 4 位。短于 8 位一律全遮 —— 因为 4 位以下露出来等于没遮。
     * 没有 key 时返回空串，由调用方决定显示"未配置"还是"未填写"。
     */
    public String keyMasked() {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String k = apiKey.trim();
        if (k.length() <= 8) {
            return "••••";
        }
        return "••••••" + k.substring(k.length() - 4);
    }
}
