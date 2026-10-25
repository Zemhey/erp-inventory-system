package com.erp.fin.application;

import com.erp.fin.domain.AccountCategory;
import com.erp.fin.domain.AccountDirection;
import com.erp.fin.domain.FinAccount;
import com.erp.fin.domain.StandardChartOfAccounts;
import com.erp.fin.repository.FinAccountRepository;
import com.erp.fin.repository.FinVoucherRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会计科目表（FR-V3-06）。
 *
 * <p><b>懒补种（ensureSeeded）</b>：V20 为迁移时刻已存在的租户铺 28 个标准科目；
 * 之后新建的租户（平台租户管理，B1）在第一次访问科目时补齐同一模板。
 * 凭证生成按编码找科目，没有模板的新租户会在第一张凭证上哑掉 ——
 * "科目缺失"必须是响亮失败，而不是空树。
 */
@Service
public class FinAccountApplicationService {

    /** 树节点（简报验收："树能展开"）。children 为子科目；六大类分组由前端按 category 归组。 */
    public record AccountNode(FinAccount account, List<AccountNode> children) {
    }

    public record CreateCommand(String code, String name, Long parentId, String category,
                                String direction, BigDecimal initBalance, Boolean enabled,
                                String remark) {
    }

    public record UpdateCommand(String name, String direction, BigDecimal initBalance,
                                Boolean enabled, String remark) {
    }

    private final FinAccountRepository accounts;
    private final FinVoucherRepository vouchers;

    public FinAccountApplicationService(FinAccountRepository accounts,
                                        FinVoucherRepository vouchers) {
        this.accounts = accounts;
        this.vouchers = vouchers;
    }

    /** 当前租户没有科目 → 按标准模板补种（幂等：先查 count，再逐条插入）。 */
    @Transactional
    public void ensureSeeded() {
        if (accounts.count() > 0) {
            return;
        }
        Map<String, Long> inserted = new LinkedHashMap<>();
        for (StandardChartOfAccounts.Seed seed : StandardChartOfAccounts.TEMPLATE) {
            if (inserted.containsKey(seed.code())) {
                throw new BusinessException(ErrorCode.FIN_ACCOUNTING_TEMPLATE_MISSING,
                        "标准科目模板编码重复：" + seed.code());
            }
            Long parentId = seed.parentCode() == null ? null : inserted.get(seed.parentCode());
            inserted.put(seed.code(), accounts.insert(seed.code(), seed.name(), parentId,
                    seed.category(), seed.direction(), seed.leaf(), BigDecimal.ZERO,
                    true, "系统标准科目（自动初始化）"));
        }
    }

    /** 读路径也懒补种（与 tree 同一理由：新租户第一次打开科目下拉就必须有得选）。 */
    @Transactional
    public List<FinAccount> list() {
        ensureSeeded();
        return accounts.listAll();
    }

    /**
     * 科目树：一级科目（parent_id IS NULL）为根，子科目递归挂载。
     *
     * <p>读路径也 ensureSeeded：新租户第一次点"财务中心 → 科目"就应当看到模板，
     * 而不是空树 + 手动初始化按钮（验收口径"树能展开"）。
     */
    @Transactional
    public List<AccountNode> tree() {
        ensureSeeded();
        List<FinAccount> all = accounts.listAll();
        Map<Long, List<FinAccount>> byParent = new LinkedHashMap<>();
        List<FinAccount> roots = new ArrayList<>();
        for (FinAccount a : all) {
            if (a.parentId() == null) {
                roots.add(a);
            } else {
                byParent.computeIfAbsent(a.parentId(), k -> new ArrayList<>()).add(a);
            }
        }
        return roots.stream().map(r -> node(r, byParent)).toList();
    }

    private AccountNode node(FinAccount account, Map<Long, List<FinAccount>> byParent) {
        List<FinAccount> children = byParent.getOrDefault(account.id(), List.of());
        return new AccountNode(account, children.stream()
                .map(c -> node(c, byParent)).toList());
    }

