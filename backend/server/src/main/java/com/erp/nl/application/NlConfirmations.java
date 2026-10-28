package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlConfirmation;
import com.erp.nl.domain.NlReview;
import com.erp.nl.domain.NlVerdict;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务端确认闸门（FR-V4-03 · S4）：把"人点了确认"变成一张**服务端能验的回执**。
 *
 * <p><b>为什么必须挪到服务端</b>：S3 的确认只发生在页面上。Agent 如果绕过
 * {@code /nl/plan} 直接调 {@code /nl/command}，草稿照样能建出来——那种闸门只防守规矩的调用方。
 * 现在写意图缺少合法凭据一律 409，无论调用方是页面、豆包还是自研脚本。
 *
 * <p><b>凭据里绑了什么</b>：账号 + 意图 + **参数哈希** + 结论 + 过期时间，再用 HMAC-SHA256 签名。
 * 于是三种绕过都失效：
 * <ul>
 *   <li>改参数（"预览 10 件、执行 20000 件"）→ 参数哈希对不上；</li>
 *   <li>换动作（"预览查库存、执行建单"）→ 意图对不上；</li>
 *   <li>借别人的回执 → 账号对不上。</li>
 * </ul>
 *
 * <p><b>为什么不落库</b>：凭据是无状态的（签名即真伪），不需要建表、不需要清理过期记录，
 * 也不会因为"清理任务没跑"而留下可重放的旧凭据。审计表要记的是"谁确认了什么"，
 * 那是 S6 的事，不该由凭据兼职。
 *
 * <p><b>密钥</b>：优先 {@code erp.nl.confirm.secret}（环境变量 {@code ERP_NL_CONFIRM_SECRET}）。
 * 未配置时进程启动随机生成——单机开发够用，<b>多实例部署必须配置共享密钥</b>，
 * 否则 A 实例签的回执到 B 实例会被判为伪造。
 */
@Component
public class NlConfirmations {

    private static final int VERSION = 1;
    private static final String ALGORITHM = "HmacSHA256";

    private final ObjectMapper json;
    private final byte[] secret;
    private final Duration ttl;
    private final boolean required;

    public NlConfirmations(ObjectMapper json,
                           @Value("${erp.nl.confirm.secret:}") String secret,
                           @Value("${erp.nl.confirm.ttl-seconds:300}") int ttlSeconds,
                           @Value("${erp.nl.confirm.required:true}") boolean required) {
        this.json = json;
        this.ttl = Duration.ofSeconds(Math.max(30, ttlSeconds));
        this.required = required;
        this.secret = deriveKey(secret);
    }

    /** 全局开关。关掉只应出现在"临时兼容旧调用方"，生产必须是 true。 */
    public boolean required() {
        return required;
    }

    /**
     * 签发凭据。
     *
     * <p>结论为 {@code REJECT} 时**不签发**——返回"不可执行"的说明，
     * 让调用方先去改指令，而不是拿着一份被否决的计划去试运气。
     */
    public NlConfirmation issue(NlCommand command, String requestId, Long actorId, NlReview review) {
        if (!required) {
            return NlConfirmation.notRequired("确认闸门已关闭（erp.nl.confirm.required=false）");
        }
        if (!command.intent().write()) {
            return NlConfirmation.notRequired("只读操作：不产生数据变更，无需确认凭据");
        }
        if (review != null && review.verdict() == NlVerdict.REJECT) {
            return NlConfirmation.notRequired("复核结论为否决：请先修正指令，本条不可执行");
        }
        long expiresAt = Instant.now().plus(ttl).getEpochSecond();
        Payload payload = new Payload(VERSION, requestId, command.intent().code(),
                argsHash(command.arguments()), actorId == null ? 0L : actorId,
                review == null ? NlVerdict.NEED_HUMAN : review.verdict(), expiresAt);
        String body;
        try {
            body = encode(json.writeValueAsBytes(payload));
        } catch (JsonProcessingException ex) {
            return NlConfirmation.notRequired("无法生成确认凭据：" + ex.getOriginalMessage());
        }
        String token = body + "." + encode(sign(body));
        return new NlConfirmation(true, token, Instant.ofEpochSecond(expiresAt).toString(),
                "写操作：执行时请原样带回此凭据（X-Nl-Plan-Token），%d 分钟内有效"
                        .formatted(ttl.toSeconds()));
    }

