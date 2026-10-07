package com.insightkb.model;

/**
 * 一次问答会话。
 *
 * @param id        会话 ID
 * @param title     会话标题（取首条问题，截断）
 * @param summary   历史对话的压缩摘要。长对话靠它控制 token 成本
 * @param turnCount 累计轮次
 */
public record Conversation(
        String id,
        String title,
        String summary,
        int turnCount
) {
}
