package com.insightkb.service;

import com.insightkb.config.InsightKbProperties;
import com.insightkb.llm.ChatModel;
import com.insightkb.llm.ChatRequest;
import com.insightkb.model.ChatMessage;
import com.insightkb.model.Conversation;
import com.insightkb.repository.ConversationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 多轮对话记忆（M3）。
 *
 * 单轮 RAG 有个很明显的短板：用户问"它的功率是多少"时，检索拿到的 query 只有
 * "它的功率是多少" 这七个字 —— 既没有型号也没有对象，向量和 BM25 都不可能召回对。
 * 多轮记忆解决的就是这件事，它分三步：
 *
 * 1. **上下文查询改写**：把带指代的问题结合历史改写成独立可检索的 query。
 *    这是收益最大的一步 —— 检索质量的问题大多出在 query 本身不完整，而不是算法不够好。
 *
 * 2. **历史消息进 Prompt**：让模型知道上一轮说了什么，回答才能连贯。
 *    只取最近 N 轮，避免 prompt 无限膨胀。
 *
 * 3. **滚动摘要压缩**：对话超过阈值轮次后，把较早的内容压成一段摘要存进
 *    kb_conversation.summary，只留最近窗口的完整消息。
 *    这样长对话的 token 成本是常数级，而不是随轮次线性上涨。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationStore store;
    private final ChatModel chatModel;
    private final InsightKbProperties props;

    public ConversationService(ConversationStore store,
                               ChatModel chatModel,
                               InsightKbProperties props) {
        this.store = store;
        this.chatModel = chatModel;
        this.props = props;
    }

    public String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 提问前的准备工作：确保会话存在、取历史、必要时改写 query。
     *
     * @return 改写后的检索 query、拼好的历史文本、以及是否发生了改写（前端 Trace 会显示）
     */
    public Prepared prepare(String conversationId, String question) {
        String id = (conversationId == null || conversationId.isBlank()) ? newId() : conversationId;

        Conversation conv = store.get(id);
        if (conv == null) {
            store.createIfAbsent(id, question);
            conv = new Conversation(id, question, null, 0);
        }

        int window = Math.max(1, props.chat().historyTurnsOrDefault()) * 2;
        List<ChatMessage> history = store.recentMessages(id, window);

        String effectiveQuery = question;
        boolean rewritten = false;
        if (!history.isEmpty() && props.chat().queryRewrite() && !chatModel.isMock()) {
            String rewrittenQuery = rewrite(question, history);
            if (rewrittenQuery != null && !rewrittenQuery.isBlank()
                    && !rewrittenQuery.equalsIgnoreCase(question)) {
                effectiveQuery = rewrittenQuery;
                rewritten = true;
            }
        }

        String historyBlock = buildHistoryBlock(conv.summary(), history);
        log.info("会话 {} | 历史 {} 条 | 摘要 {} | 改写 {}",
                id, history.size(), conv.summary() == null ? "无" : "有", rewritten);
        return new Prepared(id, effectiveQuery, historyBlock, rewritten);
    }

    /** 把带指代的问题改写成能独立检索的问题 */
    private String rewrite(String question, List<ChatMessage> history) {
        String dialog = history.stream()
                .map(m -> (m.role().equals("user") ? "用户：" : "助手：") + m.content())
                .reduce("", (a, b) -> a + "\n" + b);
        String system = """
                你是查询改写助手。根据对话历史，把用户最新的追问改写成一条"不依赖上下文也能看懂"的完整检索问题。
                要求：
                1. 只输出改写后的问题本身，不要任何解释、标点以外的文字。
                2. 保留原问题里的专有名词、型号、数值。
                3. 如果最新问题本身已经完整，原样输出即可。
                """;
        try {
            ChatRequest request = new ChatRequest(question, system,
                    "对话历史：" + dialog + "\n\n最新追问：" + question, List.of());
            StringBuilder sb = new StringBuilder();
            chatModel.stream(request, sb::append);
            String out = sb.toString().trim();
            // 模型偶尔会带上引号或多余前缀，做一次轻量清洗
            out = out.replaceAll("^[\"'「《]*", "").replaceAll("[\"'」》]*$", "").trim();
            return out;
        } catch (Exception e) {
            log.warn("查询改写失败，回退用原始问题检索", e);
            return question;
        }
    }

    private String buildHistoryBlock(String summary, List<ChatMessage> history) {
        if ((summary == null || summary.isBlank()) && history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("【更早对话的摘要】\n").append(summary).append("\n\n");
        }
        if (!history.isEmpty()) {
            sb.append("【最近对话】\n");
            for (ChatMessage m : history) {
                sb.append(m.role().equals("user") ? "用户：" : "助手：")
                        .append(m.content()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 滚动摘要：每 N 轮把"已有摘要 + 最近窗口"再压一遍。
     *
     * 为什么用滚动摘要而不是"一次性总结全部历史"：后者每轮都要把全部历史喂给模型，
     * 轮次一多成本反而更高。滚动摘要每次只处理固定长度的输入，成本是常数。
     */
    public void maybeCompress(String conversationId, List<ChatMessage> recent) {
        Conversation conv = store.get(conversationId);
        if (conv == null || chatModel.isMock() || recent.isEmpty()) {
            return;
        }
        int every = props.chat().summaryEveryTurnsOrDefault();
        if (conv.turnCount() == 0 || conv.turnCount() % every != 0) {
            return;
        }
        try {
            String dialog = recent.stream()
                    .map(m -> (m.role().equals("user") ? "用户：" : "助手：") + m.content())
                    .reduce("", (a, b) -> a + "\n" + b);
            String system = """
                    你是对话摘要助手。把下面的对话压缩成一段不超过 200 字的中文摘要。
                    必须保留：涉及的设备型号、数值指标、用户已经确认过的结论。
                    丢弃：寒暄、重复表述、模型的安全声明。只输出摘要本身。
                    """;
            String user = "已有摘要：\n" + (conv.summary() == null ? "（无）" : conv.summary())
                    + "\n\n新增对话：\n" + dialog;
            StringBuilder sb = new StringBuilder();
            chatModel.stream(new ChatRequest("", system, user, List.of()), sb::append);
            String summary = sb.toString().trim();
            if (!summary.isBlank()) {
                store.updateSummary(conversationId, summary);
                log.info("会话 {} 已更新摘要（第 {} 轮，摘要 {} 字）",
                        conversationId, conv.turnCount(), summary.length());
            }
        } catch (Exception e) {
            log.warn("生成会话摘要失败，不影响本轮回答", e);
        }
    }

    public record Prepared(
            String conversationId,
            String effectiveQuery,
            String historyBlock,
            boolean rewritten
    ) {
    }
}
