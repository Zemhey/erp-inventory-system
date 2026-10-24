package com.erp.sys.domain;

/**
 * 字典类型（如 COLOR / SIZE / SEASON / UNIT）。
 *
 * <p>为什么本模块要自带字典：V2-5 的审计查询要按"操作类型 / 结果"过滤，
 * 前端需要一份稳定的枚举候选；把这份候选硬编码在前端，
 * 每次新增动作都要改三个端。字典表让"可选值"成为一个可查询的资源。
 *
 * @param id          主键
 * @param code        类型编码（唯一）
 * @param name        展示名
 * @param description 说明
 * @param enabled     是否启用
 */
public record DictType(long id, String code, String name, String description, boolean enabled) {
}
