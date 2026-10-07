package com.insightkb.common;

/**
 * 业务异常。抛出后由 GlobalExceptionHandler 统一转成 ApiResponse，
 * 避免把底层堆栈直接暴露给前端。
 */
public class InsightKbException extends RuntimeException {

    public InsightKbException(String message) {
        super(message);
    }

    public InsightKbException(String message, Throwable cause) {
        super(message, cause);
    }
}
