package com.erp.sys.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.sys.domain.SystemConfig;
import com.erp.sys.repository.SystemConfigRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 系统参数应用服务（FR-V3-24）：按组展示 + 编辑值。 */
@Service
public class SystemConfigService {

    private final SystemConfigRepository repository;

    public SystemConfigService(SystemConfigRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<SystemConfig> list(String group) {
        return repository.listByGroup(group);
    }

    @Transactional
    public SystemConfig updateValue(long id, String value) {
        List<SystemConfig> all = repository.listByGroup(null);
        SystemConfig target = all.stream().filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_CONFIG_NOT_FOUND,
                        "参数不存在：id=" + id));
        repository.updateValue(id, value);
        return new SystemConfig(target.id(), target.configGroup(), target.configKey(), value,
                target.valueType(), target.remark(), target.createdAt(), target.updatedAt());
    }
}
