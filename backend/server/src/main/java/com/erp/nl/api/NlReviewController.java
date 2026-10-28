package com.erp.nl.api;

import com.erp.nl.application.NlCommandParser;
import com.erp.nl.application.NlPlanService;
import com.erp.nl.application.NlReviewService;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlPlan;
import com.erp.nl.domain.NlReview;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单独复核入口（FR-V4-03 · S4，契约 {@code POST /api/v1/nl/review}）。
 *
 * <p><b>既然 {@code /nl/plan} 已经带复核结果，为什么还要这个接口</b>：
 * 三个实际用途——① 页面上的"重新复核"按钮（复核 AI 不可用后恢复，不必重新生成计划）；
 * ② 运维/审计排查"这条计划当时被判成什么、为什么"；③ 将来接人工复核队列时，
 * 队列里每一条都要能单独复跑一次。
 *
 * <p><b>它不签发确认凭据</b>。凭据只能从 {@code /nl/plan} 出：
 * 如果复核接口也能签，就等于给"跳过预览"开了第二条路。
 */
@RestController
@RequestMapping("/api/v1/nl")
public class NlReviewController {

    private final NlCommandParser parser;
    private final NlPlanService plans;
    private final NlReviewService reviews;

    public NlReviewController(NlCommandParser parser, NlPlanService plans, NlReviewService reviews) {
        this.parser = parser;
        this.plans = plans;
        this.reviews = reviews;
    }

    public record ReviewRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500")
            String command) {
    }

    @PostMapping("/review")
    public ResponseEntity<Map<String, Object>> review(
            @RequestHeader("X-Request-Id") @NotBlank @Size(min = 8, max = 64) String requestId,
            @Valid @RequestBody ReviewRequest req) {
        NlCommand parsed = parser.parse(req.command());
        NlPlan plan = plans.plan(parsed);
        NlReview review = reviews.review(plan, req.command());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", plan);
        body.put("review", review);
        body.put("aiReviewEnabled", reviews.aiReviewEnabled());
        return ResponseEntity.ok(ApiResponses.ok(body));
    }
}
