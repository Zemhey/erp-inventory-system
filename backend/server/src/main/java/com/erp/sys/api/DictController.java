package com.erp.sys.api;

import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.DictQueryService;
import com.erp.sys.domain.DictItem;
import com.erp.sys.domain.DictType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字典查询接口（契约 {@code /dicts}，只读）。
 *
 * <p>本期只提供读：字典的维护界面与权限码留到 V2-7（参数/字典期）。
 * 一个"能写但没有审计、没有权限细化、没有界面"的写入口比没有更糟。
 */
@RestController
@RequestMapping("/api/v1/dicts")
public class DictController {

    private final DictQueryService dicts;

    public DictController(DictQueryService dicts) {
        this.dicts = dicts;
    }

    @GetMapping
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> types(
            @RequestParam(defaultValue = "true") boolean onlyEnabled) {
        List<Map<String, Object>> items = dicts.types(onlyEnabled).stream()
                .map(DictController::typeToMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    /**
     * 某类型的字典项。
     *
     * <p>路径故意用 {@code /dicts/{typeCode}/items} 而不是 {@code /dicts/items?type=}：
     * 前者在日志与审计里能一眼看出查的是哪个类型。
     */
    @GetMapping("/{typeCode}/items")
    @RequirePermission("sys:read")
    public ResponseEntity<Map<String, Object>> items(
            @PathVariable String typeCode,
            @RequestParam(defaultValue = "true") boolean onlyEnabled) {
        List<Map<String, Object>> items = dicts.items(typeCode, onlyEnabled).stream()
                .map(DictController::itemToMap).toList();
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    private static Map<String, Object> typeToMap(DictType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("code", t.code());
        m.put("name", t.name());
        m.put("description", t.description());
        m.put("enabled", t.enabled());
        return m;
    }

    private static Map<String, Object> itemToMap(DictItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("typeCode", i.typeCode());
        m.put("code", i.code());
        m.put("label", i.label());
        m.put("sortNo", i.sortNo());
        m.put("enabled", i.enabled());
        return m;
    }
}