    /**
     * 校验凭据并**返回它承载的事实**。任何一项不满足都抛 409，并在消息里说清"为什么不认"——
     * Agent 拿到这句话能自己决定是重新预览还是问人，而不是盲目重试。
     *
     * @return 校验通过时的凭据内容；闸门关闭或只读意图时返回 {@code null}（调用方按"无需回填"处理）
     */
    public Verified verify(String token, NlCommand command, Long actorId) {
        if (!required || !command.intent().write()) {
            return null;
        }
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.NL_CONFIRM_REQUIRED,
                    "写操作需要先预览并由人确认：请调用 POST /api/v1/nl/plan，"
                            + "把计划给人看过之后，带 X-Nl-Plan-Token 再次执行");
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 2) {
            throw invalid("确认凭据格式不正确");
        }
        byte[] signature;
        Payload payload;
        try {
            signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(parts[0]), signature)) {
                throw invalid("确认凭据签名校验失败（内容被篡改或来自其它环境）");
            }
            payload = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Payload.class);
        } catch (IllegalArgumentException | IOException ex) {
            throw invalid("确认凭据无法解析");
        }
        if (payload.v() != VERSION) {
            throw invalid("确认凭据版本不匹配，请重新预览");
        }
        if (payload.exp() < Instant.now().getEpochSecond()) {
            throw new BusinessException(ErrorCode.NL_CONFIRM_EXPIRED,
                    "确认凭据已过期（有效期 %d 秒）：请重新预览并确认".formatted(ttl.toSeconds()));
        }
        if (!payload.intent().equals(command.intent().code())) {
            throw invalid("确认凭据对应的意图是 " + payload.intent()
                    + "，与本次执行的 " + command.intent().code() + " 不一致");
        }
        if (!payload.argsHash().equals(argsHash(command.arguments()))) {
            throw invalid("指令参数与预览时不一致（预览后被改动，或解析结果发生变化）：请重新预览");
        }
        long expectedActor = actorId == null ? 0L : actorId;
        if (payload.actor() != expectedActor) {
            throw invalid("确认凭据属于其他账号，不能代用");
        }
        if (payload.verdict() == NlVerdict.REJECT) {
            throw invalid("复核结论为否决，不允许执行");
        }
        return new Verified(payload.rid(), payload.actor(), payload.verdict());
    }

    /**
     * 凭据里除了"真伪"之外还带着的事实。
     *
     * <p>`requestId` 是**计划**那一次请求的 id：执行时把它交给档案仓储，
     * 就能把"打算做什么"和"最终干了什么"接上。这是 S5 之前做不到的事
     * （那时回执只用来判真伪，不携带可用信息）。
     */
    public record Verified(String requestId, long actorId, NlVerdict verdict) {
    }

    // ─────────────────────────── 内部 ───────────────────────────

    /**
     * 参数指纹：键排序后序列化再取摘要。
     *
     * <p>数字统一成"最简十进制字符串"，让 {@code 1} 与 {@code 1.0} 得到同一指纹——
     * 不同解析器（规则 / LLM）给出的数值类型可能不同，但语义相同，不该因此判为篡改。
     */
    String argsHash(Map<String, Object> args) {
        Map<String, Object> canonical = new TreeMap<>();
        if (args != null) {
            args.forEach((key, value) -> canonical.put(key, canonicalize(value)));
        }
        try {
            String text = json.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算参数指纹", ex);
        }
    }

    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            list.forEach(item -> items.add(canonicalize(item)));
            return items;
        }
        if (value instanceof Number number && !(value instanceof Double d && d.isNaN())) {
            double asDouble = number.doubleValue();
            if (asDouble == Math.rint(asDouble) && !Double.isInfinite(asDouble)) {
                return BigDecimal.valueOf(asDouble).stripTrailingZeros().toPlainString();
            }
            return number.toString();
        }
        return value;
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC 不可用：" + ex.getMessage(), ex);
        }
    }

    private static byte[] deriveKey(String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return random;
        }
        // 无论配了多长的密钥都归一到 32 字节：短密钥直接给 HmacSHA256 会抛 InvalidKeyException
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.NL_CONFIRM_INVALID, message);
    }

    /** 凭据正文。字段名刻意短：它每次请求都要随 header 走一趟。 */
    private record Payload(int v, String rid, String intent, String argsHash,
                           long actor, NlVerdict verdict, long exp) {
    }
}
