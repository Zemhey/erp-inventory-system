package com.erp.sys.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.sys.domain.NumberRule;
import com.erp.sys.repository.NumberRuleRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单号规则应用服务（FR-V3-25）：列表 + 编辑 + 预览。
 *
 * <p>预览只看不取：读 {@code document_sequences} 当前流水号 +1，返回一个"下一张单号长这样"
 * 的示例，绝不会真的消费号（不会让号跳号）。
 *
 * <p>★ 与 {@code DocumentNumberGenerator} 保持一致：实际取号恒用 {@code yyyyMMdd} 日期键，
 * 因此预览也用今天 {@code yyyyMMdd} 作日期键，否则预览号会和真实号错位。
 */
@Service
public class NumberRuleService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private final NumberRuleRepository repository;
    private final ClockProvider clock;

    public NumberRuleService(NumberRuleRepository repository, ClockProvider clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NumberRule> list() {
        return repository.list();
    }

    @Transactional
    public NumberRule update(long id, String prefix, int seqLength, boolean enabled) {
        if (prefix == null || prefix.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "前缀不能为空");
        }
        if (seqLength < 2 || seqLength > 12) {
            throw new BusinessException(ErrorCode.SYS_NUMBER_RULE_INVALID, "流水位长必须在 2~12 之间");
        }
        NumberRule rule = repository.list().stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NUMBER_RULE_NOT_FOUND,
                        "规则不存在：id=" + id));
        repository.update(id, prefix.trim(), seqLength, enabled);
        return new NumberRule(rule.id(), rule.docType(), prefix.trim(), rule.datePattern(),
                seqLength, rule.resetCycle(), enabled, rule.createdAt(), rule.updatedAt());
    }

    /**
     * 预览下一张单号（不消费）。
     *
     * @param docType 单据类型，如 PURCHASE_ORDER
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String docType) {
        NumberRule rule = repository.findByDocType(docType).orElseThrow(
                () -> new BusinessException(ErrorCode.SYS_NUMBER_RULE_NOT_FOUND,
                        "单据类型无单号规则：" + docType));
        LocalDate today = clock.today();
        String dayKey = today.format(DAY);
        long current = repository.currentSequence(rule.prefix(), dayKey);
        long next = current + 1;
        String serial = String.format(Locale.ROOT, "%0" + rule.seqLength() + "d", next);
        String sample = rule.prefix() + dayKey + "-" + serial;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("docType", rule.docType());
        result.put("prefix", rule.prefix());
        result.put("datePattern", rule.datePattern());
        result.put("seqLength", rule.seqLength());
        result.put("resetCycle", rule.resetCycle());
        result.put("currentNo", current);
        result.put("sample", sample);
        result.put("dayKey", dayKey);
        return result;
    }
}
