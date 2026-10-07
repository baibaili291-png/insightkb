package com.insightkb.service;

import com.insightkb.config.InsightKbProperties;
import com.insightkb.model.RetrievedChunk;
import com.insightkb.model.RetrievalCompare;
import com.insightkb.rag.Bm25Index;
import com.insightkb.rag.EmbeddingModel;
import com.insightkb.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务（M2：混合检索）。
 *
 * 三种模式由 insightkb.rag.retrieval-mode 控制：
 *   vector  —— 只用向量召回，作为对比基线
 *   keyword —— 只用 BM25 关键词召回
 *   hybrid  —— 两路各召回 recallK 条，再用 RRF 融合取 topK（默认）
 *
 * 为什么融合用 RRF 而不是"分数归一化后相加"：
 * 余弦相似度落在 [-1,1]（实际多集中在 0.3~0.8），BM25 没有上界，几十到几百都常见。
 * 两路量纲完全不同，直接加权相加等于把权重调参变成玄学 —— 换一批文档最优权重就变了。
 * RRF 只吃"排名"（score = Σ w_i / (k + rank_i)），天然免疫量纲差异，
 * 换语料也不用重新调参，这是它在工程上被广泛采用的真正原因。
 *
 * retrieve() 的签名保持不变，上层 ChatService 不需要感知检索策略的变化。
 */
@Service
public class RetrieverService {

    private static final Logger log = LoggerFactory.getLogger(RetrieverService.class);

    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final InsightKbProperties props;

    public RetrieverService(EmbeddingModel embeddingModel,
                            VectorStore vectorStore,
                            Bm25Index bm25Index,
                            InsightKbProperties props) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.props = props;
    }

    public List<RetrievedChunk> retrieve(String question, int topK) {
        return switch (props.rag().retrievalModeOrDefault()) {
            case "vector" -> vectorRoute(question, topK);
            case "keyword" -> keywordRoute(question, topK);
            default -> hybridRoute(question, topK);
        };
    }

    /** 三路一起跑，用于 A/B 对比与评测集批量统计 */
    public RetrievalCompare compare(String question, int topK) {
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> vector = vectorRoute(question, topK);
        long t1 = System.currentTimeMillis();
        List<RetrievedChunk> keyword = keywordRoute(question, topK);
        long t2 = System.currentTimeMillis();
        List<RetrievedChunk> fused = fuse(vector, keyword, topK);
        long t3 = System.currentTimeMillis();

        return new RetrievalCompare(question, props.rag().retrievalModeOrDefault(),
                vector, keyword, fused, t1 - t0, t2 - t1, t3 - t2, t3 - t0);
    }

    private List<RetrievedChunk> vectorRoute(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (vectorStore.size() == 0) {
            log.info("向量库为空，向量路直接返回空召回");
            return List.of();
        }
        int recallK = Math.max(props.rag().recallK(), topK);
        float[] queryVector = embeddingModel.embed(question);
        List<RetrievedChunk> candidates = vectorStore.search(queryVector, recallK);
        return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
    }

    private List<RetrievedChunk> keywordRoute(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (bm25Index.size() == 0) {
            log.info("BM25 索引为空，关键词路直接返回空召回");
            return List.of();
        }
        int recallK = Math.max(props.rag().recallK(), topK);
        List<RetrievedChunk> candidates = bm25Index.search(question, recallK);
        return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
    }

    private List<RetrievedChunk> hybridRoute(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        if (vectorStore.size() == 0 && bm25Index.size() == 0) {
            log.info("向量库与 BM25 索引均为空，直接返回空召回");
            return List.of();
        }
        int recallK = Math.max(props.rag().recallK(), topK);
        List<RetrievedChunk> vector = vectorStore.search(embeddingModel.embed(question), recallK);
        List<RetrievedChunk> keyword = bm25Index.search(question, recallK);
        return fuse(vector, keyword, topK);
    }

    /**
     * 加权 RRF 融合：score = Σ w_i / (k + rank_i)
     *
     * 只出现在某一路的结果也要保留 —— RRF 的另一大价值是"互相补位"：
     * 向量路没召回但 BM25 命中的片段（典型场景：型号编号、缩写查询）依然有机会进 Top-K。
     */
    private List<RetrievedChunk> fuse(List<RetrievedChunk> vector,
                                      List<RetrievedChunk> keyword,
                                      int topK) {
        int k = props.rag().rrfKOrDefault();
        double wv = props.rag().vectorWeightOrDefault();
        double wk = props.rag().keywordWeightOrDefault();

        Map<String, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<String, Double> fused = new HashMap<>();
        Map<String, Integer> vRank = new HashMap<>();
        Map<String, Integer> kRank = new HashMap<>();

        for (int i = 0; i < vector.size(); i++) {
            RetrievedChunk rc = vector.get(i);
            byId.putIfAbsent(rc.chunk().id(), rc);
            vRank.put(rc.chunk().id(), i + 1);
            fused.merge(rc.chunk().id(), wv / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < keyword.size(); i++) {
            RetrievedChunk rc = keyword.get(i);
            byId.putIfAbsent(rc.chunk().id(), rc);
            kRank.put(rc.chunk().id(), i + 1);
            fused.merge(rc.chunk().id(), wk / (k + i + 1), Double::sum);
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(fused.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

        List<RetrievedChunk> result = new ArrayList<>();
        int limit = Math.min(topK, ranked.size());
        for (int i = 0; i < limit; i++) {
            RetrievedChunk rc = byId.get(ranked.get(i).getKey());
            if (rc == null) {
                continue;
            }
            result.add(RetrievedChunk.ofFused(rc.chunk(), ranked.get(i).getValue(),
                    vRank.get(rc.chunk().id()), kRank.get(rc.chunk().id())));
        }
        return result;
    }

    /** 供 /api/system/info 展示当前检索模式 */
    public String mode() {
        return props.rag().retrievalModeOrDefault();
    }
}
