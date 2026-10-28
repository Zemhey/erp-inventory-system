package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 意图级策略：一个意图需要什么权限、要不要人工确认（FR-V4-03）。
 *
 * <p><b>存在的唯一理由：把这两件事只写一遍。</b>
 * 之前权限码散落在 {@link NlCommandDispatcher} 的每个分支里（{@code access.require("inv:read")}…），
 * 而"预览"也需要同一份判断 —— 一旦两处各写一遍，迟早出现
 * "预览说能执行、真执行却 403"这种最招人恨的错。
 *
 * <p>注意这里判断的是"意图级"权限，不是数据权限：能不能看某个租户的数据由更下层负责，
 * 本类只回答"这个动作需要哪个权限码"。
 */
@Component
public class NlPolicy {

    /**
     * 意图对应的权限码；{@code null} 表示不需要额外权限（目前只有 {@code help}）。
     *
     * <p>单据类意图的权限取决于 {@code docType}：进货看 {@code pur:read}、出货看 {@code sal:read}、
     * 退货看 {@code rma:read}。非法 {@code docType} 返回 {@code null}（不在这里判），
     * 交给调度器报"docType 取值非法"——否则用户会收到一个莫名其妙的 403 而不是清楚的参数错误。
     */
    public String requiredPermission(NlCommand command) {
        return switch (command.intent()) {
            case HELP, WHO_AM_I -> null;
            case QUERY_INVENTORY, REPLENISH_ADVICE -> "inv:read";
            case INVENTORY_SUMMARY -> "rpt:read";
            // 自定义查询横跨所有业务表，用"看报表"这枚权限来管：
            // 它本来就是"能不能出跨模块统计"的语义，比借用某个模块的 read 更贴切。
            case SQL_QUERY -> "rpt:read";
            case QUERY_MASTER_DATA -> "mdm:read";
            case CREATE_PURCHASE_ORDER -> "pur:create";
            case CREATE_SALES_ORDER -> "sal:create";
            case SUBMIT_DOCUMENT -> submitPermission(command);
            case LIST_DOCUMENTS, GET_DOCUMENT -> documentPermission(command);
        };
    }

    /**
     * 是否需要人工确认。
     *
     * <p>分级原则（计划书 §4.2）：**读不审，有副作用才审。**
     * 查错了最多是"答案不对"，重问一次就行；挡它只会把 AI 省下的时间又还回去。
     * 写操作目前全部落在 L1（只建草稿），仍需人点一下，但不需要走异步审批队列。
     */
    public boolean requiresHumanReview(NlCommand command) {
        return command.intent().write();
    }

    /** 为什么需要 / 不需要审核——直接展示给人看，所以是完整句子，不是枚举码。 */
    public String reviewReason(NlCommand command) {
        if (!command.intent().write()) {
            return "只读查询：不产生任何数据变更，不需要人工确认";
        }
        if (command.intent() == NlIntent.SUBMIT_DOCUMENT) {
            return "写操作：会把单据从草稿推入待审状态（不是审核、不改变库存），需要人工确认后才执行";
        }
        return "写操作：将产生单据草稿，需要人工确认后才落库";
    }

    /**
     * 提交单据的权限码：**与页面上的"提交"按钮同一个码**（{@code pur:submit} 等）。
     *
     * <p>刻意不复用 {@code *:create}：能起草不等于能把单子送进审批流。
     * 这两个动作在很多公司是不同人做的（业务员建单、主管提交），合成一个码
     * 就等于把"提交"这道关悄悄拆掉了。
     */
    private String submitPermission(NlCommand command) {
        Object raw = command.arguments().get("docType");
        if (raw == null) {
            return null;
        }
        return switch (String.valueOf(raw).toUpperCase(Locale.ROOT)) {
            case "PURCHASE" -> "pur:submit";
            case "SALES" -> "sal:submit";
            case "RETURN" -> "rma:submit";
            default -> null;
        };
    }

    private String documentPermission(NlCommand command) {
        Object raw = command.arguments().get("docType");
        if (raw == null) {
            return null;
        }
        return switch (String.valueOf(raw).toUpperCase(Locale.ROOT)) {
            case "PURCHASE" -> "pur:read";
            case "SALES" -> "sal:read";
            case "RETURN" -> "rma:read";
            default -> null;
        };
    }
}
