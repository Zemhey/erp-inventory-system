package com.erp.mdm.repository;

import com.erp.mdm.domain.CustomerAddress;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 客户收货地址仓储（V18）。
 *
 * <p>SQL 文本统一交给 {@code TenantSql} 注入租户谓词/列，不在业务代码里手写 tenant_id。
 * 默认唯一由部分唯一索引 {@code uq_customer_address_default} 兜底，
 * 应用层在切换前先锁客户行（{@link CustomerRepository#findByIdForUpdate}）。
 */
@Repository
public class CustomerAddressRepository {

    private static final RowMapper<CustomerAddress> MAPPER = (rs, i) -> new CustomerAddress(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            rs.getString("address_key"),
            rs.getString("recipient_name"),
            rs.getString("phone"),
            rs.getString("province"),
            rs.getString("city"),
            rs.getString("district"),
            rs.getString("detail_address"),
            rs.getBoolean("is_default"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public CustomerAddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long customerId, String addressKey, String recipientName, String phone,
                       String province, String city, String district, String detailAddress,
                       boolean isDefault, boolean enabled, String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO customer_addresses
                            (customer_id, address_key, recipient_name, phone, province, city,
                             district, detail_address, is_default, enabled, create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    customerId, addressKey, recipientName, phone, province, city,
                    district, detailAddress, isDefault, enabled, requestId);
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicated = DbErrors.translate(ex, "uq_customer_address_key",
                    ErrorCode.MDM_ADDRESS_DUPLICATED,
                    "地址键已存在：addressKey=%s".formatted(addressKey));
            if (duplicated != null) {
                throw duplicated;
            }
            BusinessException noSecondDefault = DbErrors.translate(ex, "uq_customer_address_default",
                    ErrorCode.MDM_ADDRESS_DUPLICATED,
                    "该客户已有默认地址，默认地址只能有一个");
            if (noSecondDefault != null) {
                throw noSecondDefault;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex,
                    "uq_customer_address_create_request", ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建地址：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            throw ex;
        }
    }

    public Optional<CustomerAddress> findById(long customerId, long id) {
        return jdbc.query("""
                SELECT id, customer_id, address_key, recipient_name, phone, province, city,
                       district, detail_address, is_default, enabled
                  FROM customer_addresses
                 WHERE customer_id = ? AND id = ?
                """, MAPPER, customerId, id).stream().findFirst();
    }

    public Optional<CustomerAddress> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, customer_id, address_key, recipient_name, phone, province, city,
                       district, detail_address, is_default, enabled
                  FROM customer_addresses
                 WHERE create_request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    public List<CustomerAddress> findAll(long customerId, boolean onlyEnabled) {
        String sql = """
                SELECT id, customer_id, address_key, recipient_name, phone, province, city,
                       district, detail_address, is_default, enabled
                  FROM customer_addresses
                 WHERE customer_id = ?
                """ + (onlyEnabled ? " AND enabled" : "") + " ORDER BY is_default DESC, id";
        return jdbc.query(sql, MAPPER, customerId);
    }

    public int countEnabled(long customerId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_addresses WHERE customer_id = ? AND enabled
                """, Integer.class, customerId);
        return n == null ? 0 : n;
    }

    public int clearDefault(long customerId) {
        return jdbc.update("""
                UPDATE customer_addresses
                   SET is_default = FALSE, updated_at = now()
                 WHERE customer_id = ? AND is_default
                """, customerId);
    }

    public boolean markDefault(long customerId, long id) {
        return jdbc.update("""
                UPDATE customer_addresses
                   SET is_default = TRUE, updated_at = now()
                 WHERE customer_id = ? AND id = ? AND enabled
                """, customerId, id) == 1;
    }

    public boolean update(long customerId, long id, String recipientName, String phone,
                          String province, String city, String district, String detailAddress,
                          boolean isDefault, boolean enabled) {
        return jdbc.update("""
                UPDATE customer_addresses
                   SET recipient_name = ?, phone = ?, province = ?, city = ?,
                       district = ?, detail_address = ?, is_default = ?, enabled = ?,
                       updated_at = now()
                 WHERE customer_id = ? AND id = ?
                """, recipientName, phone, province, city, district, detailAddress,
                isDefault, enabled, customerId, id) == 1;
    }

    public boolean disable(long customerId, long id) {
        return jdbc.update("""
                UPDATE customer_addresses
                   SET enabled = FALSE, is_default = FALSE, updated_at = now()
                 WHERE customer_id = ? AND id = ?
                """, customerId, id) == 1;
    }

    public boolean enable(long customerId, long id) {
        return jdbc.update("""
                UPDATE customer_addresses
                   SET enabled = TRUE, updated_at = now()
                 WHERE customer_id = ? AND id = ?
                """, customerId, id) == 1;
    }

    /** 默认地址停用后，把最早创建的启用地址提升为默认；没有则返回 null。 */
    public Optional<Long> findFirstEnabledIdExcept(long customerId, long excludeId) {
        return jdbc.query("""
                SELECT id FROM customer_addresses
                 WHERE customer_id = ? AND enabled AND id <> ?
                 ORDER BY id LIMIT 1
                """, (rs, i) -> rs.getLong("id"), customerId, excludeId).stream().findFirst();
    }
}
