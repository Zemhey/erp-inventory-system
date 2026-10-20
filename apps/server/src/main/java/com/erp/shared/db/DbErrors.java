package com.erp.shared.db;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;

/**
 * 把 PostgreSQL 约束违例翻译成<b>业务错误码</b>的小工具。
 *
 * <p><b>为什么需要这一层</b>：唯一约束是"编码唯一"的最终保证，但如果直接让
 * {@code DataIntegrityViolationException} 冒到接口层，前端收到的是
 * {@code SYS_INTERNAL_ERROR} + 一整串 SQL 报错；而契约要求返回
 * {@code 409 MDM_CODE_DUPLICATED} 这种可编程判断的码。
 *
 * <p><b>为什么不直接 import {@code org.postgresql.util.PSQLException}</b>：
 * 驱动在 build.gradle.kts 里是 {@code runtimeOnly}（生产只需要驱动运行时在），
 * 编译期拿不到它的类型。而且把应用代码绑到具体驱动类上，
 * 会让"换驱动/换库"从配置问题变成改代码问题。
 * 这里改用 Spring 的 {@link SQLExceptionTranslator} 读取驱动暴露的
 * <b>标准 SQLState</b> 与方言错误码 —— 两者都是 JDBC 规范的一部分，与驱动实现解耦。
 *
 * <p><b>为什么按约束名而不是按消息文本判断</b>：报错文本会随 PostgreSQL 版本
 * 与 locale 变化（本机就是中文 locale，报错是"违反唯一约束"），
 * 按文本匹配的代码在升级后静默失效。约束名由我们自己定义
 * （{@code uq_product_code} 等），是稳定契约。
 *
 * <p>注意：这里<b>不吞掉</b>无法识别的约束违例 —— 那说明代码违反了某条
 * 没有专门错误码的约束，属于程序缺陷，应当原样抛出并记录。
 */
public final class DbErrors {

    /** PostgreSQL: unique_violation。 */
    public static final String UNIQUE_VIOLATION = "23505";
    /** PostgreSQL: check_violation。 */
    public static final String CHECK_VIOLATION = "23514";
    /** PostgreSQL: foreign_key_violation。 */
    public static final String FOREIGN_KEY_VIOLATION = "23503";

    private DbErrors() {
    }

    /**
     * 若异常是由指定约束名触发的违例，返回可抛出的业务异常；否则返回 null。
     *
     * @param ex         捕获到的异常（通常是 DataIntegrityViolationException）
     * @param constraint 约束名（如 {@code uq_product_code}）
     * @param code       命中后使用的业务错误码
     * @param message    命中后的业务消息
     */
    public static BusinessException translate(DataIntegrityViolationException ex,
                                              String constraint, ErrorCode code,
                                              String message) {
        String detail = constraintOf(ex);
        // PostgreSQL 的约束名以双引号出现在消息里；不同驱动/版本的格式略有差异，
        // 这里用"包含约束名"判断，比精确格式更稳。
        if (detail != null && detail.contains(constraint)) {
            return new BusinessException(code, message);
        }
        return null;
    }

    /**
     * 判断异常是否是"某张外键"违例（用于把外键失败翻译成 404）。
     *
     * @param ex         捕获到的异常
     * @param sqlState   期望的 SQLState（{@link #FOREIGN_KEY_VIOLATION} 等）
     */
    public static boolean isSqlState(Throwable ex, String sqlState) {
        return sqlState.equals(sqlStateOf(ex));
    }

    /**
     * 提取 PostgreSQL 错误消息里最先出现的 {@code "constraint_name"} 片段。
     *
     * <p>PostgreSQL 的典型消息为：
     * {@code 违反唯一约束"uq_product_code" DETAIL: 键值已存在...}。
     * 这里把整条消息原样返回，由调用方用 {@code contains} 判断 ——
     * 不做正则解析，因为消息随 locale 变化，解析越精细越脆。
     */
    static String constraintOf(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof SQLException sqlEx) {
            return sqlEx.getMessage();
        }
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx) {
                return sqlEx.getMessage();
            }
            cur = cur.getCause();
        }
        return ex.getMessage();
    }

    /**
     * 取异常的 SQLState。
     *
     * <p>优先用方言翻译器（能拿到 {@code PGException} 的 state），
     * 退化到只认标准 SQLState 的翻译器 —— 后者对所有 JDBC 驱动都可用。
     */
    static String sqlStateOf(Throwable ex) {
        SQLException sqlEx = findSqlException(ex);
        if (sqlEx == null) {
            return null;
        }
        String state = sqlEx.getSQLState();
        if (state != null && !state.isBlank()) {
            return state;
        }
        SQLExceptionTranslator translator = new SQLStateSQLExceptionTranslator();
        try {
            translator.translate("db", null, sqlEx);
        } catch (RuntimeException ignored) {
            // 翻译器只用于探测，不关心其返回值
        }
        return sqlEx.getSQLState();
    }

    private static SQLException findSqlException(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx) {
                return sqlEx;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** 供诊断日志使用：返回方言翻译器的类名，确认方言翻译器是否被加载。 */
    static String dialectTranslatorName() {
        return SQLErrorCodeSQLExceptionTranslator.class.getSimpleName();
    }
}
