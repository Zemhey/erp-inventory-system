package com.erp.rpt.application;

import com.erp.rpt.repository.DashboardRepository;
import com.erp.rpt.repository.TodoRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 待办中心服务（FR-V3-03）：4 个 Tab。 */
@Service
public class TodoService {

    private final TodoRepository todoRepository;
    private final DashboardRepository dashboardRepository;

    public TodoService(TodoRepository todoRepository, DashboardRepository dashboardRepository) {
        this.todoRepository = todoRepository;
        this.dashboardRepository = dashboardRepository;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String type, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return switch (type) {
            case "approval" -> dashboardRepository.recentPendingApprovals(safe);
            case "purchase-receipt" -> todoRepository.purchaseToReceive(safe);
            case "sales-ship" -> todoRepository.salesToShip(safe);
            case "low-stock" -> todoRepository.lowStock(safe);
            default -> List.of();
        };
    }
}
