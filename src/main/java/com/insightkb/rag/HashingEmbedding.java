package com.insightkb.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地哈希向量 —— 零依赖、零成本的 Embedding 兜底实现。
 *
 * 用途：在没有 API Key、或者想快速验证整条 RAG 链路是否通的时候使用。
 * 它当然不具备真正的语义能力（"电脑"和"计算机"它认为是两个无关的词），
 * 但它的定位就是"让链路先跑起来"，等接入真实 Embedding 后检索质量会明显提升——
 * 这个前后对比本身就是简历上一个很好的量化素材。
 *
 * 算法：Hashing Trick（特征哈希）
 *   1. 分词：中文切单字 + 双字（bigram），英文数字按单词切
 *      中文用 bigram 是因为中文没有空格，单字粒度太粗（"知识"和"识库"都无意义）
 *   2. 词频加权：weight = 1 + ln(tf)，避免高频词完全主导
 *   3. 哈希到固定维度，并用哈希值的一位决定正负号 —— 带符号哈希可以抵消
 *      不同词碰撞到同一维度时带来的系统性偏差
 *   4. L2 归一化，使余弦相似度计算退化为点积
 */
public class HashingEmbedding implements EmbeddingModel {

    private static final int DIM = 1024;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        if (text == null || text.isBlank()) {
            return v;
        }

        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokenize(text)) {
            tf.merge(token, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            double weight = 1.0 + Math.log(e.getValue());
            int h = hash(e.getKey());
            int idx = Math.floorMod(h, DIM);
            float sign = ((h >>> 15) & 1) == 0 ? 1f : -1f;
            v[idx] += (float) (sign * weight);
        }
        return l2Normalize(v);
    }

    @Override
    public int dimension() {
        return DIM;
    }

    @Override
    public String name() {
        return "hashing-1024 (mock)";
    }

    private List<String> tokenize(String text) {
        String s = text.toLowerCase();
        List<String> out = new ArrayList<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                flushWord(word, out);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                word.append(c);
            } else {
                flushCjk(cjk, out);
                flushWord(word, out);
            }
        }
        flushCjk(cjk, out);
        flushWord(word, out);
        return out;
    }

    private void flushCjk(StringBuilder buf, List<String> out) {
        String seg = buf.toString();
        buf.setLength(0);
        for (int i = 0; i < seg.length(); i++) {
            out.add(String.valueOf(seg.charAt(i)));
            if (i + 1 < seg.length()) {
                out.add(seg.substring(i, i + 2));
            }
        }
    }

    private void flushWord(StringBuilder buf, List<String> out) {
        String seg = buf.toString();
        buf.setLength(0);
        if (seg.length() > 1) {
            out.add(seg);
        }
    }

    private boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /** FNV-1a 起始 + MurmurHash3 收敛，32 位，够用且无需额外依赖 */
    private int hash(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        h *= 0xc2b2ae35;
        h ^= (h >>> 16);
        return h;
    }

    private float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            return v;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }
}
