package com.insightkb.rag;

import com.insightkb.model.Chunk;
import com.insightkb.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量库（M1 版本）。
 *
 * 为什么第一阶段先不上 Elasticsearch / pgvector：
 * 这一步要验证的是"检索链路是否成立"——切块粒度合不合适、Top-K 取多少、
 * 相似度分数有没有区分度。这些结论跟向量存在哪无关，但引入 ES 会额外带来
 * 索引映射、分词器配置、服务运维三类问题，把排错面放大好几倍。
 * 先用内存实现把链路和参数调好，再换存储引擎，是更可控的顺序。
 *
 * 已知局限（面试时主动讲出来，比被问出来好）：
 * - 全量线性扫描，O(n)，只在万级切块以下可接受；
 * - 进程重启数据丢失；
 * - 无持久化索引，无法做增量更新。
 * 这三点正是 M3 换 Elasticsearch 的动机。
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);

    /** chunkId -> chunk */
    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();
    /** docId -> chunkId 集合，用于按文档删除 */
    private final Map<String, Set<String>> docIndex = new ConcurrentHashMap<>();

    public void addAll(List<Chunk> list) {
        for (Chunk c : list) {
            chunks.put(c.id(), c);
            docIndex.computeIfAbsent(c.docId(), k -> ConcurrentHashMap.newKeySet()).add(c.id());
        }
        log.info("向量库写入 {} 块，当前总量 {} 块", list.size(), chunks.size());
    }

    public void removeByDocument(String docId) {
        Set<String> ids = docIndex.remove(docId);
        if (ids == null) {
            return;
        }
        ids.forEach(chunks::remove);
        log.info("向量库移除文档 {} 的 {} 块，剩余 {} 块", docId, ids.size(), chunks.size());
    }

    public int size() {
        return chunks.size();
    }

    public List<Chunk> listByDocument(String docId) {
        Set<String> ids = docIndex.get(docId);
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .map(chunks::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Chunk::index))
                .toList();
    }

    /**
     * 向量召回：余弦相似度排序取 Top-K。
     * 因为入库时向量已 L2 归一化，余弦相似度等价于点积，省掉了每次开方。
     */
    public List<RetrievedChunk> search(float[] queryVector, int topK) {
        if (queryVector == null || topK <= 0) {
            return List.of();
        }

        List<RetrievedChunk> scored = new ArrayList<>();
        for (Chunk c : chunks.values()) {
            float[] vec = c.embedding();
            // 维度不一致说明该向量是用另一个模型生成的，直接跳过而不是算出一个错误的相似度
            if (vec == null || vec.length != queryVector.length) {
                continue;
            }
            scored.add(RetrievedChunk.ofVector(c, dot(queryVector, vec), 0));
        }

        scored.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());

        List<RetrievedChunk> top = scored.size() > topK ? scored.subList(0, topK) : scored;
        List<RetrievedChunk> result = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            RetrievedChunk r = top.get(i);
            result.add(new RetrievedChunk(r.chunk(), r.score(), i + 1, r.keywordRank()));
        }
        return result;
    }

    private double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
