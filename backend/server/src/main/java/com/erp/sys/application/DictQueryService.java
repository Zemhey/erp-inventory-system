package com.erp.sys.application;

import com.erp.sys.domain.DictItem;
import com.erp.sys.domain.DictType;
import com.erp.sys.repository.DictRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 字典查询服务（只读）。 */
@Service
public class DictQueryService {

    private final DictRepository repository;

    public DictQueryService(DictRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DictType> types(boolean onlyEnabled) {
        return repository.listTypes(onlyEnabled);
    }

    /** 按类型编码取字典项；类型不存在返回 404（而不是空数组）。 */
    @Transactional(readOnly = true)
    public List<DictItem> items(String typeCode, boolean onlyEnabled) {
        if (typeCode == null || typeCode.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "字典类型编码不能为空");
        }
        repository.findType(typeCode.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_DICT_TYPE_NOT_FOUND,
                        "字典类型不存在：code=%s".formatted(typeCode)));
        return repository.listItems(typeCode.trim(), onlyEnabled);
    }
}
