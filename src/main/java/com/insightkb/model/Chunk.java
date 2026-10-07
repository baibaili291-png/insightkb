package com.insightkb.model;

/**
 * 文档切块 —— RAG 检索的最小单位。
 *
 * @param id        切块唯一 ID（格式：{docId}#{index}），用于答案引用溯源
 * @param docId     所属文档 ID
 * @param docName   所属文档名，回答引用时直接展示
 * @param index     在文档内的序号，从 0 开始
 * @param text      切块正文
 * @param embedding 向量，null 表示尚未向量化
 */
public record Chunk(
        String id,
        String docId,
        String docName,
        int index,
        String text,
        float[] embedding
) {
    public Chunk withEmbedding(float[] embedding) {
        return new Chunk(id, docId, docName, index, text, embedding);
    }

    /** 用于前端展示的摘要（列表里只显示前 120 字） */
    public String preview() {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
