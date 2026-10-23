package com.erp.nl.domain;

/**
 * NL 调度结果（契约 {@code NlCommandResult}）。
 *
 * <p>{@code intent} 用字符串而不是枚举：契约的取值是 {@code query_inventory} 这类小写代码，
 * 直接放枚举会被 Jackson 序列化成 {@code QUERY_INVENTORY}，与契约漂移。
 */
public record NlCommandResult(String intent, String description, boolean write, Object data) {
}
