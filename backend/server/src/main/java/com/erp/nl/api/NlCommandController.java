package com.erp.nl.api;

import com.erp.nl.application.NlCommandDispatcher;
import com.erp.nl.application.NlCommandParser;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlCommandResult;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然语言操作入口（FR-SYS-04，契约 {@code POST /api/v1/nl/command}）。
 *
 * <p>本层只有三步：绑定请求 → 交给解析器 → 交给调度器。
 * 它<b>不做</b>权限判断（意图级权限在调度器里）、<b>不碰</b>任何业务服务
 * —— 这样换解析器（stub/真实 LLM）时，HTTP 契约与错误码完全不变。
 *
 * <p>为什么读意图也要求 {@code X-Request-Id}：NL 入口在 HTTP 层无法预知意图，
 * 用一条统一规则比"先解析再决定要不要键"简单，也避免写意图漏键。
 * 写意图把该键原样交给建单服务作为幂等键。
 */
@RestController
@RequestMapping("/api/v1/nl")
public class NlCommandController {

    private final NlCommandParser parser;
    private final NlCommandDispatcher dispatcher;

    public NlCommandController(NlCommandParser parser, NlCommandDispatcher dispatcher) {
        this.parser = parser;
        this.dispatcher = dispatcher;
    }

    public record CommandRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500")
            String command) {
    }

    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> command(
            @RequestHeader("X-Request-Id") @NotBlank @Size(min = 8, max = 64) String requestId,
            @Valid @RequestBody CommandRequest req) {
        NlCommand parsed = parser.parse(req.command());
        NlCommandResult result = dispatcher.execute(parsed, requestId);
        return ResponseEntity.ok(ApiResponses.ok(result));
    }
}
