package com.insightkb.llm;

import com.insightkb.model.RetrievedChunk;

import java.util.List;

/**
 * 一次对话请求的完整上下文。
 *
 * 之所以不直接把拼好的字符串传给模型实现，是因为两类实现需要的信息不同：
 * - 真实模型只需要 systemPrompt + userPrompt；
 * - Mock 实现需要原始的 contexts，才能把检索结果原样呈现出来用于链路验证。
 * 把结构化数据传进去，两边都不用去反解析一个字符串。
 */
public record ChatRequest(
        String question,
        String systemPrompt,
        String userPrompt,
        List<RetrievedChunk> contexts
) {
}
