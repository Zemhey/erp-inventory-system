package com.erp.sys.domain;

/**
 * 字典项。
 *
 * @param id      主键
 * @param typeCode 所属类型编码（返回时带上，前端不必再关联一次）
 * @param code    项编码
 * @param label   展示文案
 * @param sortNo  排序号（小在前）
 * @param enabled 是否启用
 */
public record DictItem(long id, String typeCode, String code, String label, int sortNo,
                       boolean enabled) {
}
