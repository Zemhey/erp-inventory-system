package com.erp.mdm.application;

import com.erp.mdm.domain.ProductAttribute;
import com.erp.mdm.repository.ProductAttributeRepository;
import com.erp.mdm.repository.ProductRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品规格属性应用服务（V18）。
 *
 * <p>属性是款级键值对（面料/版型/执行标准…），与 SKU 的颜色×尺码矩阵正交：
 * 写属性不会生成/删除 SKU，也不参与 {@code uq_sku_variant}。
 * 全量替换天然幂等，重复 PUT 同一份数据结果一致。
 */
@Service
public class ProductAttributeApplicationService {

    public static final int MAX_ENTRIES = 50;
    public static final int MAX_KEY_LENGTH = 32;
    public static final int MAX_VALUE_LENGTH = 256;

    private final ProductAttributeRepository attributes;
    private final ProductRepository products;

    public ProductAttributeApplicationService(ProductAttributeRepository attributes,
                                              ProductRepository products) {
        this.attributes = attributes;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<ProductAttribute> list(long productId) {
        requireProduct(productId);
        return attributes.findByProduct(productId);
    }

    @Transactional
    public List<ProductAttribute> replace(long productId, List<ProductAttribute> requested) {
        requireProduct(productId);
        Map<String, String> normalized = normalize(requested);
        List<ProductAttribute> values = normalized.entrySet().stream()
                .map(e -> new ProductAttribute(e.getKey(), e.getValue()))
                .toList();
        attributes.replaceAll(productId, values);
        return attributes.findByProduct(productId);
    }

    private void requireProduct(long productId) {
        products.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "商品不存在：id=%d".formatted(productId)));
    }

    private static Map<String, String> normalize(List<ProductAttribute> requested) {
        if (requested == null) {
            throw new BusinessException(ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID,
                    "规格属性列表不能为空；清空请传空数组");
        }
        if (requested.size() > MAX_ENTRIES) {
            throw new BusinessException(ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID,
                    "规格属性最多 %d 条：实际=%d".formatted(MAX_ENTRIES, requested.size()));
        }
        Map<String, String> seen = new LinkedHashMap<>();
        for (ProductAttribute attribute : requested) {
            if (attribute == null) {
                throw new BusinessException(ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID,
                        "规格属性不允许 null 项");
            }
            String key = ProductApplicationService.trimToNull(attribute.key());
            String value = ProductApplicationService.trimToNull(attribute.value());
            if (key == null || key.length() > MAX_KEY_LENGTH) {
                throw new BusinessException(ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID,
                        "属性键必须为 1~%d 个字符：%s"
                                .formatted(MAX_KEY_LENGTH, attribute.key()));
            }
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new BusinessException(ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID,
                        "属性值必须为 1~%d 个字符：key=%s"
                                .formatted(MAX_VALUE_LENGTH, key));
            }
            if (seen.putIfAbsent(key, value) != null) {
                throw new BusinessException(ErrorCode.MDM_PRODUCT_ATTRIBUTE_INVALID,
                        "属性键重复：%s".formatted(key));
            }
        }
        return seen;
    }
}
