package com.erp.rpt.application;

import com.erp.rpt.domain.DocDetail;
import com.erp.rpt.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单据详情服务（FR-V3-04）。 */
@Service
public class DocumentService {

    private final DocumentRepository repository;

    public DocumentService(DocumentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public DocDetail detail(String type, long id) {
        return repository.getDetail(type, id);
    }
}
