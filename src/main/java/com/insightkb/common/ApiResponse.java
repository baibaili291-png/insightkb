package com.insightkb.common;

/**
 * 统一响应体。code=0 表示成功，非 0 表示业务失败。
 * 前端只需判断 code，不用去解析 HTTP 状态码的语义差异。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(1, message, null);
    }
}
