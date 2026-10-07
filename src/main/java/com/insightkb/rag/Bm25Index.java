package com.insightkb.rag;

import com.insightkb.model.Chunk;
import com.insightkb.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BM25 关键词索引（自研，零第三方依赖）。
 *
 * 为什么需要它：向量检索擅长"意思相近"，但对专有名词、型号编号、缩写极不敏感。
 * 比如问 "Model X200 的额定功率"，向量很可能召回一堆讲"功率定义"的段落，
 * 而 BM25 能靠字面命中 "X200" 这一唯一标识。两路互补，才有混合检索的意义。
 *
 * 为什么自己写而不引 Lucene / IK 分词器：
 *   1. 本项目只有万级切块，倒排 + BM25 的实现本身不到 200 行，引入 Lucene 会多出
 *      3 MB 依赖和一整套 Analyzer / Directory / IndexWriter 的概念负担；
 *   2. 中文分词是这类项目最常被追问的点，自己写一遍才能在面试里讲清楚
 *      "为什么这么切、边界情况怎么处理"，这是简历上能讲的东西。
 *
 * 由 RagConfig 按 insightkb.rag.bm25-k1 / bm25-b 装配（不用 @Component，
 * 因为这两个参数要从配置注入，交给 @Bean 更直接）。
 *
 * 中文怎么切的（关键设计）：
 *   不依赖词典，用「二元组（bigram）」滑动切分 —— "混合检索" 切成 "混合"、"合检"、"检索"。
 *   好处是：不需要词典就能获得近似分词的效果，对未登录词（人名、型号、新词）完全免疫；
 *   代价是索引体积约为单字的 2 倍。单字连续段（只有一个汉字）单独成词，避免短查询全部落空。
 *   英文与数字按连续串切分并统一小写，保证 "RRF" 和 "rrf" 能互相命中。
 */
public class Bm25Index {

    private static final Logger log = LoggerFactory.getLogger(Bm25Index.class);

    /** BM25 词频饱和参数：控制单个词重复出现时的收益衰减速度 */
    private static final double DEFAULT_K1 = 1.5;
    /** BM25 长度归一化参数：1 表示完全按文档长度归一化，0 表示不考虑长度 */
    private static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;

    /** chunkId -> chunk */
    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();
    /** chunkId -> (term -> tf)，删除文档时要用它回退 df 和倒排表 */
    private final Map<String, Map<String, Integer>> docTerms = new ConcurrentHashMap<>();
    /** chunkId -> 词数（文档长度） */
    private final Map<String, Integer> docLen = new ConcurrentHashMap<>();
    /** term -> (chunkId -> tf)，倒排表 */
    private final Map<String, Map<String, Integer>> inverted = new ConcurrentHashMap<>();
    /** docId -> chunkId 集合 */
    private final Map<String, Set<String>> docIndex = new ConcurrentHashMap<>();

    /** 全部文档的词数总和，用于算平均文档长度 */
    private long totalTermCount = 0L;

    /**
     * 写入与删除会同时改动 5 个结构，必须整体原子，否则会出现
     * "df 减了但倒排表还在" 这种不一致 —— 这类 bug 的表现是检索结果时好时坏，极难复现。
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public Bm25Index() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    public Bm25Index(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    public void addAll(List<Chunk> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            for (Chunk c : list) {
                if (c == null || c.text() == null) {
                    continue;
                }
                // 重复导入同一文档时先清掉旧的，否则 df 会被重复计数
                removeChunkLocked(c.id());
                chunks.put(c.id(), c);
                docIndex.computeIfAbsent(c.docId(), k -> ConcurrentHashMap.newKeySet()).add(c.id());

                Map<String, Integer> tf = new HashMap<>();
                for (String term : analyze(c.text())) {
                    tf.merge(term, 1, Integer::sum);
                }
                docTerms.put(c.id(), tf);
                docLen.put(c.id(), tf.values().stream().mapToInt(Integer::intValue).sum());
                totalTermCount += docLen.get(c.id());

                tf.forEach((term, freq) ->
                        inverted.computeIfAbsent(term, k -> new ConcurrentHashMap<>()).put(c.id(), freq));
            }
            log.info("BM25 索引写入 {} 块，当前 {} 块 / {} 个词项", list.size(), chunks.size(), inverted.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeByDocument(String docId) {
        Set<String> ids = docIndex.remove(docId);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            ids.forEach(this::removeChunkLocked);
            log.info("BM25 索引移除文档 {} 的 {} 块，剩余 {} 块", docId, ids.size(), chunks.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 调用方必须持有写锁 */
    private void removeChunkLocked(String chunkId) {
        Map<String, Integer> tf = docTerms.remove(chunkId);
        Integer len = docLen.remove(chunkId);
        chunks.remove(chunkId);
        if (tf == null) {
            return;
        }
        if (len != null) {
            totalTermCount -= len;
        }
        tf.forEach((term, freq) -> {
            Map<String, Integer> postings = inverted.get(term);
            if (postings == null) {
                return;
            }
            postings.remove(chunkId);
            // 没有任何文档还含有这个词，倒排条目一并删掉，避免 Map 无限膨胀
            if (postings.isEmpty()) {
                inverted.remove(term);
            }
        });
    }

