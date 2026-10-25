package com.erp.fin.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 业务单据只读出口（FR-V3-08 自动生成凭证的数据来源）。
 *
 * <p><b>为什么 fin 自己写 SQL 读三域表，而不是注入 PUR/SAL 的应用服务或仓储</b>：
 * 与 SET 读单据同一手法（见 {@code SettlementSourceRepository}）——
 * 跨模块仓储不对外开放（ArchUnit 规则 1/2/8 的精神），而"只读取数"没有写风险，
 * 一条聚合 SQL 把口径钉死在财务域自己手里。这里的每一条查询都只 SELECT。
 *
 * <p>金额是**整数分**（业务域口径，ADR-0005）；换算成凭证的"元"由调用方
 * {@code movePointLeft(2)} 完成，不在 SQL 里除 100 —— 避免两种口径在同一条链路上混用。
 */
@Repository
public class FinSourceRepository {

    /** 采购入库凭证源：po_receipts 合格金额 = 应借库存商品 = 应贷应付账款（系统暂无税项）。 */
    public record PurInDoc(String receiptNo, LocalDate bizDate, String supplierName,
                           long amountFen) {
    }

    /** 销售出库凭证源：收入 = 发货行金额合计；成本 = 本次出库流水结转的移动加权成本。 */
    public record SalOutDoc(String shipmentNo, LocalDate bizDate, String customerName,
                            long revenueFen, long costFen) {
    }

    /** 付款核销凭证源：payable_allocations 按 payment_no 聚合的一笔付款。 */
    public record PayDoc(String paymentNo, LocalDate bizDate, String supplierName,
                         long amountFen) {
    }

    /** 收款核销凭证源：customer_receipts 一张收款单。 */
    public record RcvDoc(String receiptNo, LocalDate bizDate, String customerName,
                         long amountFen) {
    }

    private final JdbcTemplate jdbc;

    public FinSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PurInDoc> purchaseReceipt(long receiptId) {
        return jdbc.query("""
                SELECT r.receipt_no, r.biz_date, s.name AS supplier_name, r.accepted_amount
                  FROM po_receipts r
                  JOIN suppliers s ON s.id = r.supplier_id
                 WHERE r.id = ?
                """, (rs, i) -> new PurInDoc(rs.getString("receipt_no"),
                rs.getObject("biz_date", LocalDate.class), rs.getString("supplier_name"),
                rs.getLong("accepted_amount")), receiptId).stream().findFirst();
    }

    public Optional<SalOutDoc> shipment(long shipmentId) {
        Optional<SalOutDoc> head = jdbc.query("""
                SELECT sp.shipment_no, o.biz_date, c.name AS customer_name,
                       (SELECT COALESCE(SUM(ROUND(l.planned_qty::numeric
                                    * ol.unit_price_amount * ol.discount_rate / 10000.0)), 0)::bigint
                          FROM shipment_lines l
                          JOIN sales_order_lines ol ON ol.id = l.sales_order_line_id
                         WHERE l.shipment_id = sp.id) AS revenue_fen
                  FROM so_shipments sp
                  JOIN sales_orders o ON o.id = sp.sales_order_id
                  JOIN customers c ON c.id = o.customer_id
                 WHERE sp.id = ?
                """, (rs, i) -> new SalOutDoc(rs.getString("shipment_no"),
                rs.getObject("biz_date", LocalDate.class), rs.getString("customer_name"),
                rs.getLong("revenue_fen"), 0L), shipmentId).stream().findFirst();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        // 成本 = 本次发货产生的 SALES_OUT 流水在成本引擎里的结转额（request_id 前缀 = 发货单号）。
        // 成本引擎未重算时这里取到 0：成本分录按简报口径"0 金额不写行"整体缺席，
        // 收入侧凭证依旧平衡（应收对收入），重算后可由红冲/补录跟进（报告口径）。
        Long cost = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cl.total_cost_amount), 0)
                  FROM cost_layers cl
                  JOIN inventory_ledger l ON l.id = cl.ledger_id
                 WHERE l.biz_type = 'SALES_OUT' AND l.request_id LIKE ?
                """, Long.class, head.get().shipmentNo() + ":%");
        SalOutDoc doc = head.get();
        return Optional.of(new SalOutDoc(doc.shipmentNo(), doc.bizDate(), doc.customerName(),
                doc.revenueFen(), cost == null ? 0L : cost));
    }

    /**
     * 付款单凭证源。
     *
     * <p>付款没有独立头表（见 V10：付款 = payable_allocations 的一族行），
     * sourceId 约定为**该笔付款的第一条核销明细 id**（pay() 钩子保证稳定），
     * 这里顺着它取 payment_no 再聚合整笔付款金额（payment_amount 恒等于 Σamount）。
     */
    public Optional<PayDoc> payment(long firstAllocationId) {
        return jdbc.query("""
                SELECT a.payment_no, a.biz_date, s.name AS supplier_name,
                       MAX(a.payment_amount) AS amount_fen
                  FROM payable_allocations a
                  JOIN suppliers s ON s.id = a.supplier_id
                 WHERE a.payment_no = (SELECT payment_no FROM payable_allocations WHERE id = ?)
                 GROUP BY a.payment_no, a.biz_date, s.name
                """, (rs, i) -> new PayDoc(rs.getString("payment_no"),
                rs.getObject("biz_date", LocalDate.class), rs.getString("supplier_name"),
                rs.getLong("amount_fen")), firstAllocationId).stream().findFirst();
    }

    public Optional<RcvDoc> customerReceipt(long receiptId) {
        return jdbc.query("""
                SELECT r.receipt_no, r.biz_date, c.name AS customer_name, r.amount AS amount_fen
                  FROM customer_receipts r
                  JOIN customers c ON c.id = r.customer_id
                 WHERE r.id = ?
                """, (rs, i) -> new RcvDoc(rs.getString("receipt_no"),
                rs.getObject("biz_date", LocalDate.class), rs.getString("customer_name"),
                rs.getLong("amount_fen")), receiptId).stream().findFirst();
    }

    // ─────────────────────── 发票超额校验 / 总览卡片 ───────────────────────

    /** 源单据的"可开票金额"（价税合计口径 = 单据总额）。单据不存在返回空。 */
    public Optional<Long> sourceDocAmount(String sourceType, long sourceId) {
        String sql = switch (sourceType) {
            // 业务域分数口径：换算成元交给调用方比较（movePointLeft 由服务层做，这里返回分）
            case "PURCHASE_ORDER" -> "SELECT total_amount FROM purchase_orders WHERE id = ?";
            case "SALES_ORDER" -> "SELECT total_amount FROM sales_orders WHERE id = ?";
            default -> null;
        };
        if (sql == null) {
            return Optional.empty();
        }
        return jdbc.query(sql, (rs, i) -> rs.getLong(1), sourceId)
                .stream().findFirst();
    }

    /** 应收总额（customer_receivables.balance 合计，分）。 */
    public long receivableBalanceFen() {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM customer_receivables", Long.class);
        return n == null ? 0L : n;
    }

    /** 应付总额（supplier_payables.balance_amount 合计，分）。 */
    public long payableBalanceFen() {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance_amount), 0) FROM supplier_payables", Long.class);
        return n == null ? 0L : n;
    }
}
