package com.erp.prm.application;

import com.erp.prm.domain.Promotion;
import com.erp.prm.domain.Promotion.PromoType;
import com.erp.prm.repository.PromotionRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 促销活动应用服务（FR-V3-21）。
 *
 * <p>本期为"台账 + 展示"：规则 condition/benefit 只存 JSON 字符串并原样回显，
 * <b>不接入出货算价链路</b>（避免动 sal 折扣逻辑造成合并风险，报告中已如实标注）。
 * 状态机：DRAFT → ACTIVE ⇄ STOPPED。
 */
@Service
public class PromotionApplicationService {

    private final PromotionRepository promotions;

    public PromotionApplicationService(PromotionRepository promotions) {
        this.promotions = promotions;
    }

    @Transactional(readOnly = true)
    public List<Promotion> page(String status, String keyword) {
        return promotions.page(status, keyword);
    }

    @Transactional(readOnly = true)
    public Promotion require(long id) {
        return promotions.findById(id).orElseThrow(() -> new BusinessException(
                ErrorCode.PRM_PROMOTION_NOT_FOUND, "促销活动不存在：id=" + id));
    }

    @Transactional
    public Promotion create(Promotion draft) {
        if (promotions.existsCode(draft.code())) {
            throw new BusinessException(ErrorCode.PRM_PROMOTION_CODE_DUPLICATED,
                    "促销活动编码已存在：" + draft.code());
        }
        validate(draft);
        String status = draft.status() == null ? Promotion.STATUS_DRAFT : draft.status();
        long id = promotions.insert(new Promotion(draft.id(), draft.code(), draft.name(),
                draft.promoType(), draft.startTime(), draft.endTime(), draft.priority(),
                status, draft.remark(), draft.rules()));
        return require(id);
    }

    @Transactional
    public Promotion update(long id, Promotion draft) {
        Promotion current = require(id);
        validate(draft);
        promotions.replace(id, new Promotion(current.id(), draft.code(), draft.name(),
                draft.promoType(), draft.startTime(), draft.endTime(), draft.priority(),
                current.status(), draft.remark(), draft.rules()));
        return require(id);
    }

    /** DRAFT/STOPPED → ACTIVE；ACTIVE → STOPPED。非法转移报 400。 */
    @Transactional
    public Promotion transition(long id, boolean activate) {
        Promotion p = require(id);
        String target = activate ? Promotion.STATUS_ACTIVE : Promotion.STATUS_STOPPED;
        String from = p.status();
        boolean legal = (activate && (Promotion.STATUS_DRAFT.equals(from)
                || Promotion.STATUS_STOPPED.equals(from)))
                || (!activate && Promotion.STATUS_ACTIVE.equals(from));
        if (!legal) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                    "促销状态转移非法：%s → %s".formatted(from, target));
        }
        promotions.setStatus(id, target);
        return require(id);
    }

    private static void validate(Promotion draft) {
        if (draft.startTime() != null && draft.endTime() != null
                && draft.endTime().isBefore(draft.startTime())) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                    "促销结束时间早于开始时间：" + draft.code());
        }
        if (draft.promoType() == null) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID, "缺少促销类型");
        }
        for (Promotion.Rule r : draft.rules()) {
            if (blankJson(r.conditionJson()) || blankJson(r.benefitJson())) {
                throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                        "规则的条件/利益 JSON 不能为空");
            }
        }
    }

    private static boolean blankJson(String value) {
        return value == null || value.isBlank();
    }

    /** 便于 Controller 层做类型白名单（PromoType 枚举校验由 Jackson 完成，这里兜底）。 */
    public static PromoType parseType(String raw) {
        try {
            return PromoType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                    "促销类型非法：" + raw);
        }
    }
}
