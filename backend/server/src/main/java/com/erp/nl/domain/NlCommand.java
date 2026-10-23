package com.erp.nl.domain;

import java.util.Map;
import java.util.Objects;

/**
 * 解析器的输出：一个意图 + 结构化参数。
 *
 * <p>参数在解析阶段只负责"形状"（key/value），业务合法性（库存是否足够、状态能否推进、
 * 账期是否锁账）一律留给既有 ApplicationService —— NL 层不复制规则。
 */
public record NlCommand(NlIntent intent, Map<String, Object> arguments) {

    public NlCommand {
        Objects.requireNonNull(intent, "intent 不能为空");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static NlCommand of(NlIntent intent) {
        return new NlCommand(intent, Map.of());
    }
}
