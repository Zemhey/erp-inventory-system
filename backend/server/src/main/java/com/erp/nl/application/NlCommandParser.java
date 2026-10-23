package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;

/**
 * 自然语言解析器（FR-SYS-04 的可注入点）。
 *
 * <p><b>为什么是接口</b>：
 * <ul>
 *   <li>测试与 CI 用 {@link StubNlCommandParser}，不依赖外网与 LLM key；</li>
 *   <li>生产可切 {@link OpenAiNlCommandParser}，业务调度代码一行不改；</li>
 *   <li>解析器只产出 {@link NlCommand}，不允许在解析阶段调用任何业务服务 ——
 *       "解析"与"执行"分开，才能在不碰业务的情况下替换模型。</li>
 * </ul>
 *
 * <p>失败语义：识别不到意图抛 {@code NL_COMMAND_UNRECOGNIZED}；
 * 意图能识别但参数形状不对抛 {@code NL_COMMAND_INVALID_ARGUMENT}；
 * 真实 LLM 端点不可用抛 {@code NL_PARSER_UNAVAILABLE}。
 */
public interface NlCommandParser {

    NlCommand parse(String text);
}
