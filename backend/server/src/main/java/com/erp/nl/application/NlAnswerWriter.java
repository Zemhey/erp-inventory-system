package com.erp.nl.application;

import com.erp.shared.nl.NlModelConfigPort;
import com.erp.shared.nl.NlModelSettings;
import com.erp.sys.application.SqlSandbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把「查询结果」写成人话（FR-SYS-04）。
 *
 * <p><b>为什么需要第二次模型调用</b>：SQL 只有数据库读得懂。用户问"最近一个月出货单的
 * 情况"，拿到 98 行数据只会更困惑——他要的是一句"这一个月出了 98 单、合计 12.6 万，
 * 其中 3 单还没发"。这一步就是做那个翻译。
 *
 * <p><b>为什么不让 SQL 自己带结论</b>：可以让模型写 {@code SELECT count(*)}，
 * 但用户的问题往往不是单一数字，而是"怎么样""有什么问题"。
 * 把原始行交给模型组织语言，比逼它一次写对"既聚合又解释"的 SQL 稳得多。
 *
 * <p><b>失败即降级</b>：模型不可用、超时、输出为空 → 返回 {@code null}，
 * 由调用方退回机械描述（"共 N 行"）。宁可少一句人情话，也不能因为这一步挂了
 * 让整个查询失败——数据已经查到了，那是主产物。
 */
@Component
public class NlAnswerWriter {

    private static final String SYSTEM_PROMPT = """
            你是进销存系统的业务助理。下面会给你【用户问题】和【已知事实】。
            规则：
            1. 只能基于【已知事实】回答，事实里没有的绝不编造；
            2. 问什么答什么，不要复述问题、不要客套开场；
            3. 像跟同事说话，不要罗列字段、不要输出表格、JSON 或代码；
            4. 不要提「SQL」「查询语句」「数据库」「工具」这类实现细节；
            5. 问模型就答模型，问系统能力就答能做什么，问数据就答数据里的结论；
            6. 金额若明显以「分」为单位（整数很大），换算成元来说；日期说成「10 月 26 日」这种自然表达；
            7. 不超过 4 句话，不要分点。
            """;

    /** 只喂前若干行：200 行塞进去既贵又会让它去数数，而结论只需要看个大概。 */
    private static final int SAMPLE_ROWS = 20;

    private final NlModelConfigPort config;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration timeout;

    public NlAnswerWriter(
            NlModelConfigPort config,
            ObjectMapper json,
            @Value("${erp.nl.openai.timeout-seconds:30}") int timeoutSeconds) {
        this.config = config;
        this.json = json;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /**
     * @param question 用户原话
     * @param sql      实际执行的语句（只用于让模型理解口径，不会展示给用户）
     * @param result   查询结果
     * @return 自然语言回答；模型不可用时返回 {@code null}
     */
    public String summarize(String question, String sql, SqlSandbox.Result result) {
        NlModelSettings settings = config.current();
        if (!settings.llmEnabled()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("用户问题", question);
        payload.put("查询口径", sql);
        payload.put("结果行数", result.rows().size());
        payload.put("是否被截断", result.truncated());
        payload.put("列", result.columns());
        payload.put("数据样本", result.rows().stream().limit(SAMPLE_ROWS).toList());

        String userContent;
        try {
            userContent = json.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return null;
        }
        return callModel(settings, userContent);
    }

    /**
     * 用给定事实回答一个开放式问题（"你是什么模型""你对系统有什么了解"）。
     *
     * <p><b>为什么不能直接回固定文案</b>：同样的系统事实，用户可能从十个角度问
     * （你是什么、你会什么、你能帮我做什么、你和普通查询有什么不同）。
     * 固定文案会让每一种问法得到同一句话，用户立刻能感觉到"它在背稿"。
     * 把事实交给模型，问法则决定表述——这才是"理解问题后再回答"。
     *
     * <p>事实本身仍是代码里给的（模型不许编），所以不会瞎吹能力。
     */
    public String narrate(String question, String facts) {
        NlModelSettings settings = config.current();
        if (!settings.llmEnabled()) {
            return null;
        }
        String asked = (question == null || question.isBlank())
                ? "请介绍一下你自己，以及你能帮我做什么"
                : question.trim();
        return callModel(settings, "【用户问题】" + asked + "\n【已知事实】\n" + facts);
    }

    private String callModel(NlModelSettings settings, String userContent) {        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("temperature", 0.3);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userContent)));
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(stripTrailingSlash(settings.baseUrl()) + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            JsonNode content = json.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() ? null : content.asText("").trim();
            return text == null || text.isEmpty() ? null : text;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            // 组织语言失败不是致命错误：数据已经查到了，调用方会退回机械描述。
            return null;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.deepseek.com/v1";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
