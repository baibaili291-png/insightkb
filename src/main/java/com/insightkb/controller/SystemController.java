package com.insightkb.controller;

import com.insightkb.common.ApiResponse;
import com.insightkb.common.RateLimiter;
import com.insightkb.config.InsightKbProperties;
import com.insightkb.llm.ChatModel;
import com.insightkb.rag.Bm25Index;
import com.insightkb.rag.EmbeddingModel;
import com.insightkb.rag.VectorStore;
import com.insightkb.service.DocumentRegistry;
import com.insightkb.repository.KnowledgeStore;
import com.insightkb.service.MetricsService;
import com.insightkb.service.RetrieverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统概览接口。
 *
 * 前端顶栏会展示当前用的是 Mock 还是真实模型、向量维度、切块参数、知识库规模。
 * 这些信息在调试 RAG 时非常关键 —— 比如"检索结果突然变差"，
 * 第一件要确认的就是"是不是 Embedding 模型被换了、向量维度对不上"。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final DocumentRegistry registry;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final RetrieverService retriever;
    private final KnowledgeStore knowledgeStore;
    private final InsightKbProperties props;
    private final RateLimiter rateLimiter;
    private final MetricsService metrics;

    public SystemController(EmbeddingModel embeddingModel,
                            ChatModel chatModel,
                            DocumentRegistry registry,
                            VectorStore vectorStore,
                            Bm25Index bm25Index,
                            RetrieverService retriever,
                            KnowledgeStore knowledgeStore,
                            InsightKbProperties props,
                            RateLimiter rateLimiter,
                            MetricsService metrics) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.registry = registry;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.retriever = retriever;
        this.knowledgeStore = knowledgeStore;
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("embeddingModel", embeddingModel.name());
        data.put("embeddingDimension", embeddingModel.dimension());
        data.put("chatModel", chatModel.name());
        data.put("mockChat", chatModel.isMock());
        data.put("documentCount", registry.count());
        data.put("chunkCount", vectorStore.size());
        data.put("chunkSize", props.rag().chunkSize());
        data.put("chunkOverlap", props.rag().chunkOverlap());
        data.put("topK", props.rag().topK());
        data.put("recallK", props.rag().recallK());
        data.put("retrievalMode", retriever.mode());
        data.put("persistEnabled", knowledgeStore.enabled());
        // 限流后端在 auto 模式下可能是 Redis 也可能是进程内，必须显式暴露，
        // 否则"以为用了 Redis 其实在跑内存版"这种事根本发现不了
        data.put("rateLimitBackend", rateLimiter.backend());
        data.put("rateLimitEnabled", props.limit().isEnabled());
        // BM25 侧的规模单独暴露：如果 chunkCount 在涨而 termCount 不动，
        // 基本可以断定中文分词出了问题，而不是检索逻辑的问题
        data.put("bm25", bm25Index.stats());
        // 高并发能力（原规划第 3 个项目）的实时指标：QPS / 累计请求 / 60 秒窗口
        data.put("concurrency", metrics.snapshot());
        return ApiResponse.ok(data);
    }
}
