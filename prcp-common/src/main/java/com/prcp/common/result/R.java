package com.prcp.common.result;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应格式 R&lt;T&gt;
 * 对齐 FastAPI 端 {"code": 0, "msg": "OK", "data": ...} 格式
 *
 * @author PRCP WorkBuddy Agent
 * @date 2026-09-23
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务码：0=成功 */
    private Integer code;
    /** 提示信息 */
    private String msg;
    /** 业务数据 */
    private T data;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(0);
        r.setMsg("OK");
        r.setData(data);
        return r;
    }

    public static <T> R<T> ok(T data, String msg) {
        R<T> r = ok(data);
        r.setMsg(msg);
        return r;
    }

    public static <T> R<T> fail(String msg) {
        R<T> r = new R<>();
        r.setCode(500);
        r.setMsg(msg);
        return r;
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }

    public static <T> R<T> unauthorized(String msg) {
        return fail(401, msg);
    }

    public static <T> R<T> forbidden(String msg) {
        return fail(403, msg);
    }

    public static <T> R<T> notFound(String msg) {
        return fail(404, msg);
    }

    public static <T> R<T> badRequest(String msg) {
        return fail(400, msg);
    }
}
