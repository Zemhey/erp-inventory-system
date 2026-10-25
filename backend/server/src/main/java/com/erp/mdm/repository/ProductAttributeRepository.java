package com.erp.mdm.repository;

import com.erp.mdm.domain.ProductAttribute;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 商品规格属性仓储（V18）。
 *
 * <p>写入采用“事务内全量替换”：先 DELETE 再逐条 INSERT（最多 50 条），
 * 语义简单且天然幂等；唯一索引 {@code uq_product_attribute_key} 兜底重复键。
 */
@Repository
public class ProductAttributeRepository {

    private final JdbcTemplate jdbc;

    public ProductAttributeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProductAttribute> findByProduct(long productId) {
        return jdbc.query("""
                SELECT attribute_key, attribute_value
                  FROM product_attribute_values
                 WHERE product_id = ?
                 ORDER BY attribute_key
                """, (rs, i) -> new ProductAttribute(
                        rs.getString("attribute_key"), rs.getString("attribute_value")),
                productId);
    }

    public void replaceAll(long productId, List<ProductAttribute> attributes) {
        jdbc.update("DELETE FROM product_attribute_values WHERE product_id = ?", productId);
        for (ProductAttribute attribute : attributes) {
            jdbc.update("""
                    INSERT INTO product_attribute_values
                        (product_id, attribute_key, attribute_value)
                    VALUES (?, ?, ?)
                    """, productId, attribute.key(), attribute.value());
        }
    }
}
