package com.insightkb.model;

/**
 * 提问请求体。
 * 用 POST body 而不是 URL 参数传递问题：长问题放 URL 会超长，
 * 而且 URL 容易被日志系统完整记录，不利于后续接入敏感信息审计。
 */
public record ChatQuery(String question, String conversationId) {
}
