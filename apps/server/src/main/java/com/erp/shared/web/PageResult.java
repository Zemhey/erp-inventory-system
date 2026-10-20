package com.erp.shared.web;

import java.util.List;

/**
 * 分页结果（契约：{@code ApiPageResponse.data} = { items, total, page, size }）。
 *
 * <p>为什么不用 Spring 的 {@code Page}：Spring Data 的分页对象把"分页参数"
 * 与"仓储实现"耦合在一起，而本项目用 JdbcTemplate 直写 SQL。
 * 自建一个只含四个字段的记录类，边界更清楚，也免得多引入一套抽象。
 *
 * @param items 当前页数据
 * @param total 满足条件的总行数（不是当前页行数）
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 * @param <T>   元素类型
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    /** 契约上限：size ∈ [1, 200]。 */
    private static final int MAX_SIZE = 200;

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 把外部传入的页码/条数规整到合法区间。
     *
     * <p>刻意<b>不</b>对越界参数抛 400：分页参数越界属客户端习惯问题，
     * 报错只会让前端到处补 if；钳制到合法区间同时返回真实的 page/size，
     * 调用方可据响应纠正。
     */
    public static int normalizeSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    /** 转成 SQL 的 OFFSET。 */
    public static long offset(int page, int size) {
        return (long) (normalizePage(page) - 1) * normalizeSize(size);
    }
}
