package com.erp.sys.application;

import com.erp.sys.domain.LoginLog;
import com.erp.sys.repository.LoginLogRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 登录日志查询服务（FR-V3-29）：只读。 */
@Service
public class LoginLogQueryService {

    private final LoginLogRepository repository;

    public LoginLogQueryService(LoginLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<LoginLog> recent(int limit) {
        int safe = Math.max(1, Math.min(limit, 500));
        return repository.list(safe);
    }

    @Transactional(readOnly = true)
    public List<LoginLog> online(int minutes) {
        return repository.recentOnline(Math.max(1, minutes));
    }
}
