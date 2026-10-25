package com.erp.rpt.application;

import com.erp.rpt.repository.SearchRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 全局搜索服务（FR-V3-05）。 */
@Service
public class SearchService {

    private final SearchRepository repository;

    public SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> search(String keyword) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (keyword == null || keyword.isBlank()) {
            result.put("products", List.of());
            result.put("customers", List.of());
            result.put("suppliers", List.of());
            result.put("orders", List.of());
            return result;
        }
        int limit = 20;
        result.put("products", repository.products(keyword, limit));
        result.put("customers", repository.customers(keyword, limit));
        result.put("suppliers", repository.suppliers(keyword, limit));
        result.put("orders", repository.orders(keyword, limit));
        return result;
    }
}
