package com.prcp.common.exception;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String msg) {
        super(msg);
        this.code = 500;
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(int code, String msg, Throwable cause) {
        super(msg, cause);
        this.code = code;
    }

    public static BizException badRequest(String msg) {
        return new BizException(400, msg);
    }

    public static BizException unauthorized(String msg) {
        return new BizException(401, msg);
    }

    public static BizException forbidden(String msg) {
        return new BizException(403, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(404, msg);
    }
}