    @Transactional
    public FinAccount create(CreateCommand cmd) {
        ensureSeeded();
        String code = cmd.code() == null ? "" : cmd.code().trim();
        if (code.isEmpty() || code.length() > 32) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "科目编码必填且不超过 32 字符");
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "科目名称必填");
        }
        AccountCategory category = parseEnum(AccountCategory.class, cmd.category(),
                "ASSET|LIABILITY|EQUITY|COST|REVENUE|EXPENSE", ErrorCode.SYS_VALIDATION_ERROR);
        AccountDirection direction = parseEnum(AccountDirection.class, cmd.direction(),
                "DEBIT|CREDIT", ErrorCode.SYS_VALIDATION_ERROR);
        if (accounts.findByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.FIN_ACCOUNT_DUPLICATED,
                    "科目编码已存在：code=" + code);
        }
        Long parentId = cmd.parentId();
        if (parentId != null) {
            FinAccount parent = accounts.findById(parentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.FIN_ACCOUNT_NOT_FOUND,
                            "父科目不存在：id=" + parentId));
            if (!parent.leaf()) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "父科目 %s 已含子科目且非末级，不能作为新科目的上级".formatted(parent.code()));
            }
        }
        BigDecimal init = cmd.initBalance() == null ? BigDecimal.ZERO : scale(cmd.initBalance());
        if (init.signum() < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "期初余额不能为负");
        }
        boolean enabled = cmd.enabled() == null || cmd.enabled();
        long id = accounts.insert(code, cmd.name().trim(), parentId, category, direction,
                true, init, enabled, trim(cmd.remark()));
        if (parentId != null) {
            // 父科目降级为非末级后，历史分录不受影响（分录冗余了 account_code）
            accounts.setLeaf(parentId, false);
        }
        return accounts.findById(id).orElseThrow();
    }

    @Transactional
    public FinAccount update(long id, UpdateCommand cmd) {
        FinAccount existing = accounts.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_ACCOUNT_NOT_FOUND,
                        "科目不存在：id=" + id));
        AccountDirection direction = cmd.direction() == null ? existing.direction()
                : parseEnum(AccountDirection.class, cmd.direction(), "DEBIT|CREDIT",
                        ErrorCode.SYS_VALIDATION_ERROR);
        BigDecimal init = cmd.initBalance() == null ? existing.initBalance()
                : scale(cmd.initBalance());
        if (init.signum() < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "期初余额不能为负");
        }
        boolean enabled = cmd.enabled() == null ? existing.enabled() : cmd.enabled();
        if (!enabled && vouchers.accountReferenced(id)) {
            throw new BusinessException(ErrorCode.FIN_ACCOUNT_IN_USE,
                    "科目 %s 已被凭证分录引用，只能停用不能删除；停用后新分录不可再选它"
                            .formatted(existing.code()));
        }
        if (!accounts.update(id, cmd.name() == null ? existing.name() : cmd.name().trim(),
                direction, init, enabled, trim(cmd.remark()))) {
            throw new BusinessException(ErrorCode.FIN_ACCOUNT_NOT_FOUND, "科目不存在：id=" + id);
        }
        return accounts.findById(id).orElseThrow();
    }

    /** 删除（简报验收：被凭证引用的科目不许删，409）。 */
    @Transactional
    public void delete(long id) {
        FinAccount existing = accounts.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_ACCOUNT_NOT_FOUND,
                        "科目不存在：id=" + id));
        if (accounts.countChildren(id) > 0) {
            throw new BusinessException(ErrorCode.FIN_ACCOUNT_IN_USE,
                    "科目 %s 还有子科目，请先删除子科目".formatted(existing.code()));
        }
        if (vouchers.accountReferenced(id)) {
            throw new BusinessException(ErrorCode.FIN_ACCOUNT_IN_USE,
                    "科目 %s 已被凭证分录引用，不能删除".formatted(existing.code()));
        }
        accounts.deleteById(id);
    }

    public static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String allowed,
                                           ErrorCode code) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw.trim().toUpperCase(
                    java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(code, "取值非法：%s（允许 %s）".formatted(raw, allowed));
        }
    }
}
