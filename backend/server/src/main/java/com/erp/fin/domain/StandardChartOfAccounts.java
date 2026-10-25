package com.erp.fin.domain;

import java.util.List;

/**
 * 标准科目模板与生成凭证要用到的科目编码（FR-V3-06 / FR-V3-08）。
 *
 * <p><b>两处模板必须逐字一致</b>：V20 迁移里的 VALUES 与本类的 {@link #TEMPLATE}。
 * V20 为"迁移时刻已存在的租户"铺底，{@code ensureSeeded} 为"之后新建的租户"懒补 ——
 * 编码是凭证生成规则（按 code 找科目）的稳定锚点，任何一处改动都要同步另一处，
 * 否则新租户会静默缺科目、老租户会撞唯一键（ON CONFLICT 只兜后者）。
 */
public final class StandardChartOfAccounts {

    // ── 凭证生成规则引用到的编码（简报 §4 FR-V3-08 规则表的锚点） ──
    public static final String CASH_ON_HAND = "1001";     // 库存现金
    public static final String BANK_DEPOSIT = "1002";     // 银行存款
    public static final String RECEIVABLE = "1122";       // 应收账款
    public static final String PAYABLE = "2202";          // 应付账款
    public static final String TAX_PAYABLE = "2221";      // 应交税费
    public static final String TAX_INPUT = "222101";      // 应交税费-进项税额（借）
    public static final String TAX_OUTPUT = "222102";     // 应交税费-销项税额（贷）
    public static final String MAIN_REVENUE = "6001";     // 主营业务收入
    public static final String MAIN_COST = "6401";        // 主营业务成本
    public static final String MERCHANDISE = "1405";      // 库存商品
    public static final String ADMIN_EXPENSE = "6602";    // 管理费用

    /** 模板行：编码、名称、类别、方向、是否末级、父编码（null = 一级）。 */
    public record Seed(String code, String name, AccountCategory category,
                       AccountDirection direction, boolean leaf, String parentCode) {
    }

    private static Seed debit(String code, String name, AccountCategory c) {
        return new Seed(code, name, c, AccountDirection.DEBIT, true, null);
    }

    private static Seed credit(String code, String name, AccountCategory c) {
        return new Seed(code, name, c, AccountDirection.CREDIT, true, null);
    }

    public static final List<Seed> TEMPLATE = List.of(
            debit("1001", "库存现金", AccountCategory.ASSET),
            debit("1002", "银行存款", AccountCategory.ASSET),
            debit("1012", "其他货币资金", AccountCategory.ASSET),
            debit("1122", "应收账款", AccountCategory.ASSET),
            debit("1123", "预付账款", AccountCategory.ASSET),
            debit("1221", "其他应收款", AccountCategory.ASSET),
            debit("1401", "原材料", AccountCategory.ASSET),
            debit("1405", "库存商品", AccountCategory.ASSET),
            debit("1411", "周转材料", AccountCategory.ASSET),
            credit("2202", "应付账款", AccountCategory.LIABILITY),
            credit("2203", "预收账款", AccountCategory.LIABILITY),
            credit("2211", "应付职工薪酬", AccountCategory.LIABILITY),
            new Seed("2221", "应交税费", AccountCategory.LIABILITY,
                    AccountDirection.CREDIT, false, null),
            new Seed("222101", "应交税费-进项税额", AccountCategory.LIABILITY,
                    AccountDirection.DEBIT, true, "2221"),
            new Seed("222102", "应交税费-销项税额", AccountCategory.LIABILITY,
                    AccountDirection.CREDIT, true, "2221"),
            credit("4001", "实收资本", AccountCategory.EQUITY),
            credit("4002", "资本公积", AccountCategory.EQUITY),
            credit("4104", "利润分配", AccountCategory.EQUITY),
            credit("6001", "主营业务收入", AccountCategory.REVENUE),
            credit("6051", "其他业务收入", AccountCategory.REVENUE),
            credit("6301", "营业外收入", AccountCategory.REVENUE),
            debit("6401", "主营业务成本", AccountCategory.COST),
            debit("6402", "其他业务成本", AccountCategory.COST),
            debit("6403", "税金及附加", AccountCategory.COST),
            debit("6601", "销售费用", AccountCategory.EXPENSE),
            debit("6602", "管理费用", AccountCategory.EXPENSE),
            debit("6603", "财务费用", AccountCategory.EXPENSE),
            debit("6711", "营业外支出", AccountCategory.EXPENSE));

    private StandardChartOfAccounts() {
    }
}
