package com.insightkb.rag;

import java.util.List;

/**
 * 向量化模型抽象。
 *
 * 为什么要抽成接口：本地哈希向量（零依赖、可离线）和远端 Embedding 接口
 * （语义质量高、但要 Key 和网络）在开发阶段会来回切换。
 * 抽成接口后，切换只改一行配置，上层检索逻辑完全不用动。
 */
public interface EmbeddingModel {

    /** 把一段文本编码成向量。返回的向量已做 L2 归一化，因此检索时余弦相似度退化为点积。 */
    float[] embed(String text);

    /** 批量编码。默认逐个调用，远端实现可以覆写为一次请求。 */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** 向量维度。用于校验新旧向量是否来自同一个模型。 */
    int dimension();

    /** 模型标识，会显示在前端的 Trace 面板里，便于排查"检索效果变差是不是换了模型"。 */
    String name();
}
