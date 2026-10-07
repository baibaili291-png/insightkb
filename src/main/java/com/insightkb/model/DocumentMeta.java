package com.insightkb.model;

/**
 * 文档元信息。
 * 用 record 保持不可变，状态变更时整体替换（见 DocumentService），
 * 这样并发读到的永远是一个自洽的快照，不会读到"半个状态"。
 */
public record DocumentMeta(
        String id,
        String name,
        String type,
        long size,
        int chunkCount,
        DocumentStatus status,
        String message,
        long createdAt
) {
    public enum DocumentStatus {
        /** 已接收，正在解析 / 切块 / 向量化 */
        PROCESSING,
        /** 处理完成，可被检索 */
        READY,
        /** 处理失败 */
        FAILED
    }

    public DocumentMeta withStatus(DocumentStatus status, int chunkCount, String message) {
        return new DocumentMeta(id, name, type, size, chunkCount, status, message, createdAt);
    }
}
