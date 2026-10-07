package com.insightkb.model;

/**
 * 会话里的一条消息。
 *
 * @param role    user / assistant
 * @param content 消息正文
 */
public record ChatMessage(String role, String content) {
}
