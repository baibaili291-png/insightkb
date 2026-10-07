package com.insightkb.model;

import java.util.List;

/**
 * 一次检索的三路结果快照，用于 /api/retrieval/debug 与评测集对比。
 *
 * 为什么要有这个结构：简历上写"混合检索使召回率从 62% 提升到 81%"这句话之前，
 * 必须先有一组能复现的数字。这个对象把"同一问题在三种模式下的召回结果"一次性返回，
 * 评测脚本拿着它就能批量统计命中率，不需要人眼一条条看。
 *
 * @param vector   纯向量召回结果
 * @param keyword  纯 BM25 召回结果
 * @param fused    RRF 融合后的结果
 */
public record RetrievalCompare(
        String question,
        String mode,
        List<RetrievedChunk> vector,
        List<RetrievedChunk> keyword,
        List<RetrievedChunk> fused,
        long vectorMs,
        long keywordMs,
        long fuseMs,
        long totalMs
) {
}
