package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.nl.NlModelConfigPort;
import com.erp.shared.nl.NlModelSettings;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 解析器选择器：按**当前配置**把请求分给关键词解析器或大模型解析器。
 *
 * <p><b>为什么需要这一层</b>：原来两个实现靠 {@code @ConditionalOnProperty} 在启动时二选一，
 * 意味着改配置必须重启。现在配置存在 {@code sys_config} 里、页面可改，
 * 就必须在每次请求时决定用谁 —— 条件装配做不到，只有运行时分发能做。
 * 加 {@code @Primary} 是为了让三个 Controller 仍然只注入一个 {@code NlCommandParser}，
 * 不必各自感知"有两个实现"。
 *
 * <p><b>模型不可用时为什么不悄悄回退到关键词</b>：
 * 静默回退会制造更难查的问题 —— 用户明明配了模型、看到结果也正常，
 * 却不知道这条其实是关键词猜出来的（还可能猜错）。所以这里让 503 直接抛出去，
 * 错误信息指路设置页；要临时降级，用户自己在设置页把解析方式切成关键词，
 * 这个决定是显式的、可被审计的。
 */
@Component
@Primary
public class NlCommandParserRouter implements NlCommandParser {

    private final StubNlCommandParser keyword;
    private final OpenAiNlCommandParser llm;
    private final NlModelConfigPort config;

    public NlCommandParserRouter(
            StubNlCommandParser keyword, OpenAiNlCommandParser llm, NlModelConfigPort config) {
        this.keyword = keyword;
        this.llm = llm;
        this.config = config;
    }

    @Override
    public NlCommand parse(String text) {
        NlModelSettings settings = config.current();
        if (!settings.llmEnabled()) {
            return keyword.parse(text);
        }
        try {
            return llm.parse(text);
        } catch (BusinessException ex) {
            // 模型层已经给出明确错误码，补一句"当前可怎么自救"，别让用户只看到 503。
            throw new BusinessException(ex.getCode(),
                    ex.getMessage() + "（可在「系统 → AI 接入」中检查配置，或临时切换为关键词模式）");
        }
    }

    /** 当前实际生效的解析方式，供设置页和帮助文案使用。 */
    public String effectiveParser() {
        NlModelSettings settings = config.current();
        return settings.llmEnabled() ? NlModelSettings.PARSER_OPENAI : NlModelSettings.PARSER_STUB;
    }

    /** 该错误码是否属于"解析器不可用"（供上层决定展示方式）。 */
    public static boolean isUnavailable(BusinessException ex) {
        return ex.getCode() == ErrorCode.NL_PARSER_UNAVAILABLE;
    }
}