    public int size() {
        return chunks.size();
    }

    public int termCount() {
        return inverted.size();
    }

    /**
     * BM25 检索。
     *
     * 只对查询词命中的文档打分（走倒排表），不是全表扫描：
     * 文档量上万后，全表扫描的代价会随知识库线性增长，而倒排只和查询词本身的命中量有关。
     */
    public List<RetrievedChunk> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<String> queryTerms = analyze(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        lock.readLock().lock();
        try {
            int n = chunks.size();
            if (n == 0) {
                return List.of();
            }
            double avgdl = totalTermCount / (double) n;

            Map<String, Double> scores = new HashMap<>();
            // 同一个词在查询里重复出现只算一次：重复词不代表用户强调，只会让分数失真
            for (String term : new HashSet<>(queryTerms)) {
                Map<String, Integer> postings = inverted.get(term);
                if (postings == null || postings.isEmpty()) {
                    continue;
                }
                int df = postings.size();
                // BM25 的 IDF 用平滑版本，保证 df 很大时不会出现负数
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                postings.forEach((chunkId, tf) -> {
                    int dl = docLen.getOrDefault(chunkId, 0);
                    double norm = tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / avgdl));
                    scores.merge(chunkId, idf * norm, Double::sum);
                });
            }

            List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
            ranked.sort(Map.Entry.<String, Double>comparingByValue().reversed());

            List<RetrievedChunk> result = new ArrayList<>();
            int limit = Math.min(topK, ranked.size());
            for (int i = 0; i < limit; i++) {
                Chunk c = chunks.get(ranked.get(i).getKey());
                if (c != null) {
                    result.add(RetrievedChunk.ofKeyword(c, ranked.get(i).getValue(), i + 1));
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /* ==================== 分词 ==================== */

    /**
     * 极简分析器：中文二元组 + 英文数字按串，统一小写。
     * 刻意不引入任何分词词典 —— 词典方案在未登录词面前会直接失效，
     * 而知识库里恰恰充满了产品名、型号、缩写这类未登录词。
     */
    public static List<String> analyze(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flushAscii(terms, ascii);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(terms, cjk);
                ascii.append(c);
            } else {
                flushAscii(terms, ascii);
                flushCjk(terms, cjk);
            }
        }
        flushAscii(terms, ascii);
        flushCjk(terms, cjk);
        return terms;
    }

    private static void flushAscii(List<String> out, StringBuilder buf) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjk(List<String> out, StringBuilder buf) {
        int len = buf.length();
        if (len == 0) {
            return;
        }
        if (len == 1) {
            // 孤立的单个汉字：二元组切不出来，只能整字入索引，否则 "钱" 这类查询永远落空
            out.add(buf.toString());
        } else {
            for (int i = 0; i < len - 1; i++) {
                out.add(buf.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    /** CJK 基本区与扩展 A 区，覆盖日常中日韩汉字 */
    private static boolean isCjk(char c) {
        return (c >= '\u3400' && c <= '\u4DBF') || (c >= '\u4E00' && c <= '\u9FFF');
    }

    /** 给前端 /api/system/info 展示用：当前索引规模 */
    public Map<String, Object> stats() {
        lock.readLock().lock();
        try {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("chunkCount", chunks.size());
            m.put("termCount", inverted.size());
            m.put("avgDocLength", chunks.isEmpty() ? 0 : Math.round(totalTermCount / (double) chunks.size()));
            return m;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 排序用的小工具，避免业务代码里到处写 Comparator */
    public static Comparator<RetrievedChunk> byScoreDesc() {
        return Comparator.comparingDouble(RetrievedChunk::score).reversed();
    }
}
