package com.insightkb.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightkb.config.InsightKbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的对话模型实现。
 *
 * 目前 DeepSeek、通义、智谱、硅基流动、月之暗面等都提供 OpenAI 兼容接口，
 * 所以只写这一个实现 + 改 base-url 就能切换服务商，不用为每家写一个客户端。
 *
 * SSE 响应体格式（每行）：
 *   data: {"choices":[{"delta":{"content":"你"}}]}
 *   data: [DONE]
 * 注意 delta.content 可能为空（首帧只带 role、末帧只带 finish_reason），必须判空，
 * 否则会把大量空字符串推进前端，看起来像"卡住不动"。
 */
public class OpenAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatModel.class);

    private final InsightKbProperties props;
    private final HttpJsonClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiChatModel(InsightKbProperties props, HttpJsonClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public String stream(ChatRequest request, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.llm().chatModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())
        ));
        body.put("stream", true);
        // 知识库问答要的是"忠于原文"，不是"发挥创意"，温度调低能明显减少模型自行补充内容
        body.put("temperature", 0.3);

        StringBuilder full = new StringBuilder();
        http.postJsonStream(url("/chat/completions"), authHeaders(), body,
                props.llm().timeoutSecondsOrDefault(), line -> {
                    String delta = parseDelta(line);
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        onDelta.accept(delta);
                    }
                });
        return full.toString();
    }

    private String parseDelta(String line) {
        if (line == null || !line.startsWith("data:")) {
            return "";
        }
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(payload);
            return node.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            log.debug("跳过无法解析的流式分片: {}", payload);
            return "";
        }
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + props.llm().apiKey());
    }

    private String url(String path) {
        String base = props.llm().baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    @Override
    public String name() {
        return props.llm().chatModel();
    }
}
