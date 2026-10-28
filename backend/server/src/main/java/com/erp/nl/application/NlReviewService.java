package com.erp.nl.application;

import com.erp.nl.domain.NlPlan;
import com.erp.nl.domain.NlReview;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 复核编排（FR-V4-03 · S4）：跑规则层，再跑可选的 AI 层，然后**取更严格的一方**。
 *
 * <p><b>为什么要有编排层，而不是让调用方自己挨个调</b>：
 * "合并规则"本身就是安全边界。如果每个调用点各写一遍"取谁的意见"，
 * 总有人写成"AI 说通过就通过"。把这条规则关在一个类里，改一次就全站生效。
 *
 * <p><b>两层的顺序有讲究</b>：规则先跑、AI 后跑。规则一票否决时仍会跑 AI
 * ——不是为了让它翻案（它翻不了），而是为了让审计里留下"模型当时怎么看的"。
 * 但也因此 AI 层必须便宜：只读一段 JSON，输出三选一。
 *
 * <p><b>降级策略</b>：AI 层没配置或没跑通，不是错误，是"少了一个意见来源"。
 * 结论仍以规则为准，并在意见里明确写出"AI 复核未参与"——
 * 不能让用户以为有一道他没得到的检查。
 */
@Service
public class NlReviewService {

    private final RuleNlReviewer rules;
    private final ObjectProvider<NlReviewer> reviewers;

    public NlReviewService(RuleNlReviewer rules, ObjectProvider<NlReviewer> reviewers) {
        this.rules = rules;
        this.reviewers = reviewers;
    }

    /**
     * @param plan       待复核计划
     * @param rawCommand 用户原话（只透传给规则层做数字比对；AI 层会丢弃它）
     */
    public NlReview review(NlPlan plan, String rawCommand) {
        NlReview base = rules.review(plan, rawCommand);
        // 读不审（计划书 §4.2）：只读计划本来就不需要第二个模型再看一遍，
        // 否则每条"查询库存"都要多一次模型往返，纯粹烧钱换延迟。
        if (!plan.write()) {
            return base;
        }
        NlReviewer ai = aiReviewer();
        // "没启用"与"启用但没跑通"要分开说：前者去设置页填 key，后者查网络。
        if (ai == null || !ai.available()) {
            return base.withNote("AI 复核未启用（复核方式为规则或未配置模型 Key）：本次只做了确定性规则校验");
        }
        NlReview second = ai.review(plan, rawCommand);
        if (second == null) {
            return base.withNote("AI 复核不可用（模型无响应或输出不合法）：已降级为规则校验");
        }
        return base.merge(second);
    }

    /** 当前是否真的有第二个模型参与——前端据此说明"这台机器上复核是几个人在看"。 */
    public boolean aiReviewEnabled() {
        NlReviewer ai = aiReviewer();
        return ai != null && ai.available();
    }

    private NlReviewer aiReviewer() {
        return reviewers.orderedStream()
                .filter(reviewer -> !RuleNlReviewer.class.isInstance(reviewer))
                .findFirst()
                .orElse(null);
    }
}
