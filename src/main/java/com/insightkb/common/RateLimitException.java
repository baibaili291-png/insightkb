package com.insightkb.common;

/**
 * 限流拒绝。
 *
 * 单独定义一个异常而不是直接抛 InsightKbException，是为了让全局处理器能区分
 * 状态码：限流必须是 429（客户端可以据此退避重试），业务参数错误才是 400。
 * 混在一起的话，客户端要么无脑重试把服务冲垮，要么该重试的时候不重试。
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterMillis;

    public RateLimitException(String message, long retryAfterMillis) {
        super(message);
        this.retryAfterMillis = retryAfterMillis;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
