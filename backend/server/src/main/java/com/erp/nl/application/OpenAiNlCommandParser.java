package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.nl.NlModelConfigPort;
import com.erp.shared.nl.NlModelSettings;
import com.erp.shared.time.ClockProvider;
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
public class OpenAiNlCommandParser implements NlCommandParser {

    private static final String TOOLS_RESOURCE = "/nl/openai-tools.json";
    private static final String SCHEMA_RESOURCE = "/nl/db-schema.txt";
    private static final String SYSTEM_PROMPT = """
            你是进销存系统的自然语言指令解析器。用户会给出一句中文或英文操作指令。
            你必须且只能调用 tools 中的一个函数来表达意图；即使参数不全也要调用最接近的函数，
            缺失的参数留空，后端会返回明确错误。
            不要编造 id：用户没有给出的 id 字段不要填。
            金额单位是“分”（整数），不要换算成元。
            对于不支持的动作（审核、入库、出货、作废、删除、天气、闲聊等），不要调用任何工具。
            """;

    private final NlModelConfigPort config;
    private final ClockProvider clock;
    private final Duration timeout;
    private final ObjectMapper json;
    private final JsonNode tools;
    private final HttpClient http;

    public OpenAiNlCommandParser(
            NlModelConfigPort config,
            ClockProvider clock,
            @Value("${erp.nl.openai.timeout-seconds:30}") int timeoutSeconds,
            ObjectMapper json) {
        this.config = config;
        this.clock = clock;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.json = json;
        this.tools = loadTools(json);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /**
     * 系统提示 + **当前日期**。
     *
     * <p>日期必须每次现拼、不能写进常量：模型本身不知道今天几号，
     * 少了这一句，"最近一个月""上个月"这类相对时间它只能丢掉或者瞎猜 ——
     * 表现出来就是"我说了时间，它把所有数据都列出来了"。
     * 日期来自 {@link ClockProvider}，与业务时间同源，测试里也能固定。
     */
    private String systemPrompt() {
        return SYSTEM_PROMPT
                + "\n今天是 " + clock.now().toLocalDate()
                + "。用户说「最近一个月/上个月/本周/近7天」这类相对时间时，"
                + "先按今天换算出具体的 dateFrom / dateTo 再传参，不要忽略时间条件。\n"
                + "用户说「按时间排序/按时间顺序」但没说方向时，用 desc（最新的在前）；"
                + "只有明确说「从早到晚/正序/最早在前」才用 asc。\n"
                + "遇到「你是什么模型/你是谁/你能做什么」这类关于你自己的问题，"
                + "调用 who_am_i 工具，不要回答不了就报错。\n"
                + """
                
                【什么时候用 sql_query】
                固定工具（list_documents 等）的参数是写死的，适合"查某类单据/某类主数据"这种标准问法。
                凡是出现下面这些情况，改用 sql_query 自己写一条 SELECT，不要把问题硬塞进固定工具：
                  · 需要跨表关联（单据 + 客户 + 商品）；
                  · 需要分组、排行、占比、同比、平均、最大最小；
                  · 需要同时满足多个条件的组合筛选；
                  · 用户问的是「总结/分析/有什么问题/情况怎么样」——先查出支撑结论的数据；
                  · 固定工具的参数表达不了用户的条件。
                用 sql_query 时，question 参数必须原样带上用户的话 —— 查完要据此组织回答。
                
                """ + schema()
                + "\n";
    }

    /**
     * 可查询的数据结构（classpath:db-schema.txt）。
     *
     * <p>把它交给模型是<b>给能力</b>，不是加提示词：没有表结构，它写不出合法的 SQL，
     * 只能退回固定工具；有了它，任何组合问法都能落成一次查询。
     * 读取失败时返回空串 —— 此时模型仍可走固定工具，不会整个不可用。
     */
    private static String schema() {
        try (InputStream in = OpenAiNlCommandParser.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    /** 是否具备调用条件（页面里选了 openai 且填了 key）。 */
    public boolean available() {
        return config.current().llmEnabled();
    }

    @Override
    public NlCommand parse(String text) {
        return parseWith(config.current(), text);
    }

    /**
     * 用**给定配置**解析一次，不读库。
     *
     * <p>设置页的"测试连接"走这里：用户填了新 key 但还没保存时，
     * 也得能先验证通不通 —— 让他"先保存再看是否可用"等于逼他冒险覆盖掉旧的可用配置。
     *
     * @throws BusinessException 配置不全或上游不可用时（错误码语义与 {@link #parse} 一致）
     */
    public NlCommand parseWith(NlModelSettings settings, String text) {
        if (text == null || text.isBlank()) {
            throw StubNlCommandParser.unrecognized(text);
        }
        if (settings.apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.NL_PARSER_UNAVAILABLE,
                    "自然语言解析器不可用：未配置模型 API Key（可在「系统 → AI 接入」中填写）");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", text)));
        body.put("tools", tools);
        body.put("tool_choice", "auto");

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(
                            URI.create(stripTrailingSlash(settings.baseUrl()) + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + settings.apiKey())
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
