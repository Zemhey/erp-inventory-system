package com.erp.nl.domain;

/**
 * 人工确认凭据（FR-V4-03 · S4）。
 *
 * <p>它不是"批准书"，而是**回执**：证明"这份计划被看过、并且现在要按它执行"。
 * 凭据由服务端签发、服务端校验，绑定账号 / 意图 / 参数哈希 / 有效期，
 * 因此调用方（页面、豆包、自研脚本）谁都无法伪造，也无法"确认 A 却执行 B"。
 *
 * @param required 这个意图是否需要凭据（只读恒为 false）
 * @param token    凭据本体；{@code required=false} 时为 null
 * @param expiresAt 过期时间（ISO-8601，便于前端提示"还剩几分钟"）
 * @param reason    给调用方看的说明，Agent 可以原样转述给人
 */
public record NlConfirmation(boolean required, String token, String expiresAt, String reason) {

    public static NlConfirmation notRequired(String reason) {
        return new NlConfirmation(false, null, null, reason);
    }
}
