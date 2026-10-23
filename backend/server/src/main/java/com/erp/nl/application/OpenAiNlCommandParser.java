package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容端点解析器（真实 LLM，function calling）。
 *
 * <p><b>只走配置</b>：base URL / model / key 全部来自 {@code erp.nl.openai.*}，
 * key 只从环境变量 {@code ERP_NL_API_KEY} 注入，不写日志、不写仓库。
 *
 * <p><b>为什么用 JDK HttpClient 而不是第三方 SDK</b>：这里只需要一个
 * {@code POST /chat/completions}；少一个依赖就少一个供应链与版本升级面。
 * 兼容任意声称 OpenAI 接口的端点（本地 vLLM / Ollama / 云厂商），不只是官方。
 *
 * <p><b>失败语义</b>：key 缺失、网络/超时、上游非 2xx、响应不是合法 JSON
 * 一律 {@code NL_PARSER_UNAVAILABLE}（503）—— 这是基础设施问题，不是用户的指令写错了。
 * 上游没有返回 tool call 时才归为 {@code NL_COMMAND_UNRECOGNIZED}（422）。
 */
@Component
@ConditionalOnProperty(name = "erp.nl.parser", havingValue = "openai")
public class OpenAiNlCommandParser implements NlCommandParser {

    private static final String TOOLS_RESOURCE = "/nl/openai-tools.json";
    private static final String SYSTEM_PROMPT = """
            你是进销存系统的自然语言指令解析器。用户会给出一句中文或英文操作指令。
            你必须且只能调用 tools 中的一个函数来表达意图；即使参数不全也要调用最接近的函数，
            缺失的参数留空，后端会返回明确错误。
            不要编造 id：用户没有给出的 id 字段不要填。
            金额单位是“分”（整数），不要换算成元。
            对于不支持的动作（审核、入库、出货、作废、删除、天气、闲聊等），不要调用任何工具。
            """;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json;
    private final JsonNode tools;
    private final HttpClient http;

    public OpenAiNlCommandParser(
            @Value("${erp.nl.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${erp.nl.openai.api-key:}") String apiKey,
            @Value("${erp.nl.openai.model:gpt-4o-mini}") String model,
            @Value("${erp.nl.openai.timeout-seconds:30}") int timeoutSeconds,
            ObjectMapper json) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.json = json;
        this.tools = loadTools(json);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public NlCommand parse(String text) {
        if (text == null || text.isBlank()) {
            throw StubNlCommandParser.unrecognized(text);
        }
        if (apiKey.isEmpty()) {
            throw new BusinessException(ErrorCode.NL_PARSER_UNAVAILABLE,
                    "自然语言解析器不可用：未配置 ERP_NL_API_KEY（erp.nl.parser=openai）");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", text)));
        body.put("tools", tools);
        body.put("tool_choice", "auto");

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (JsonProcessingException ex) {
            throw unavailable("构造请求体失败：" + ex.getOriginalMessage());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("请求被中断");
        } catch (IOException ex) {
            throw unavailable("无法连接 LLM 端点：" + ex.getClass().getSimpleName());
        }

        if (response.statusCode() / 100 != 2) {
            throw unavailable("LLM 端点返回 HTTP %d：%s"
                    .formatted(response.statusCode(), truncate(response.body())));
        }

        JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (JsonProcessingException ex) {
            throw unavailable("LLM 响应不是合法 JSON");
        }
        JsonNode toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
        if (!toolCalls.isArray() || toolCalls.isEmpty()) {
            throw StubNlCommandParser.unrecognized(text);
        }

        JsonNode function = toolCalls.get(0).path("function");
        String name = function.path("name").asText("");
        NlIntent intent = NlIntent.fromCode(name).orElse(null);
        if (intent == null) {
            throw unavailable("LLM 返回了未知工具：" + name);
        }
        return new NlCommand(intent, argumentsOf(function.path("arguments")));
    }

    private Map<String, Object> argumentsOf(JsonNode arguments) {
        if (arguments.isMissingNode() || arguments.isNull()
                || (arguments.isTextual() && arguments.asText().isBlank())) {
            return Map.of();
        }
        try {
            if (arguments.isTextual()) {
                Map<String, Object> parsed = json.readValue(arguments.asText(),
                        new TypeReference<Map<String, Object>>() {
                        });
                return parsed == null ? Map.of() : parsed;
            }
            if (arguments.isObject()) {
                Map<String, Object> parsed = json.convertValue(arguments,
                        new TypeReference<Map<String, Object>>() {
                        });
                return parsed == null ? Map.of() : parsed;
            }
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw unavailable("LLM 返回的工具参数不是合法 JSON 对象");
        }
        throw unavailable("LLM 返回的工具参数不是 JSON 对象");
    }

    private static JsonNode loadTools(ObjectMapper json) {
        try (InputStream in = OpenAiNlCommandParser.class.getResourceAsStream(TOOLS_RESOURCE)) {
            if (in == null) {
                throw new BusinessException(ErrorCode.NL_PARSER_UNAVAILABLE,
                        "内置工具定义缺失：classpath:" + TOOLS_RESOURCE);
            }
            return json.readTree(in);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.NL_PARSER_UNAVAILABLE,
                    "读取内置工具定义失败：" + ex.getMessage());
        }
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.NL_PARSER_UNAVAILABLE,
                "自然语言解析器不可用：" + message);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 200 ? raw.substring(0, 200) + "…" : raw;
    }
}
