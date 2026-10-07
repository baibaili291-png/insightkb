package com.insightkb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * 全部可配置项集中在这里，通过 application.yml 的 insightkb.* 注入。
 * 使用 record + 构造器绑定，配置项不可变，避免运行期被意外修改。
 */
@ConfigurationProperties(prefix = "insightkb")
public record InsightKbProperties(Storage storage, Llm llm, Rag rag, Chat chat, Limit limit) {

    public record Storage(String uploadDir, boolean persist) {
    }

    public record Llm(
            String mode,
            String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            String embeddingMode,
            Integer timeoutSeconds
    ) {
        public boolean useRemoteChat() {
            return "openai".equalsIgnoreCase(mode) && apiKey != null && !apiKey.isBlank();
        }

        public boolean useRemoteEmbedding() {
            return "openai".equalsIgnoreCase(embeddingMode)
                    && apiKey != null && !apiKey.isBlank()
                    && embeddingModel != null && !embeddingModel.isBlank();
        }

        public int timeoutSecondsOrDefault() {
            return timeoutSeconds == null ? 60 : timeoutSeconds;
        }
    }

    /**
     * 限流配置。
     *
     * qps 是匀速补充速率，burst 是桶容量（允许的瞬时突发）。
     * 真实用户是"问一句、看一会儿、再问一句"，burst 给 3~5 才不会误伤；
     * 给 1 的话连问两句就 429，体验很差。
     */
    public record Limit(Boolean enabled, Double qps, Integer burst, String backend) {
        /** 不能叫 enabled()：record 组件已经有同名访问器，会编译冲突 */
        public boolean isEnabled() {
            return enabled != null && enabled;
        }

        /** redis / memory / auto */
        public String backendOrDefault() {
            if (backend == null || backend.isBlank()) {
                return "auto";
            }
            String b = backend.trim().toLowerCase(Locale.ROOT);
            return (b.equals("redis") || b.equals("memory") || b.equals("auto")) ? b : "auto";
        }

        public double qpsOrDefault() {
            return qps == null || qps <= 0 ? 1.0 : qps;
        }

        public int burstOrDefault() {
            return burst == null || burst <= 0 ? 5 : burst;
        }
    }

    /**
     * 多轮对话相关配置。
     *
     * history-turns 决定"最近几轮完整消息进 Prompt"，summary-every-turns 决定
     * "每几轮压缩一次历史"。这两个值决定了长对话的 token 成本曲线：
     * 窗口越大回答越连贯，但成本线性上涨；摘要越频繁越省，但细节丢得越快。
     */
    public record Chat(Integer historyTurns, boolean queryRewrite, Integer summaryEveryTurns) {
        public int historyTurnsOrDefault() {
            return historyTurns == null || historyTurns <= 0 ? 4 : historyTurns;
        }

        public int summaryEveryTurnsOrDefault() {
            return summaryEveryTurns == null || summaryEveryTurns <= 0 ? 6 : summaryEveryTurns;
        }
    }

    /**
     * 检索相关配置。
     *
     * retrieval-mode 是混合检索的总开关，取值：
     *   vector  —— 只用向量召回（M1 行为，用于做对比基线）
     *   keyword —— 只用 BM25 召回
     *   hybrid  —— 两路召回后做 RRF 融合（默认）
     * 留这个开关不是为了炫技，是为了能跑 A/B：同一批问题分别用三种模式跑，
     * 才能拿到"混合检索比纯向量高多少"这种可以写进简历的真实数字。
     */
    public record Rag(
            int chunkSize,
            int chunkOverlap,
            int topK,
            int recallK,
            String retrievalMode,
            int rrfK,
            double vectorWeight,
            double keywordWeight,
            double bm25K1,
            double bm25B
    ) {
        public String retrievalModeOrDefault() {
            if (retrievalMode == null || retrievalMode.isBlank()) {
                return "hybrid";
            }
            String m = retrievalMode.trim().toLowerCase(Locale.ROOT);
            return (m.equals("vector") || m.equals("keyword") || m.equals("hybrid")) ? m : "hybrid";
        }

        /** RRF 的平滑常数 k：越大越"抹平"排名差异，60 是社区常用默认值 */
        public int rrfKOrDefault() {
            return rrfK <= 0 ? 60 : rrfK;
        }

        public double vectorWeightOrDefault() {
            return vectorWeight <= 0 ? 1.0 : vectorWeight;
        }

        public double keywordWeightOrDefault() {
            return keywordWeight < 0 ? 1.0 : keywordWeight;
        }

        public double bm25K1OrDefault() {
            return bm25K1 <= 0 ? 1.5 : bm25K1;
        }

        public double bm25BOrDefault() {
            // b 允许为 0（表示不做长度归一化），所以不能用 <= 0 判断
            return bm25B < 0 ? 0.75 : bm25B;
        }
    }
}
