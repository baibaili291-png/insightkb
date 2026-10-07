package com.insightkb.model;

import java.util.List;

/**
 * 一次问答的完整结果，含耗时拆分。
 *
 * 把检索耗时和生成耗时分开记录，是为了能定位"回答慢"到底慢在哪一段 ——
 * 这两段的优化手段完全不同：检索慢要调索引和召回参数，生成慢则要调模型或缩短 prompt。
 * 前端的 Trace 面板直接展示这两个数字。
 */
public record ChatResult(
        String answer,
        List<RetrievedChunk> contexts,
        long retrievalMs,
        long generationMs,
        String embeddingModel,
        String chatModel,
        String conversationId,
        String effectiveQuery,
        boolean rewritten,
        boolean degraded
) {
    public long totalMs() {
        return retrievalMs + generationMs;
    }
}
