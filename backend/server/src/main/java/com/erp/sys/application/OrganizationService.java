package com.erp.sys.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.sys.domain.Department;
import com.erp.sys.domain.Position;
import com.erp.sys.repository.OrganizationRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织架构应用服务（FR-V3-23）：部门树 + 岗位。
 *
 * <p>部门树在 Java 里组装：一次取出整租户的部门，再按 parent_id 挂成树，
 * 前端拿到的就是可直接渲染的嵌套结构。层级很小，没必要上递归 CTE。
 */
@Service
public class OrganizationService {

    private final OrganizationRepository repository;

    public OrganizationService(OrganizationRepository repository) {
        this.repository = repository;
    }

    // ─────────────────────────── 部门 ───────────────────────────

    @Transactional(readOnly = true)
    public List<Department> departments(boolean onlyEnabled) {
        return repository.listDepartments(onlyEnabled);
    }

    /** 部门树（嵌套 children）。只暴露给前端渲染，不进写路径。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> departmentTree(boolean onlyEnabled) {
        List<Department> all = repository.listDepartments(onlyEnabled);
        Map<Long, List<Department>> byParent = all.stream()
                .collect(Collectors.groupingBy(d -> d.parentId() == null ? -1L : d.parentId()));
        return buildChildren(-1L, byParent);
    }

    private List<Map<String, Object>> buildChildren(long parentId,
                                                   Map<Long, List<Department>> byParent) {
        List<Department> children = byParent.getOrDefault(parentId, List.of());
        return children.stream().map(d -> {
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("id", d.id());
            node.put("parentId", d.parentId());
            node.put("code", d.code());
            node.put("name", d.name());
            node.put("sortNo", d.sortNo());
            node.put("leaderUserId", d.leaderUserId());
            node.put("enabled", d.enabled());
            node.put("children", buildChildren(d.id(), byParent));
            return node;
        }).collect(Collectors.toList());
    }

    @Transactional
    public Department createDepartment(String code, String name, Long parentId, int sortNo) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "部门编码不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "部门名称不能为空");
        }
        if (repository.departmentCodeExists(code.trim())) {
            throw new BusinessException(ErrorCode.SYS_DEPARTMENT_CODE_DUPLICATED,
                    "部门编码已存在：" + code);
        }
        long id = repository.insertDepartment(code.trim(), name.trim(), parentId, sortNo);
        return repository.findDepartment(id).orElseThrow(
                () -> new BusinessException(ErrorCode.SYS_DEPARTMENT_NOT_FOUND, "部门创建后未找到"));
    }

    @Transactional
    public Department updateDepartment(long id, String name, Long parentId, int sortNo,
                                      boolean enabled) {
        Department exist = repository.findDepartment(id).orElseThrow(
                () -> new BusinessException(ErrorCode.SYS_DEPARTMENT_NOT_FOUND,
                        "部门不存在：id=" + id));
        if (parentId != null && parentId == id) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "部门不能挂载到自己下");
        }
        repository.updateDepartment(id, name, parentId, sortNo, enabled);
        return repository.findDepartment(id).orElse(exist);
    }

    @Transactional
    public void deleteDepartment(long id) {
        repository.findDepartment(id).orElseThrow(
                () -> new BusinessException(ErrorCode.SYS_DEPARTMENT_NOT_FOUND,
                        "部门不存在：id=" + id));
        if (repository.hasChildDepartments(id)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "该部门下还有子部门，无法删除");
        }
        if (repository.departmentInUse(id)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "该部门下还有员工，无法删除");
        }
        repository.deleteDepartment(id);
    }

    // ─────────────────────────── 岗位 ───────────────────────────

    @Transactional(readOnly = true)
    public List<Position> positions(Long departmentId) {
        return repository.listPositions(departmentId);
    }

    @Transactional
    public Position createPosition(long departmentId, String code, String name, int level) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "岗位编码不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "岗位名称不能为空");
        }
        if (repository.positionCodeExists(code.trim())) {
            throw new BusinessException(ErrorCode.SYS_POSITION_CODE_DUPLICATED,
                    "岗位编码已存在：" + code);
        }
        long id = repository.insertPosition(departmentId, code.trim(), name.trim(), level);
        return repository.listPositions(departmentId).stream()
                .filter(p -> p.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_POSITION_NOT_FOUND,
                        "岗位创建后未找到"));
    }

    @Transactional
    public Position updatePosition(long id, long departmentId, String name, int level,
                                  boolean enabled) {
        if (repository.listPositions(null).stream().noneMatch(p -> p.id() == id)) {
            throw new BusinessException(ErrorCode.SYS_POSITION_NOT_FOUND, "岗位不存在：id=" + id);
        }
        repository.updatePosition(id, name, level, enabled);
        return repository.listPositions(departmentId).stream()
                .filter(p -> p.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_POSITION_NOT_FOUND,
                        "岗位更新后未找到"));
    }

    @Transactional
    public void deletePosition(long id) {
        if (repository.listPositions(null).stream().noneMatch(p -> p.id() == id)) {
            throw new BusinessException(ErrorCode.SYS_POSITION_NOT_FOUND, "岗位不存在：id=" + id);
        }
        if (repository.positionInUse(id)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "该岗位下还有员工，无法删除");
        }
        repository.deletePosition(id);
    }
}
