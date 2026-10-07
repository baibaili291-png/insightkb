package com.insightkb.llm;

import java.util.function.Consumer;

/**
 * 对话模型抽象。
 *
 * 统一采用"流式"作为唯一接口形态：非流式只是把 onDelta 传一个空实现。
 * 反过来做（以非流式为主、流式另开一个方法）会导致两套 prompt 组装逻辑，
 * 很容易出现"流式和非流式回答不一致"这种难查的问题。
 */
public interface ChatModel {

    /**
     * 流式生成回答。
     *
     * @param request 对话请求（含问题、拼好的 prompt、召回的原始片段）
     * @param onDelta 每收到一段增量文本回调一次，用于 SSE 推送给前端
     * @return 拼接完成后的完整回答
     */
    String stream(ChatRequest request, Consumer<String> onDelta);

    /** 模型标识，展示在前端 Trace 面板 */
    String name();

    /** 是否为未接入真实模型的降级实现 */
    default boolean isMock() {
        return false;
    }
}
