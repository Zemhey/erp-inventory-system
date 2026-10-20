package com.erp.shared.error;

/**
 * 业务异常：携带稳定错误码与定位信息。
 *
 * <p>契约要求（docs/05-接口契约规范.md §3）：错误消息<b>必须包含定位信息</b>
 * （哪个 SKU、哪个仓、差多少），这样测试断言能直接验证语义，
 * 排障时也无须再去猜。
 *
 * <p>错误码为稳定枚举，每个码都应有至少一条测试用例触发它。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Object details;

    public BusinessException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public BusinessException(ErrorCode code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
