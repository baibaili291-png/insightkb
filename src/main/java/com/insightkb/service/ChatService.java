package com.insightkb.service;

import com.insightkb.config.InsightKbProperties;
import com.insightkb.llm.ChatModel;
import com.insightkb.llm.ChatRequest;
import com.insightkb.model.ChatResult;
import com.insightkb.model.RetrievedChunk;
import com.insightkb.rag.EmbeddingModel;
import com.insightkb.repository.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 问答编排：检索 → 组装 Prompt → 流式生成。
 *
 * Prompt 的设计是整条链路里最容易被低估的一环。这里的写法有三个刻意的选择：
 *
 * 1. 明确"只依据参考资料回答"，并给出拒答模板。
 *    不写这条，模型会用自己的先验知识把答案补得"看起来很完整"，而这些内容
 *    恰恰无法被文档佐证 —— 这就是幻觉的主要来源。
 *
 * 2. 要求标注 [1][2] 编号。
 *    编号不是为了好看，是为了让答案可追溯：用户能点开角标核对原文。
 *    一旦模型开始"顺手编一个不存在的 [7]"，我们也能立刻发现。
 *
 * 3. 把片段来源（文档名 + 块序号）也写进参考资料。
 *    模型在回答里引用来源时会更谨慎，同时也方便我们做检索质量的人工评估。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RetrieverService retriever;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ConversationService conversationService;
    private final ConversationStore conversationStore;
    private final InsightKbProperties props;

    public ChatService(RetrieverService retriever,
                       ChatModel chatModel,
                       EmbeddingModel embeddingModel,
                       ConversationService conversationService,
                       ConversationStore conversationStore,
                       InsightKbProperties props) {
        this.retriever = retriever;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.conversationService = conversationService;
        this.conversationStore = conversationStore;
        this.props = props;
    }

    public ChatResult ask(String question, String conversationId, Consumer<String> onDelta) {
        long start = System.currentTimeMillis();

        // 1. 会话准备：取历史、必要时把带指代的问题改写成独立 query
        ConversationService.Prepared prepared =
                conversationService.prepare(conversationId, question);
        long preparedAt = System.currentTimeMillis();

        // 2. 用改写后的 query 检索 —— 这一步是多轮记忆里收益最大的部分
        List<RetrievedChunk> contexts = retriever.retrieve(prepared.effectiveQuery(), props.rag().topK());
        long retrievedAt = System.currentTimeMillis();

        ChatRequest request = new ChatRequest(
                prepared.effectiveQuery(),
                systemPrompt(),
                buildUserPrompt(question, prepared.historyBlock(), contexts),
                contexts);

        // 降级：模型超时 / 余额用完 / 网络抖动时，不要让用户什么都看不到。
        // 检索本来就已经跑完了，把片段整理成一段说明顶上去，至少问答链路还是"可用的"。
        // 这比返回 500 有用得多，也是生产环境必须做的一层保护。
        String answer;
        boolean degraded = false;
        try {
            answer = chatModel.stream(request, onDelta);
        } catch (Exception e) {
            log.error("模型调用失败，降级为检索结果展示", e);
            degraded = true;
            answer = degradedAnswer(question, contexts);
            onDelta.accept(answer);
        }
        long finishedAt = System.currentTimeMillis();

        long retrievalMs = retrievedAt - preparedAt;
        long generationMs = finishedAt - retrievedAt;
        log.info("问答完成 | 会话 {} | 召回 {} 段 | 改写 {} | 检索 {} ms | 生成 {} ms",
                prepared.conversationId(), contexts.size(), prepared.rewritten(),
                retrievalMs, generationMs);

        // 3. 落库：本轮问答进 kb_message，供下一轮取历史与压缩摘要
        conversationStore.appendMessage(prepared.conversationId(), "user", question,
                null, 0, 0);
        conversationStore.appendMessage(prepared.conversationId(), "assistant", answer,
                contexts, retrievalMs, generationMs);
        conversationService.maybeCompress(prepared.conversationId(),
                conversationStore.recentMessages(prepared.conversationId(),
                        Math.max(1, props.chat().historyTurnsOrDefault()) * 2));

        return new ChatResult(answer, contexts, retrievalMs, generationMs,
                embeddingModel.name(), chatModel.name(),
                prepared.conversationId(), prepared.effectiveQuery(), prepared.rewritten(),
                degraded);
    }

    private String degradedAnswer(String question, List<RetrievedChunk> contexts) {
        if (contexts.isEmpty()) {
            return "模型暂时不可用，且未检索到相关资料，请稍后重试。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("模型暂时不可用，以下是检索到的相关资料，供你自行判断：\n\n");
        for (int i = 0; i < contexts.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                    .append(contexts.get(i).chunk().docName())
                    .append("：").append(contexts.get(i).chunk().preview()).append("\n");
        }
        return sb.toString();
    }

    private String systemPrompt() {
        return """
                你是一个严谨的企业知识库问答助手。你必须严格依据用户提供的参考资料回答问题。

                回答规则：
                1. 只使用参考资料中的信息作答，不得引入参考资料之外的任何知识，不得凭借常识或经验补充内容。
                2. 如果参考资料不足以回答该问题，直接回复"根据现有资料无法回答该问题"，并说明缺少哪方面的信息。绝不编造。
                3. 每一条来自资料的结论，都要在句末标注对应编号，例如 [1]、[2]。编号必须真实存在，不得虚构。
                4. 如果不同资料之间存在冲突，要指出冲突并分别标注来源，而不是自行选一个。
                5. 使用中文作答，语言简洁，条目较多时分点说明。
                6. 用户可能接着上一轮追问（如"那它的功率呢"）。结合【最近对话】判断指代对象，
                   再依据参考资料作答；判断不了就按参考资料里最相关的那条答。
                """;
    }

    /**
     * 注意这里有个刻意的区分：**送进 Prompt 的是用户原话，送去检索的是改写后的 query**。
     * 用户问"它的功率是多少"，模型看到原话才能结合历史给出连贯的回答；
     * 而检索必须拿到改写后的完整问题，否则召回的全是不相干的片段。
     * 两者混用是很多 RAG 项目多轮效果差的直接原因。
     */
    private String buildUserPrompt(String question, String historyBlock, List<RetrievedChunk> contexts) {
        StringBuilder sb = new StringBuilder();

        if (!historyBlock.isBlank()) {
            sb.append(historyBlock).append("\n\n");
        }

        if (contexts.isEmpty()) {
            sb.append("参考资料：（无）\n\n");
        } else {
            sb.append("参考资料：\n\n");
            for (int i = 0; i < contexts.size(); i++) {
                RetrievedChunk rc = contexts.get(i);
                sb.append("[").append(i + 1).append("] 来源：")
                        .append(rc.chunk().docName())
                        .append(" · 第 ").append(rc.chunk().index() + 1).append(" 块\n")
                        .append("内容：").append(rc.chunk().text())
                        .append("\n\n");
            }
        }

        sb.append("用户问题：").append(question);
        return sb.toString();
    }
}
