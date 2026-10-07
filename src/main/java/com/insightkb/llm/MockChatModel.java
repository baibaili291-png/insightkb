package com.insightkb.llm;

import com.insightkb.model.RetrievedChunk;

import java.util.function.Consumer;

/**
 * 未配置 API Key 时的降级实现。
 *
 * 它不做任何"生成"，而是把检索到的片段原样呈现出来。
 * 这样做的原因：RAG 效果差有两种可能——检索没召回对，或者模型没用好召回的内容。
 * 在没接模型的阶段直接把原始片段摊开看，能立刻判断问题出在哪一环。
 * 这个"可观测性"在真实项目里同样重要，只是平时被掩盖在模型输出后面。
 */
public class MockChatModel implements ChatModel {

    /** 模拟流式输出的节奏，纯为了前端能看出打字机效果 */
    private static final long DELAY_MS = 12L;
    private static final int PIECE_SIZE = 4;

    @Override
    public String stream(ChatRequest request, Consumer<String> onDelta) {
        String answer = buildAnswer(request);

        StringBuilder sb = new StringBuilder(answer.length());
        for (int i = 0; i < answer.length(); i += PIECE_SIZE) {
            String piece = answer.substring(i, Math.min(answer.length(), i + PIECE_SIZE));
            sb.append(piece);
            onDelta.accept(piece);
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return sb.toString();
    }

    private String buildAnswer(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("【Mock 模式】当前未接入大模型，下面是检索到的原始片段，用于验证检索链路。\n\n");
        sb.append("问题：").append(request.question()).append("\n\n");

        if (request.contexts().isEmpty()) {
            sb.append("知识库中没有检索到相关内容。\n\n");
            sb.append("排查方向：文档状态是否为 READY、问题是否与文档主题差距过大、切块长度是否过小。");
            return sb.toString();
        }

        sb.append("命中 ").append(request.contexts().size()).append(" 个相关片段：\n\n");
        int i = 1;
        for (RetrievedChunk rc : request.contexts()) {
            sb.append("[").append(i++).append("] ")
                    .append(rc.chunk().docName())
                    .append(" · 第 ").append(rc.chunk().index() + 1).append(" 块")
                    .append("（相似度 ").append(String.format("%.3f", rc.score())).append("）\n")
                    .append(trim(rc.chunk().text(), 220))
                    .append("\n\n");
        }
        sb.append("---\n");
        sb.append("配置 LLM_API_KEY 并把 insightkb.llm.mode 切到 openai 之后，");
        sb.append("这里会变成大模型基于上述片段生成的连贯回答（含 [1][2] 引用角标）。");
        return sb.toString();
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    @Override
    public String name() {
        return "mock-retrieval-viewer";
    }

    @Override
    public boolean isMock() {
        return true;
    }
}
