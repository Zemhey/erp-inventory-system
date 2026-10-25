package com.erp.mdm;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.shared.tenant.TenantSql;
import com.erp.shared.tenant.TenantTables;
import com.erp.support.CaseId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V18 新表的租户 SQL 改写用例。
 *
 * <p>这三张表不是“加了 tenant_id 列就完了”：它们必须被 {@code TenantSql} 认出来，
 * 否则查询会跨租户、写入会没有租户归属。这里钉住 SELECT / INSERT / UPDATE / DELETE
 * 四种真实仓储写法（与 {@code *Repository} 中的 SQL 同形）。
 */
@DisplayName("租户改写 · V18 主数据扩展表")
class MdmFieldsTenantSqlTest {

    @Test
    @CaseId("TC-MDM-052")
    @DisplayName("SELECT：customer_addresses 查询自动追加租户谓词")
    void select_isTenantScoped() {
        TenantSql.Rewritten result = TenantSql.rewrite("""
                SELECT id FROM customer_addresses WHERE customer_id = ? AND id = ?
                """, 7);

        assertThat(result.changed()).isTrue();
        assertThat(result.sql())
                .contains("customer_addresses.tenant_id = 7")
                .contains("customer_id = ? AND id = ?");
        assertThat(TenantSql.tablesOf(result.sql())).contains("customer_addresses");
    }

    @Test
    @CaseId("TC-MDM-053")
    @DisplayName("INSERT：warehouse_locations 列清单与 VALUES 都补 tenant_id")
    void insert_isTenantScoped() {
        TenantSql.Rewritten result = TenantSql.rewrite("""
                INSERT INTO warehouse_locations
                    (warehouse_id, location_code, name, capacity_qty, enabled, create_request_id)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, 7);

        assertThat(result.changed()).isTrue();
        assertThat(result.sql())
                .contains("create_request_id, tenant_id)")
                .contains("VALUES (?, ?, ?, ?, ?, ?, 7)")
                .contains("RETURNING id");
    }

    @Test
    @CaseId("TC-MDM-054")
    @DisplayName("UPDATE：product_attribute_values / warehouse_locations 更新都带租户谓词")
    void update_isTenantScoped() {
        TenantSql.Rewritten attributes = TenantSql.rewrite("""
                UPDATE product_attribute_values
                   SET attribute_value = ?
                 WHERE product_id = ? AND attribute_key = ?
                """, 7);
        assertThat(attributes.changed()).isTrue();
        assertThat(attributes.sql())
                .contains("product_attribute_values.tenant_id = 7");

        TenantSql.Rewritten locations = TenantSql.rewrite("""
                UPDATE warehouse_locations
                   SET capacity_qty = ?
                 WHERE warehouse_id = ? AND id = ?
                """, 7);
        assertThat(locations.changed()).isTrue();
        assertThat(locations.sql()).contains("warehouse_locations.tenant_id = 7");
    }

    @Test
    @CaseId("TC-MDM-055")
    @DisplayName("DELETE：product_attribute_values 全量替换前的删除不会跨租户")
    void delete_isTenantScoped() {
        TenantSql.Rewritten result = TenantSql.rewrite(
                "DELETE FROM product_attribute_values WHERE product_id = ?", 7);

        assertThat(result.changed()).isTrue();
        assertThat(result.sql())
                .contains("product_attribute_values.tenant_id = 7");
    }

    @Test
    @CaseId("TC-MDM-056")
    @DisplayName("清单：三张新表都登记在 TenantTables（否则 TenantSchemaIT 双向比对会红）")
    void newTables_areRegistered() {
        assertThat(TenantTables.ALL)
                .contains("customer_addresses", "warehouse_locations",
                        "product_attribute_values");
        for (String table : new String[]{"customer_addresses", "warehouse_locations",
                "product_attribute_values"}) {
            assertThat(TenantSql.rewrite("SELECT * FROM " + table + " WHERE id = ?", 7).sql())
                    .as("%s 必须被改写器识别", table)
                    .contains(table + ".tenant_id = 7");
        }
    }
}
