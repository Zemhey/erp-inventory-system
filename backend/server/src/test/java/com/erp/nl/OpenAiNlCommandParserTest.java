package com.erp.nl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.erp.nl.application.OpenAiNlCommandParser;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OpenAI 兼容解析器单测：用本地假服务器，不访问外网、不需要真实 key。
 *
 * <p>验证的是<b>协议适配层</b>：请求是否带 Authorization/tools/tool_choice，
 * tool_call 是否被正确映射为意图与参数，以及各类基础设施故障是否被归到 503
 * 而不是 400/422（后者会让用户以为是自己指令写错了）。
 */
@DisplayName("NL · OpenAI 兼容解析器（本地假服务器）")
class OpenAiNlCommandParserTest {

    private final ObjectMapper json = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @CaseId("TC-NL-004")
    @DisplayName("★ tool_call 映射为意图与参数；请求带 Authorization、tools 与 tool_choice=auto")
    void mapsToolCallToCommand() throws Exception {
        String baseUrl = startServer("""
                {"choices":[{"message":{"tool_calls":[{"function":{
                  "name":"query_inventory",
                  "arguments":"{\\"skuId\\":12,\\"warehouseId\\":3}"}}]}}]}
                """);
        OpenAiNlCommandParser parser = new OpenAiNlCommandParser(
                baseUrl, "test-key", "fake-model", 5, json);

        NlCommand command = parser.parse("帮我看看 12 号 SKU 在 3 号仓还有多少");

        assertThat(command.intent()).isEqualTo(NlIntent.QUERY_INVENTORY);
        assertThat(command.arguments()).containsEntry("skuId", 12).containsEntry("warehouseId", 3);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get())
                .as("请求必须具备 function calling 的三件套，否则真实模型只会返回聊天文本")
                .contains("\"tools\"")
                .contains("\"tool_choice\":\"auto\"")
                .contains("\"query_inventory\"")
                .contains("\"model\":\"fake-model\"");
    }

    @Test
    @CaseId("TC-NL-005")
    @DisplayName("★ 无 tool call → 422；上游 500 → 503；缺 key → 503 且提示 ERP_NL_API_KEY")
    void mapsInfrastructureFailures() throws Exception {
        String baseUrl = startServer("{\"choices\":[{\"message\":{\"content\":\"我不知道\"}}]}");
        OpenAiNlCommandParser parser = new OpenAiNlCommandParser(
                baseUrl, "test-key", "fake-model", 5, json);

        BusinessException noToolCall = catchThrowableOfType(
                () -> parser.parse("今天天气怎么样"), BusinessException.class);
        assertThat(noToolCall.getCode()).isEqualTo(ErrorCode.NL_COMMAND_UNRECOGNIZED);

        responseStatus.set(500);
        responseBody.set("{\"error\":\"boom\"}");
        BusinessException upstream = catchThrowableOfType(
                () -> parser.parse("查询库存"), BusinessException.class);
        assertThat(upstream.getCode()).isEqualTo(ErrorCode.NL_PARSER_UNAVAILABLE);
        assertThat(upstream.getMessage()).contains("HTTP 500").doesNotContain("test-key");

        OpenAiNlCommandParser noKey = new OpenAiNlCommandParser(
                baseUrl, "", "fake-model", 5, json);
        BusinessException missingKey = catchThrowableOfType(
                () -> noKey.parse("查询库存"), BusinessException.class);
        assertThat(missingKey.getCode()).isEqualTo(ErrorCode.NL_PARSER_UNAVAILABLE);
        assertThat(missingKey.getMessage()).contains("ERP_NL_API_KEY");

        responseStatus.set(200);
        responseBody.set("not-json");
        BusinessException badJson = catchThrowableOfType(
                () -> parser.parse("查询库存"), BusinessException.class);
        assertThat(badJson.getCode()).isEqualTo(ErrorCode.NL_PARSER_UNAVAILABLE);
        assertThat(badJson.getMessage()).contains("合法 JSON");
    }

    private String startServer(String initialBody) throws IOException {
        responseBody.set(initialBody);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void handle(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
