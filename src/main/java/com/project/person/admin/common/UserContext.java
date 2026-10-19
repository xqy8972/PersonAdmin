package com.project.person.admin.common;

/**
 * 当前登录用户上下文（由 AuthInterceptor 解析 JWT 后写入 ThreadLocal）
 */
public class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
    }
}
