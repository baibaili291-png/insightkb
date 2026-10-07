package com.insightkb.model;

/**
 * 检索结果：切块 + 相关性得分。
 * 后续加入 BM25 与 RRF 融合时，score 的含义会从"余弦相似度"扩展为"融合后的排名分"，
 * 因此这里额外保留各路的原始排名，方便前端 Trace 展示与调参对比。
 */
public record RetrievedChunk(
        Chunk chunk,
        double score,
        Integer vectorRank,
        Integer keywordRank
) {
    public static RetrievedChunk ofVector(Chunk chunk, double score, int rank) {
        return new RetrievedChunk(chunk, score, rank, null);
    }

    public static RetrievedChunk ofKeyword(Chunk chunk, double score, int rank) {
        return new RetrievedChunk(chunk, score, null, rank);
    }

    /** 融合结果的构造：两路排名都保留，方便前端 Trace 展示与调参对比 */
    public static RetrievedChunk ofFused(Chunk chunk, double score, Integer vectorRank, Integer keywordRank) {
        return new RetrievedChunk(chunk, score, vectorRank, keywordRank);
    }
}
