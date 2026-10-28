package com.erp.nl.api;

import com.erp.nl.application.NlCommandParser;
import com.erp.nl.application.NlConfirmations;
import com.erp.nl.application.NlPlanService;
import com.erp.nl.application.NlReviewService;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlConfirmation;
import com.erp.nl.domain.NlPlan;
import com.erp.nl.domain.NlPlanResponse;
import com.erp.nl.domain.NlReview;
import com.erp.shared.security.ActorContext;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.NlExecutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 执行计划预览入口（FR-V4-03 · S3/S4/S5，契约 {@code POST /api/v1/nl/plan}）。
 *
 * <p>与 {@code /nl/command} 的关系：**同一个解析器，不同的去向**。
 * 这里解析完只生成计划、跑复核、签发回执；人要看过计划后，再拿同一句话去执行。
 *
 * <p><b>为什么预览也要求 {@code X-Request-Id}</b>：写意图会用它作为这行档案的主键线索，
 * 执行时回执里带着它，审计里就能把"它当时打算干什么"和"最终干了什么"串成一条线。
 * 少一个 header 换来的便利，不值得让审计断链。
 *
 * <p><b>什么时候会落档案</b>：只有写意图。只读查询不落——它们没有"待确认"这回事，
 * 把每天的查询都存一份会让档案表变成日志垃圾，反而淹掉真正要看的那几条。
 *
 * <p>本接口不产生任何业务写操作（唯一写入的是本次计划自己的档案行），因此可安全重试。
 */
@RestController
@RequestMapping("/api/v1/nl")
public class NlPlanController {

    private final NlCommandParser parser;
    private final NlPlanService plans;
    private final NlReviewService reviews;
    private final NlConfirmations confirmations;
    private final NlExecutionService executions;
    private final ActorContext actors;
    private final ObjectMapper json;

    public NlPlanController(NlCommandParser parser,
                            NlPlanService plans,
                            NlReviewService reviews,
                            NlConfirmations confirmations,
                            NlExecutionService executions,
                            ActorContext actors,
                            ObjectMapper json) {
        this.parser = parser;
        this.plans = plans;
        this.reviews = reviews;
        this.confirmations = confirmations;
        this.executions = executions;
        this.actors = actors;
        this.json = json;
    }

    public record PlanRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500")
            String command) {
    }

    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> plan(
            @RequestHeader("X-Request-Id") @NotBlank @Size(min = 8, max = 64) String requestId,
            @RequestHeader(value = "X-Nl-Source", required = false) String source,
            @Valid @RequestBody PlanRequest req) {
        NlCommand parsed = parser.parse(req.command());
        NlPlan plan = plans.plan(parsed);
        NlReview review = reviews.review(plan, req.command());
        Long actorId = actors.current().map(ActorContext.Actor::id).orElse(null);
        NlConfirmation confirmation = confirmations.issue(parsed, requestId, actorId, review);

        if (parsed.intent().write() && actorId != null) {
            record(requestId, source, req.command(), parsed, plan, review, actorId);
        }
        return ResponseEntity.ok(ApiResponses.ok(new NlPlanResponse(plan, review, confirmation)));
    }

    /**
     * 落一行档案。
     *
     * <p>存的是**生成当时的 JSON 快照**：审计认证的是"当时看到的东西"，
     * 而不是按后来的代码重新算一遍——否则历史记录会随着版本演化和事实脱节。
     */
    private void record(String requestId, String source, String commandText, NlCommand parsed,
                        NlPlan plan, NlReview review, long actorId) {
        String actorName = actors.current().map(ActorContext.Actor::username).orElse(null);
        executions.recordPlan(
                requestId,
                actorId,
                actorName,
                normalizeSource(source),
                commandText,
                parsed.intent().code(),
                true,
                plan.executable(),
                review.verdict().name(),
                review.source(),
                plan.summary(),
                toJson(plan),
                toJson(review));
    }

    /** 调用方标记，只用于审计归类。不认识的值一律按 API 记，而不是原样存进去。 */
    private static String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return "WEB";
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "WEB", "MCP", "API" -> value;
            default -> "API";
        };
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            // 序列化失败不该让"预览"这个动作失败：档案缺一份快照，比用户看不到计划轻得多
            return "{}";
        }
    }
}
