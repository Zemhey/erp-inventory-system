package com.erp.nl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.erp.nl.application.StubNlCommandParser;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * stub 解析器单测：不启动 Spring、不连库、不依赖外网。
 *
 * <p>这批用例守的是"离线可复现"：解析结果是确定的，演示与 CI 不会因为任何外部服务而变红。
 */
@DisplayName("NL · stub 解析器")
class StubNlCommandParserTest {

    private final StubNlCommandParser parser =
            new StubNlCommandParser(() -> LocalDateTime.of(2026, 10, 23, 10, 0));

    @Test
    @CaseId("TC-NL-001")
    @DisplayName("中文/键值对指令解析为正确意图与参数（库存/列表/详情/主数据/帮助）")
    void parsesCoreIntents() {
        NlCommand inventory = parser.parse("查询 SKU 12 在仓库 3 的库存");
        assertThat(inventory.intent()).isEqualTo(NlIntent.QUERY_INVENTORY);
        assertThat(inventory.arguments())
                .containsEntry("skuId", "12")
                .containsEntry("warehouseId", "3")
                .containsEntry("onlyAvailable", false);

        NlCommand list = parser.parse("进货单列表 状态 已审核 第2页 每页5");
        assertThat(list.intent()).isEqualTo(NlIntent.LIST_DOCUMENTS);
        assertThat(list.arguments())
                .containsEntry("docType", "PURCHASE")
                .containsEntry("status", "APPROVED")
                .containsEntry("page", "2")
                .containsEntry("size", "5");

        NlCommand detail = parser.parse("查看进货单详情 id=7");
        assertThat(detail.intent()).isEqualTo(NlIntent.GET_DOCUMENT);
        assertThat(detail.arguments()).containsEntry("docType", "PURCHASE").containsEntry("id", "7");

        NlCommand products = parser.parse("查询商品 关键字 T恤");
        assertThat(products.intent()).isEqualTo(NlIntent.QUERY_MASTER_DATA);
        assertThat(products.arguments()).containsEntry("type", "PRODUCT").containsEntry("keyword", "T恤");

        assertThat(parser.parse("帮助").intent()).isEqualTo(NlIntent.HELP);
        assertThat(parser.parse("help").intent()).isEqualTo(NlIntent.HELP);

        NlCommand machine = parser.parse("query_inventory skuId=12 warehouseId=3");
        assertThat(machine.intent()).isEqualTo(NlIntent.QUERY_INVENTORY);
        assertThat(machine.arguments()).containsEntry("skuId", "12").containsEntry("warehouseId", "3");
    }

    @Test
    @CaseId("TC-NL-002")
    @DisplayName("本月/上月由注入时钟决定；建单多行用分号分隔；缺省账期不编造")
    void parsesRelativePeriodAndMultiLineCreate() {
        assertThat(parser.parse("进销存汇总 本月").arguments()).containsEntry("period", "2026-10");
        assertThat(parser.parse("上月进销存汇总").arguments()).containsEntry("period", "2026-09");
        assertThat(parser.parse("账期 2026-08 进销存汇总").arguments())
                .containsEntry("period", "2026-08");
        assertThat(parser.parse("进销存汇总").arguments()).doesNotContainKey("period");

        NlCommand purchase = parser.parse(
                "创建进货单 供应商=1 仓库=2 日期=2026-10-20 "
                        + "SKU=5 数量=10 单价=1000；SKU=6 数量=2 单价=500");
        assertThat(purchase.intent()).isEqualTo(NlIntent.CREATE_PURCHASE_ORDER);
        assertThat(purchase.arguments())
                .containsEntry("supplierId", "1")
                .containsEntry("warehouseId", "2")
                .containsEntry("bizDate", "2026-10-20");
        List<Map<String, Object>> lines = linesOf(purchase);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0))
                .containsEntry("skuId", "5")
                .containsEntry("qty", "10")
                .containsEntry("unitPriceAmount", "1000");
        assertThat(lines.get(1)).containsEntry("skuId", "6").containsEntry("qty", "2");

        NlCommand sales = parser.parse(
                "创建出货单 客户=3 仓库=2 SKU=5 数量=2 单价=12900 折扣=9000");
        assertThat(sales.intent()).isEqualTo(NlIntent.CREATE_SALES_ORDER);
        assertThat(sales.arguments()).containsEntry("customerId", "3");
        assertThat(linesOf(sales).get(0)).containsEntry("discountRate", "9000");

        NlCommand noLine = parser.parse("创建进货单 供应商=1 仓库=2");
        assertThat(linesOf(noLine)).isEmpty();
    }

    @Test
    @CaseId("TC-NL-003")
    @DisplayName("空/无法识别 → NL_COMMAND_UNRECOGNIZED；非法数字 → NL_COMMAND_INVALID_ARGUMENT")
    void rejectsUnrecognizedAndInvalidArguments() {
        BusinessException blank = catchThrowableOfType(() -> parser.parse("  "), BusinessException.class);
        assertThat(blank.getCode()).isEqualTo(ErrorCode.NL_COMMAND_UNRECOGNIZED);

        BusinessException unknown = catchThrowableOfType(
                () -> parser.parse("今天天气怎么样"), BusinessException.class);
        assertThat(unknown.getCode()).isEqualTo(ErrorCode.NL_COMMAND_UNRECOGNIZED);
        assertThat(unknown.getMessage()).contains("支持的意图").contains("query_inventory");

        BusinessException badNumber = catchThrowableOfType(
                () -> parser.parse("query_inventory skuId=abc"), BusinessException.class);
        assertThat(badNumber.getCode()).isEqualTo(ErrorCode.NL_COMMAND_INVALID_ARGUMENT);
        assertThat(badNumber.getMessage()).contains("skuId").contains("整数");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> linesOf(NlCommand command) {
        return (List<Map<String, Object>>) command.arguments().get("lines");
    }
}
