package com.erp.nl.application;

import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.domain.Sku;
import com.erp.nl.domain.NlFinding;
import com.erp.nl.domain.NlIntent;
import com.erp.nl.domain.NlPlan;
import com.erp.nl.domain.NlReview;
import com.erp.nl.domain.NlVerdict;
import com.erp.shared.error.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 确定性复核（FR-V4-03 · S4）：不依赖任何模型，纯规则，随时可用。
 *
 * <p><b>它补的是"计划自己不会说"的那些话。</b>计划服务已经算过参数全不全、金额多少，
 * 所以这里刻意<b>不重复</b>那些检查，只做三件计划做不了的事：
 *
 * <ol>
 *   <li><b>自洽性</b>：写操作却标记"无需人工确认" —— 这种计划本身就是坏的，必须否决。</li>
 *   <li><b>数字一致性</b>：把用户原话里的数字与计划参数逐一对照。这正是 AI 最现实的错误形态
 *       （"数量 20000" 被抽成 2），而计划服务看不到原话，只有这一层能发现。</li>
 *   <li><b>业务常识</b>：独立回查 SKU 零售价，判断出货价是否低得离谱、进货价是否高于卖价；
 *       再校对业务日期。这些是"数字都对、但事情不合理"的类型。</li>
 * </ol>
 *
 * <p><b>为什么这一层不会因为模型抽风而失效</b>：它没有模型。指令解析器换成 LLM 之后，
 * 它仍然是同一段代码——这也让"复核"在 key 缺失、网络不通的环境里依然成立。
 */
@Component
public class RuleNlReviewer implements NlReviewer {

    private static final String SOURCE = "RULE";
    /** 出货价低于零售价的这个比例时提示（服装行业折扣很少低到这个程度）。 */
    private static final long DEEP_DISCOUNT_PERCENT = 40L;
    /** 复核意见最多返回这么多条：再多，人就不看了。 */
    private static final int MAX_FINDINGS = 12;
    /** 提取原话里的整数串，用于与计划参数比对。 */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final ObjectMapper json;
    private final SkuApplicationService skus;

