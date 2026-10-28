package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.nl.NlModelConfigPort;
import com.erp.shared.nl.NlModelSettings;
import com.erp.shared.time.ClockProvider;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 「系统 → AI 接入」的业务逻辑（FR-SYS-04）。
 *
 * <p><b>三个动作</b>：看现状（脱敏）、改配置（key 有"不修改"语义）、测试连接（不落库）。
 *
 * <p><b>为什么 key 要有"不修改"语义</b>：页面回显的是掩码 {@code ••••abcd}，
 * 用户只想改模型名时不该被迫重新粘贴 key。约定：{@code apiKey} 为空 = 保持原值，
 * 等于 {@link #CLEAR_KEY} = 清空，其它 = 替换。这个约定必须只在一处实现，
 * 否则前端以为"空就是清空"和后端语义打架时，会变成"改个模型名把 key 清掉了"。
 *
 * <p><b>为什么测试连接不落库</b>：让用户"先保存再验证"等于逼他拿一个可能填错的
 * key 去覆盖当前可用的配置。先测得通，再让他保存。
 */
@Service
public class NlSettingsService {

    /** 显式清空 key 的哨兵值。用固定串而不是 null，是为了区分"没传"与"要清空"。 */
    public static final String CLEAR_KEY = "__CLEAR__";

    /** 测试连接默认用这句话：它必须能稳定命中某个工具，否则测不出 function calling 是否可用。 */
    private static final String PROBE_TEXT = "查询库存";

    private final NlModelConfigPort config;
    private final OpenAiNlCommandParser llm;
    private final NlReviewService reviews;
    private final ClockProvider clock;

    public NlSettingsService(NlModelConfigPort config, OpenAiNlCommandParser llm,
                             NlReviewService reviews, ClockProvider clock) {
        this.config = config;
        this.llm = llm;
        this.reviews = reviews;
        this.clock = clock;
    }

    /**
     * 设置页视图。
     *
     * @param llmEnabled      配置上是否具备大模型条件（选了 openai 且 key 非空）
     * @param aiReviewEnabled 复核层此刻是否真的会调模型
     * @param effectiveParser 实际生效的解析方式（与 llmEnabled 同源，单独给出便于前端直接展示）
     */
    public record View(
            String parser,
            String reviewer,
            String baseUrl,
            String model,
            String keyMasked,
            boolean hasKey,
            boolean llmEnabled,
            boolean aiReviewEnabled,
            String effectiveParser) {
    }

    /** 保存请求。apiKey 语义见类注释。 */
    public record Patch(
            String parser,
            String reviewer,
            String baseUrl,
            String model,
            String apiKey) {
    }

    /** 测试请求：字段为空则用已保存的值，便于"只换 key 先测一次"。 */
    public record TestRequest(String baseUrl, String model, String apiKey, String text) {
    }

    public record TestResult(
            boolean ok,
            long elapsedMs,
            String model,
            String detail,
            String intent) {
    }

    public View view() {
        NlModelSettings s = config.current();
        return new View(
                s.parser(),
                s.reviewer(),
                s.baseUrl(),
                s.model(),
                s.keyMasked(),
                !s.apiKey().isBlank(),
                s.llmEnabled(),
                reviews.aiReviewEnabled(),
                s.llmEnabled() ? NlModelSettings.PARSER_OPENAI : NlModelSettings.PARSER_STUB);
    }

    public View update(Patch patch) {
        NlModelSettings current = config.current();

        String parser = oneOf(patch.parser(), current.parser(),
                NlModelSettings.PARSER_STUB, NlModelSettings.PARSER_OPENAI);
        String reviewer = oneOf(patch.reviewer(), current.reviewer(),
                NlModelSettings.REVIEWER_RULE, NlModelSettings.REVIEWER_OPENAI);
        String baseUrl = stripTrailingSlash(blankTo(patch.baseUrl(), current.baseUrl()));
        String model = blankTo(patch.model(), current.model());

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "接口地址必须以 http:// 或 https:// 开头");
        }
        if (model.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "模型名不能为空");
        }

        String key;
        String incoming = patch.apiKey();
        if (incoming == null || incoming.isEmpty()) {
            key = current.apiKey();
        } else if (CLEAR_KEY.equals(incoming.trim())) {
            key = "";
        } else {
            key = incoming.trim();
        }

        config.save(new NlModelSettings(parser, reviewer, baseUrl, model, key));
        return view();
    }

    /**
     * 真发一次请求验证连通性。失败也返回 200 + {@code ok=false}：
     * 这是"用户主动发起的探测"，不是服务端故障，不该让前端把它当异常处理
     * （否则用户只能看到一句通用错误提示，看不到模型返回的具体原因）。
     */
    public TestResult test(TestRequest request) {
        NlModelSettings current = config.current();
        String baseUrl = stripTrailingSlash(
                blankTo(request == null ? null : request.baseUrl(), current.baseUrl()));
        String model = blankTo(request == null ? null : request.model(), current.model());
        String key = (request == null || request.apiKey() == null || request.apiKey().isBlank())
                ? current.apiKey()
                : request.apiKey().trim();

        if (key.isBlank()) {
            return new TestResult(false, 0, model, "还没有填写 API Key", null);
        }
        String text = (request == null || request.text() == null || request.text().isBlank())
                ? PROBE_TEXT
                : request.text().trim();

        // 用请求参数临时拼一份配置，不写库。
        NlModelSettings probe = new NlModelSettings(
                NlModelSettings.PARSER_OPENAI, NlModelSettings.REVIEWER_OPENAI,
                baseUrl, model, key);

        LocalDateTime startedAt = clock.now();
        try {
            NlCommand command = llm.parseWith(probe, text);
            long ms = Duration.between(startedAt, clock.now()).toMillis();
            return new TestResult(true, ms, model,
                    "连接正常，模型按工具定义返回了意图「" + command.intent().code() + "」",
                    command.intent().code());
        } catch (BusinessException ex) {
            long ms = Duration.between(startedAt, clock.now()).toMillis();
            return new TestResult(false, ms, model, ex.getMessage(), null);
        } catch (RuntimeException ex) {
            long ms = Duration.between(startedAt, clock.now()).toMillis();
            return new TestResult(false, ms, model,
                    "调用异常：" + ex.getClass().getSimpleName(), null);
        }
    }

    private static String oneOf(String incoming, String fallback, String... allowed) {
        String value = (incoming == null || incoming.isBlank()) ? fallback : incoming.trim().toLowerCase();
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return value;
            }
        }
        throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                "取值只能是 " + String.join(" / ", allowed) + "，收到：" + incoming);
    }

    private static String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.deepseek.com/v1";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
