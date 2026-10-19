package com.project.person.admin.common;

/**
 * 业务异常：code 对应统一错误码（400 参数错误 / 401 未登录 / 409 冲突 / 500 服务器错误）
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public static BizException badRequest(String msg) {
        return new BizException(400, msg);
    }

    public static BizException unauthorized(String msg) {
        return new BizException(401, msg);
    }

    public static BizException conflict(String msg) {
        return new BizException(409, msg);
    }

    public int getCode() {
        return code;
    }
}
