package com.erp.mdm.domain;

import java.time.LocalDate;

/**
 * SKU 查询条件（FR-MDM-08）。
 *
 * <p>放在 domain 而不是 repository：Controller 需要构造它，
 * 而 ArchUnit 禁止 api 层依赖 repository；反过来 repository 也不能依赖 application。
 * 纯查询条件放 domain 是唯一不破坏分层方向的落点。
 */
public record SkuFilter(Long productId, String color, String skuSize, String seasonCode,
                        String bandCode, LocalDate launchDateFrom, LocalDate launchDateTo,
                        Integer seasonYear) {
}
