package com.insightkb.config;

import com.insightkb.llm.ChatModel;
import com.insightkb.llm.HttpJsonClient;
import com.insightkb.llm.MockChatModel;
import com.insightkb.llm.OpenAiChatModel;
import com.insightkb.rag.Bm25Index;
import com.insightkb.rag.EmbeddingModel;
import com.insightkb.rag.HashingEmbedding;
import com.insightkb.rag.OpenAiEmbedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置决定装配哪一套向量模型与对话模型。
 *
 * 这里用显式装配而不是 @ConditionalOnProperty + @Component，
 * 是因为"是否可用"要同时判断 mode、apiKey、model 三个字段，
 * 写成条件注解反而绕；用一个构造函数把判断集中在一处，读起来更直接。
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * BM25 关键词索引。k1 / b 从配置注入，方便在做评测集时按语料调参对比。
     */
    @Bean
    public Bm25Index bm25Index(InsightKbProperties props) {
        return new Bm25Index(props.rag().bm25K1OrDefault(), props.rag().bm25BOrDefault());
    }

    @Bean
    public EmbeddingModel embeddingModel(InsightKbProperties props, HttpJsonClient http) {
        if (props.llm().useRemoteEmbedding()) {
            log.info("Embedding 模式: 远端接口 ({}, {})", props.llm().baseUrl(), props.llm().embeddingModel());
            return new OpenAiEmbedding(props, http);
        }
        log.info("Embedding 模式: 本地哈希向量（零依赖，仅供链路验证）");
        return new HashingEmbedding();
    }

    @Bean
    public ChatModel chatModel(InsightKbProperties props, HttpJsonClient http) {
        if (props.llm().useRemoteChat()) {
            log.info("对话模型模式: 远端接口 ({}, {})", props.llm().baseUrl(), props.llm().chatModel());
            return new OpenAiChatModel(props, http);
        }
        log.warn("对话模型模式: Mock（未配置 LLM_API_KEY，将直接展示检索片段）");
        return new MockChatModel();
    }
}