    public RuleNlReviewer(ObjectMapper json, SkuApplicationService skus) {
        this.json = json;
        this.skus = skus;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public NlReview review(NlPlan plan, String rawCommand) {
        NlIntent intent = NlIntent.fromCode(plan.intent()).orElse(null);
        if (intent == null) {
            return NlReview.of(NlVerdict.REJECT, SOURCE,
                    "复核否决：计划里的意图 " + plan.intent() + " 不是系统支持的动作",
                    List.of(NlFinding.reject(SOURCE, "未知意图：" + plan.intent())));
        }

        List<NlFinding> findings = new ArrayList<>();

        // ① 自洽性：写操作却声称不用审 —— 这种计划不能信
        if (intent.write() && (!plan.write() || !plan.requiresReview())) {
            findings.add(NlFinding.reject(SOURCE,
                    "这是写操作，计划却标记为无需人工确认 —— 计划本身不可信，拒绝放行"));
        }

        // ② 只读：没有副作用，直接通过（读不审，挡它只会把效率还回去）
        if (!intent.write()) {
            findings.add(NlFinding.info(SOURCE, "只读查询：不写库、不动库存、不产生单据"));
            return NlReview.of(NlVerdict.APPROVE, SOURCE, "只读查询，无副作用，无需复核", findings);
        }

        // ③ 阻塞项：每一条都是否决理由（计划已列全，这里只做搬运与定性）
        if (!plan.executable()) {
            if (plan.blockers().isEmpty()) {
                findings.add(NlFinding.reject(SOURCE, "计划标记为不可执行，但未说明原因"));
            } else {
                for (String blocker : plan.blockers()) {
                    findings.add(NlFinding.reject(SOURCE, blocker));
                }
            }
        }

        // ④ 数字一致性：原话里的数字有没有全部落到参数上
        checkNumbersAgainstArgs(rawCommand, plan, findings);

        // ⑤ 业务常识：价格与日期
        checkLinePrices(intent, plan, findings);
        checkBizDate(plan, findings);

        // ⑥ 计划自己已经标出来的警告**不重复列**：同一句话出现两遍，人会以为有两件事。
        //    但它们仍然影响结论——有需要留意的点，就不该给"不反对"的绿灯。
        int planWarnings = plan.warnings().size();

        List<NlFinding> capped = findings.size() > MAX_FINDINGS
                ? findings.subList(0, MAX_FINDINGS)
                : findings;

        boolean rejected = capped.stream().anyMatch(f -> "REJECT".equals(f.level()));
        boolean needsHuman = planWarnings > 0
                || capped.stream().anyMatch(f -> "WARN".equals(f.level()));
        NlVerdict verdict = rejected ? NlVerdict.REJECT
                : needsHuman ? NlVerdict.NEED_HUMAN
                : NlVerdict.APPROVE;
        String summary = switch (verdict) {
            case REJECT -> "复核否决：有 %d 条问题必须先解决".formatted(
                    capped.stream().filter(f -> "REJECT".equals(f.level())).count());
            case NEED_HUMAN -> capped.stream().anyMatch(f -> "WARN".equals(f.level()))
                    ? "复核通过但需要人确认：有 %d 处需要留意".formatted(
                            capped.stream().filter(f -> "WARN".equals(f.level())).count())
                    : "需要人确认：计划里已标出 %d 处需要留意的点（见上方）".formatted(planWarnings);
            case APPROVE -> "规则复核未发现问题（这不等于批准，执行仍需人工确认）";
        };
        return NlReview.of(verdict, SOURCE, summary, capped);
    }

    // ─────────────────────────── 数字一致性 ───────────────────────────

    /**
     * 把用户原话里的数字与计划参数对照。
     *
     * <p>方向是单向的：只查"原话里有、参数里没有"，不查反向。
     * 因为参数里多出数字是正常的（后端补默认值），而原话里的数字丢了，就是解析漏了。
     *
     * <p>四年份（{@code 20xx}）一律跳过：那是"2026 秋季"这类修饰语，不是数量。
     */
    private void checkNumbersAgainstArgs(String rawCommand, NlPlan plan, List<NlFinding> findings) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return;
        }
        String argsText;
        try {
            argsText = json.writeValueAsString(plan.args());
        } catch (JsonProcessingException ex) {
            return; // 序列化失败不该让复核挂掉，跳过这一项
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = DIGITS.matcher(rawCommand);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() == 4 && token.startsWith("20")) {
                continue;
            }
            if (!argsText.contains(token)) {
                missing.add(token);
            }
        }
        if (!missing.isEmpty()) {
            String joined = String.join(" / ", missing.stream().limit(5).toList());
            findings.add(NlFinding.warn(SOURCE,
                    "原话里的数字 " + joined + " 没有出现在计划参数里 —— 可能有一条参数被解析漏掉或抽错了，请逐项核对"));
        }
    }

    // ─────────────────────────── 价格常识 ───────────────────────────

    @SuppressWarnings("unchecked")
    private void checkLinePrices(NlIntent intent, NlPlan plan, List<NlFinding> findings) {
        boolean purchase = intent == NlIntent.CREATE_PURCHASE_ORDER;
        Object raw = plan.args().get("lines");
        if (!(raw instanceof List<?> list)) {
            return;
        }
        int index = 0;
        for (Object item : list) {
            index++;
            if (!(item instanceof Map<?, ?> line)) {
                continue;
            }
            Long skuId = asLong(line.get("skuId"));
            Long price = asLong(line.get("unitPriceAmount"));
            if (skuId == null || price == null) {
                continue;
            }
            Sku sku;
            try {
                sku = skus.require(skuId);
            } catch (BusinessException ex) {
                continue; // SKU 不存在已由计划列进 blockers，这里不重复
            }
            long retail = sku.retailPriceAmount();
            if (retail <= 0) {
                continue;
            }
            if (purchase && price > retail) {
                findings.add(NlFinding.warn(SOURCE,
                        "第 %d 行进货单价 %s 高于该 SKU 零售价 %s，通常不合理".formatted(
                                index, yuan(price), yuan(retail))));
            }
            if (!purchase && price * 100 < retail * DEEP_DISCOUNT_PERCENT) {
                findings.add(NlFinding.warn(SOURCE,
                        "第 %d 行出货单价 %s 低于该 SKU 零售价 %s 的 %d%%，折扣异常".formatted(
                                index, yuan(price), yuan(retail), DEEP_DISCOUNT_PERCENT)));
            }
        }
    }

    // ─────────────────────────── 日期常识 ───────────────────────────

    private void checkBizDate(NlPlan plan, List<NlFinding> findings) {
        LocalDate bizDate = NlArguments.optionalDate(plan.args(), "bizDate");
        if (bizDate == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (bizDate.isAfter(today.plusDays(7))) {
            findings.add(NlFinding.warn(SOURCE,
                    "业务日期 " + bizDate + " 在未来一周之后，请确认不是日期抽错"));
        }
        if (bizDate.isBefore(today.minusYears(1))) {
            findings.add(NlFinding.warn(SOURCE,
                    "业务日期 " + bizDate + " 早于一年前，账期可能已锁定"));
        }
    }

    private static Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String yuan(long fen) {
        return "¥%.2f".formatted(fen / 100.0);
    }
}
