package com.erp.rma.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.rma.domain.ReturnOrderLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退货单接口（契约：{@code /return-orders}）。
 *
 * <p>状态变更走子路径动作，与进货/出货一致；每个动作单独声明权限，
 * 而不是在类上加一个粗粒度 {@code rma:*} —— 销售员能建单但不能审核，
 * 这件事必须在方法签名上看得见。
 */
@RestController
@RequestMapping("/api/v1/return-orders")
public class ReturnOrderController {

    private final ReturnOrderApplicationService returnOrders;

    public ReturnOrderController(ReturnOrderApplicationService returnOrders) {
        this.returnOrders = returnOrders;
    }

    public record LineRequest(
            @NotNull Long originLineId,
            @NotNull @Min(1) Integer qty,
            @Size(max = 64) String batchNo,
            LocalDate productionDate,
            LocalDate expiryDate) {
    }

    public record CreateRequest(
            @NotNull Long originSalesOrderId,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    @GetMapping
    @RequirePermission("rma:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long originSalesOrderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(
                returnOrders.page(status, customerId, originSalesOrderId, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("rma:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returnOrders.require(id))));
    }

    @PostMapping
    @RequirePermission("rma:create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        List<ReturnOrderApplicationService.CreateLineCommand> lines = req.lines().stream()
                .map(l -> new ReturnOrderApplicationService.CreateLineCommand(
                        l.originLineId(), l.qty(), l.batchNo(), l.productionDate(), l.expiryDate()))
                .toList();
        ReturnOrder o = returnOrders.create(new ReturnOrderApplicationService.CreateCommand(
                req.originSalesOrderId(), req.bizDate(), req.remark(), lines, requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(o)));
    }

    // 契约规定所有写操作都带 X-Request-Id（contract-check 会校验）。
    // 这四个状态类动作本身由状态机 + 乐观锁保证重复调用不会二次生效，
    // 因此幂等键只用于请求追踪与并发排障，不落库。
    @PostMapping("/{id}/submit")
    @RequirePermission("rma:submit")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returnOrders.submit(id))));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("rma:approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returnOrders.approve(id))));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("rma:reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returnOrders.reject(id))));
    }

    /** 整单入库：无请求体，数量与批次在建单时已确定。 */
    @PostMapping("/{id}/receive")
    @RequirePermission("rma:receive")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returnOrders.receive(id, requestId))));
    }

    @PostMapping("/{id}/void")
    @RequirePermission("rma:void")
    public ResponseEntity<Map<String, Object>> voidOrder(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returnOrders.voidOrder(id))));
    }

    /** 响应字段严格对齐契约 {@code ReturnOrder}；{@code receiveRequestId} 属服务端幂等痕迹，不对外暴露。 */
    private static Map<String, Object> toMap(ReturnOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("orderNo", o.orderNo());
        m.put("status", o.status().name());
        m.put("originSalesOrderId", o.originSalesOrderId());
        m.put("customerId", o.customerId());
        m.put("warehouseId", o.warehouseId());
        m.put("bizDate", o.bizDate() == null ? null : o.bizDate().toString());
        m.put("totalAmount", o.totalAmount());
        m.put("totalQty", o.totalQty());
        m.put("period", o.period());
        m.put("remark", o.remark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ReturnOrderLine l : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("originLineId", l.originLineId());
            lm.put("skuId", l.skuId());
            lm.put("qty", l.qty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("discountRate", l.discountRate());
            lm.put("amount", l.amount());
            lm.put("batchNo", l.batchNo());
            lm.put("productionDate", l.productionDate() == null ? null : l.productionDate().toString());
            lm.put("expiryDate", l.expiryDate() == null ? null : l.expiryDate().toString());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }
}
