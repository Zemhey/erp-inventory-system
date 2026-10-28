package com.erp.nl.application;

import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 确定性 stub 解析器（默认实现）。
 *
 * <p><b>为什么需要它</b>：测试、CI 与离线演示不能依赖外网、LLM key 或额度；
 * 而"解析器可注入"本身也需要一个不依赖任何外部服务的实现来证明调度链路是通的。
 *
 * <p><b>它不是通用 NLU</b>：只认关键词与键值对，不做别名、相对时间（除本月/上月）、多轮上下文。
 * 真实自然语言请把 {@code erp.nl.parser} 切到 {@code openai} —— 调度与权限代码完全不变。
 *
 * <p>支持的写法见 {@code .plan/M7-计划书.md} §2.7。机器可读兜底：
 * {@code query_inventory skuId=12 warehouseId=3}。
 */
@Component
@ConditionalOnProperty(name = "erp.nl.parser", havingValue = "stub", matchIfMissing = true)
public class StubNlCommandParser implements NlCommandParser {

    private static final Pattern KEY_VALUE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s;；]+)");
    private static final Pattern PERIOD = Pattern.compile("(20\\d{2}-\\d{2})");
    private static final Pattern PAGE = Pattern.compile("第\\s*(\\d+)\\s*页");
    private static final Pattern SIZE = Pattern.compile("每页\\s*(\\d+)");
    private static final Pattern KEYWORD = Pattern.compile("关键字\\s*[:：=]?\\s*([^\\s,，;；]+)");
    private static final Pattern NUMBER_AFTER_WAREHOUSE = Pattern.compile("仓库\\s*(?:id)?\\s*[#:：=]?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_AFTER_SKU = Pattern.compile("SKU\\s*(?:id)?\\s*[#:：=]?\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_AFTER_SUPPLIER = Pattern.compile("供应商\\s*(?:id)?\\s*[#:：=]?\\s*(\\d+)");
    private static final Pattern NUMBER_AFTER_CUSTOMER = Pattern.compile("客户\\s*(?:id)?\\s*[#:：=]?\\s*(\\d+)");
    private static final Pattern NUMBER_AFTER_PRODUCT = Pattern.compile("商品\\s*(?:id)?\\s*[#:：=]?\\s*(\\d+)");
    private static final Pattern LINE_QTY = Pattern.compile("数量\\s*[:：=]?\\s*(\\d+)");
    private static final Pattern LINE_PRICE = Pattern.compile("单价\\s*[:：=]?\\s*(\\d+)");
    private static final Pattern LINE_DISCOUNT = Pattern.compile("折扣\\s*[:：=]?\\s*(\\d+)");
    private static final Pattern BIZ_DATE = Pattern.compile("(?:日期|业务日期)\\s*[:：=]?\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern REMARK = Pattern.compile("备注\\s*[:：=]?\\s*([^\\s,，;；]+)");
    /** 单据号后面的数字：「提交进货单 12」「采购单 #12」——只在"提交"分支用，别处会误伤数量。 */
    private static final Pattern NUMBER_AFTER_DOC = Pattern.compile(
            "(?:进货单|采购单|出货单|销售单|退货单|单据|订单)\\s*(?:id)?\\s*[#:：=]?\\s*(\\d+)");

    private static final String SUPPORTED = String.join(" / ", NlIntent.allCodes());

    private final ClockProvider clock;

    public StubNlCommandParser(ClockProvider clock) {
        this.clock = clock;
    }

    @Override
    public NlCommand parse(String text) {
        if (text == null || text.isBlank()) {
            throw unrecognized("");
        }
        String input = text.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        Map<String, Object> kv = keyValues(input);

        // ① 机器可读兜底：意图代码 + 键值对（便于排障与自动化）
        String firstToken = lower.split("\\s+", 2)[0];
        NlIntent byCode = NlIntent.fromCode(firstToken).orElse(null);
        if (byCode != null) {
            return new NlCommand(byCode, kv);
        }

        // ② 帮助
        if (lower.equals("help") || lower.contains("帮助") || lower.contains("能做什么")
                || lower.contains("支持什么")) {
            return NlCommand.of(NlIntent.HELP);
        }

        // ②.2 自述（"你是什么模型 / 你是谁"）。
        //     关键词模式下同样要能答 —— 否则用户会得到"无法识别"，反而更像系统坏了。
        if (lower.contains("什么模型") || lower.contains("哪个模型") || lower.contains("你是谁")
                || lower.contains("什么ai") || lower.contains("什么 ai") || lower.contains("用的什么")) {
            return NlCommand.of(NlIntent.WHO_AM_I);
        }

        // ★ "建"字单独出现也算创建意图 —— 这是口语里最常见的说法（"帮我建一张进货单"
        //   "建个进货单"），但它不含"创建/新建/建单"任何一个词，早期只认那三个词时
        //   这类句子会掉到 ④ 的"进货单列表"分支：用户说"建"，系统回一张列表，
        //   看起来就像"自然语言根本没生效"。排除"建议"（补货建议是查询意图）。
        boolean createWord = lower.contains("建") && !lower.contains("建议");
        boolean create = createWord || lower.contains("创建") || lower.contains("新建")
                || lower.contains("建单") || lower.contains("create");
        if (create && (lower.contains("进货") || lower.contains("采购") || lower.contains("purchase"))) {
            return purchaseOrder(input, kv);
        }
        if (create && (lower.contains("出货") || lower.contains("销售") || lower.contains("sales"))) {
            return salesOrder(input, kv);
        }

        // ②.5 提交单据（受限 L2）。必须放在"单据列表/详情"之前：
        //     否则"提交进货单 12"会被 ④ 当成"进货单详情"（因为含"进货单"+数字）。
        //     也刻意放在"创建"之后：避免"创建进货单并提交"被理解成对某张已有单的提交。
        if ((lower.contains("提交") || lower.contains("submit")) && !create) {
            String submitType = detectDocType(lower);
            if (submitType != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("docType", submitType);
                putIfPresent(args, "id", first(kv, "id",
                        first(kv, "orderId", number(NUMBER_AFTER_DOC, input))));
                return new NlCommand(NlIntent.SUBMIT_DOCUMENT, args);
            }
        }

        // ③ 进销存汇总（先于"库存"判断，避免"进销存"被当成库存查询）
        if (lower.contains("汇总") || lower.contains("进销存") || lower.contains("summary")) {
            return summary(input, kv);
        }

        // ④ 单据：列表 / 详情
        String docType = detectDocType(lower);
        if (docType != null) {
            boolean detail = lower.contains("详情") || lower.contains("detail") || lower.contains("查看")
                    || kv.containsKey("id") || kv.containsKey("orderId");
            return detail ? documentDetail(input, kv, docType) : documentList(input, kv, docType);
        }

        // ⑤ 补货建议（目标级意图）。
        //   放在单据判断之后：避免"补货单"这类说法被误判；
        //   放在库存判断之前：因为"补货建议"往往也提到库存，先匹配目标级意图才对。
        if (lower.contains("补货") || lower.contains("该补") || lower.contains("要补")
                || lower.contains("需补") || lower.contains("补什么") || lower.contains("replenish")) {
            Map<String, Object> args = new LinkedHashMap<>();
            putIfPresent(args, "warehouseId",
                    first(kv, "warehouseId", number(NUMBER_AFTER_WAREHOUSE, input)));
            return new NlCommand(NlIntent.REPLENISH_ADVICE, args);
        }

        // ⑥ 库存
        if (lower.contains("库存") || lower.contains("stock") || lower.contains("inventory")) {
            Map<String, Object> args = new LinkedHashMap<>();
            putIfPresent(args, "skuId", first(kv, "skuId", number(NUMBER_AFTER_SKU, input)));
            putIfPresent(args, "warehouseId",
                    first(kv, "warehouseId", number(NUMBER_AFTER_WAREHOUSE, input)));
            putIfPresent(args, "batchNo", first(kv, "batchNo", null));
            args.put("onlyAvailable", booleanArg(kv, "onlyAvailable",
                    lower.contains("仅可用") || lower.contains("只看可用") || lower.contains("onlyavailable")));
            return new NlCommand(NlIntent.QUERY_INVENTORY, args);
        }

        // ⑦ 主数据
        String mdmType = detectMasterDataType(lower);
        if (mdmType != null) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("type", mdmType);
            putIfPresent(args, "keyword", first(kv, "keyword", text(KEYWORD, input)));
            putIfPresent(args, "productId", first(kv, "productId", number(NUMBER_AFTER_PRODUCT, input)));
            putIfPresent(args, "page", first(kv, "page", number(PAGE, input)));
            putIfPresent(args, "size", first(kv, "size", number(SIZE, input)));
            if (lower.contains("仅启用") || lower.contains("启用")) {
                args.put("onlyEnabled", true);
            }
            return new NlCommand(NlIntent.QUERY_MASTER_DATA, args);
        }

        throw unrecognized(input);
    }

    // ─────────────────────────── 各意图参数 ───────────────────────────

    private NlCommand documentList(String input, Map<String, Object> kv, String docType) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("docType", docType);
        putIfPresent(args, "status", first(kv, "status", statusText(input)));
        putIfPresent(args, "page", first(kv, "page", number(PAGE, input)));
        putIfPresent(args, "size", first(kv, "size", number(SIZE, input)));
        if ("PURCHASE".equals(docType)) {
            putIfPresent(args, "partyId", first(kv, "partyId", first(kv, "supplierId",
                    number(NUMBER_AFTER_SUPPLIER, input))));
        } else {
            putIfPresent(args, "partyId", first(kv, "partyId", first(kv, "customerId",
                    number(NUMBER_AFTER_CUSTOMER, input))));
        }
        putIfPresent(args, "originSalesOrderId", first(kv, "originSalesOrderId", null));
        return new NlCommand(NlIntent.LIST_DOCUMENTS, args);
    }

    private NlCommand documentDetail(String input, Map<String, Object> kv, String docType) {
        Object id = first(kv, "id", first(kv, "orderId", lastNumber(input)));
        if (id == null) {
            throw NlArguments.invalid("查看单据详情缺少参数：id（单据 id）");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("docType", docType);
        args.put("id", id);
        return new NlCommand(NlIntent.GET_DOCUMENT, args);
    }

    private NlCommand summary(String input, Map<String, Object> kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        Object periodRaw = first(kv, "period", text(PERIOD, input));
        String period = periodRaw == null ? null : String.valueOf(periodRaw);
        String lower = input.toLowerCase(Locale.ROOT);
        if (period == null) {
            YearMonth current = YearMonth.from(clock.now());
            if (lower.contains("上月") || lower.contains("上个月")) {
                period = current.minusMonths(1).toString();
            } else if (lower.contains("本月") || lower.contains("这个月") || lower.contains("当前月")) {
                period = current.toString();
            }
        }
        putIfPresent(args, "period", period);
        putIfPresent(args, "warehouseId",
                first(kv, "warehouseId", number(NUMBER_AFTER_WAREHOUSE, input)));
        return new NlCommand(NlIntent.INVENTORY_SUMMARY, args);
    }

    private NlCommand purchaseOrder(String input, Map<String, Object> kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, "supplierId", first(kv, "supplierId", number(NUMBER_AFTER_SUPPLIER, input)));
        putIfPresent(args, "warehouseId", first(kv, "warehouseId", number(NUMBER_AFTER_WAREHOUSE, input)));
        putIfPresent(args, "bizDate", first(kv, "bizDate", text(BIZ_DATE, input)));
        putIfPresent(args, "remark", first(kv, "remark", text(REMARK, input)));
        args.put("lines", parseLines(input, false));
        return new NlCommand(NlIntent.CREATE_PURCHASE_ORDER, args);
    }

    private NlCommand salesOrder(String input, Map<String, Object> kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, "customerId", first(kv, "customerId", number(NUMBER_AFTER_CUSTOMER, input)));
        putIfPresent(args, "warehouseId", first(kv, "warehouseId", number(NUMBER_AFTER_WAREHOUSE, input)));
        putIfPresent(args, "bizDate", first(kv, "bizDate", text(BIZ_DATE, input)));
        putIfPresent(args, "remark", first(kv, "remark", text(REMARK, input)));
        args.put("lines", parseLines(input, true));
        return new NlCommand(NlIntent.CREATE_SALES_ORDER, args);
    }

    /**
     * 建单明细：按 {@code ；/;} 分段，每段接受
     * {@code SKU=5 数量=10 单价=1000} 或 {@code skuId=5 qty=10 unitPriceAmount=1000}。
     */
    private static List<Map<String, Object>> parseLines(String input, boolean withDiscount) {
        String[] segments = input.split("[;；]");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String segment : segments) {
            Map<String, Object> kv = keyValues(segment);
            Object skuId = first(kv, "skuId", number(NUMBER_AFTER_SKU, segment));
            if (skuId == null) {
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("skuId", skuId);
            putIfPresent(line, "qty", first(kv, "qty", number(LINE_QTY, segment)));
            putIfPresent(line, "unitPriceAmount",
                    first(kv, "unitPriceAmount", first(kv, "unitPrice", number(LINE_PRICE, segment))));
            if (withDiscount) {
                putIfPresent(line, "discountRate",
                        first(kv, "discountRate", number(LINE_DISCOUNT, segment)));
            }
            lines.add(line);
        }
        return lines;
    }

    // ─────────────────────────── 关键词与参数提取 ───────────────────────────

    private static String detectDocType(String lower) {
        if (lower.contains("进货单") || lower.contains("purchase-order")
                || lower.contains("purchase order") || lower.contains("采购单")) {
            return "PURCHASE";
        }
        if (lower.contains("出货单") || lower.contains("sales-order")
                || lower.contains("sales order") || lower.contains("销售单")) {
            return "SALES";
        }
        if (lower.contains("退货单") || lower.contains("return-order")
                || lower.contains("return order")) {
            return "RETURN";
        }
        return null;
    }

    private static String detectMasterDataType(String lower) {
        if (lower.contains("供应商") || lower.contains("supplier")) {
            return "SUPPLIER";
        }
        if (lower.contains("客户") || lower.contains("customer")) {
            return "CUSTOMER";
        }
        if (lower.contains("仓库") || lower.contains("warehouse")) {
            return "WAREHOUSE";
        }
        if (lower.contains("商品") || lower.contains("product")) {
            return "PRODUCT";
        }
        if (lower.contains("sku")) {
            return "SKU";
        }
        return null;
    }

    private static String statusText(String input) {
        String lower = input;
        if (lower.contains("已作废") || lower.contains("作废")) {
            return "VOIDED";
        }
        if (lower.contains("已驳回") || lower.contains("驳回")) {
            return "REJECTED";
        }
        if (lower.contains("已完成") || lower.contains("完结")) {
            return "COMPLETED";
        }
        if (lower.contains("部分")) {
            return "PARTIAL";
        }
        if (lower.contains("已审核") || lower.contains("审核通过") || lower.contains("已审")) {
            return "APPROVED";
        }
        if (lower.contains("待审") || lower.contains("已提交")) {
            return "SUBMITTED";
        }
        if (lower.contains("草稿")) {
            return "DRAFT";
        }
        return null;
    }

    private static Map<String, Object> keyValues(String input) {
        Map<String, Object> values = new LinkedHashMap<>();
        Matcher matcher = KEY_VALUE.matcher(input);
        while (matcher.find()) {
            String key = normalizeKey(matcher.group(1));
            String raw = matcher.group(2);
            if ((raw.startsWith("\"") && raw.endsWith("\""))
                    || (raw.startsWith("'") && raw.endsWith("'"))) {
                raw = raw.substring(1, raw.length() - 1);
            }
            values.put(key, raw);
        }
        validateNumbers(values);
        return values;
    }

    /** 键值对里的数字参数在这里就拒绝，别等到调度器才发现（错误消息也更靠近输入）。 */
    private static void validateNumbers(Map<String, Object> values) {
        for (String key : List.of("skuId", "warehouseId", "supplierId", "customerId", "productId",
                "partyId", "originSalesOrderId", "id", "page", "size", "qty",
                "unitPriceAmount", "unitPrice", "discountRate")) {
            Object raw = values.get(key);
            if (raw == null) {
                continue;
            }
            try {
                Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ex) {
                throw NlArguments.invalid("参数 %s 必须是整数：%s".formatted(key, raw));
            }
        }
    }

    private static String normalizeKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "skuid", "sku_id" -> "skuId";
            case "warehouseid", "warehouse_id" -> "warehouseId";
            case "supplierid", "supplier_id" -> "supplierId";
            case "customerid", "customer_id" -> "customerId";
            case "productid", "product_id" -> "productId";
            case "partyid", "party_id" -> "partyId";
            case "originalsalesorderid", "origin_sales_order_id", "originsalesorderid" -> "originSalesOrderId";
            case "doctype", "doc_type" -> "docType";
            case "bizdate", "biz_date" -> "bizDate";
            case "unitpriceamount", "unit_price_amount" -> "unitPriceAmount";
            case "unitprice", "unit_price" -> "unitPrice";
            case "discountrate", "discount_rate" -> "discountRate";
            case "onlyavailable", "only_available" -> "onlyAvailable";
            case "onlyenabled", "only_enabled" -> "onlyEnabled";
            case "ordertype", "order_type" -> "docType";
            case "orderid", "order_id" -> "id";
            case "skusize", "sku_size" -> "skuSize";
            default -> key;
        };
    }

    private static Object first(Map<String, Object> kv, String key, Object fallback) {
        Object value = kv.get(key);
        return value != null ? value : fallback;
    }

    private static void putIfPresent(Map<String, Object> args, String key, Object value) {
        if (value != null) {
            args.put(key, value);
        }
    }

    private static String text(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String number(Pattern pattern, String input) {
        return text(pattern, input);
    }

    private static String lastNumber(String input) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*$").matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean booleanArg(Map<String, Object> kv, String key, boolean fallback) {
        Object raw = kv.get(key);
        if (raw == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    static BusinessException unrecognized(String input) {
        String shown = input == null || input.isBlank() ? "（空指令）"
                : input.length() > 80 ? input.substring(0, 80) + "…" : input;
        return new BusinessException(ErrorCode.NL_COMMAND_UNRECOGNIZED,
                ("无法识别指令：%s。支持的意图：%s。示例：查询 SKU 12 的库存、"
                        + "进货单列表、账期 2026-10 进销存汇总、查询商品 关键字 T恤、"
                        + "创建进货单 供应商=1 仓库=1 SKU=5 数量=10 单价=1000")
                        .formatted(shown, SUPPORTED));
    }
}
