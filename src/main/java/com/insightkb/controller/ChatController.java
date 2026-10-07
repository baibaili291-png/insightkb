package com.insightkb.controller;

import com.insightkb.common.InsightKbException;
import com.insightkb.common.RateLimiter;
import com.insightkb.model.ChatQuery;
import com.insightkb.model.ChatResult;
import com.insightkb.model.RetrievedChunk;
import com.insightkb.service.ChatService;
import com.insightkb.service.MetricsService;
import com.insightkb.service.RetrieverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答接口（SSE 流式）。
 *
 * 关于协议选择的一个要点：浏览器原生的 EventSource 只支持 GET，
 * 而把长问题塞进 query string 既容易超长也容易被日志完整记录。
 * 所以这里用「POST + text/event-stream」返回标准 SSE 文本，
 * 前端则用 fetch 的 ReadableStream 手动解析 —— 协议仍然是 SSE，
 * 但摆脱了 EventSource 的方法限制。前端怎么解析见 static/app.js。
 *
 * 另外，生成线程用的是 Java 21 的虚拟线程：
 * 流式生成是典型的"长时间等待 IO"，平台线程会被白白占住；
 * 虚拟线程在等待时自动让出载体线程，几百个并发问答也不会把线程池耗尽。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final ChatService chatService;
    private final RetrieverService retriever;
    private final RateLimiter limiter;
    private final Executor sseExecutor;
    private final MetricsService metrics;

    public ChatController(ChatService chatService,
                          RetrieverService retriever,
                          RateLimiter limiter,
                          @Qualifier("sseExecutor") Executor sseExecutor,
                          MetricsService metrics) {
        this.chatService = chatService;
        this.retriever = retriever;
        this.limiter = limiter;
        this.sseExecutor = sseExecutor;
        this.metrics = metrics;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatQuery query, HttpServletRequest httpRequest) {
        if (query == null || query.question() == null || query.question().isBlank()) {
            throw new InsightKbException("问题不能为空");
        }
        // 限流放在建连接之前：模型调用是整条链路里最贵的一步，
        // 被限掉的请求不该占用 SSE 连接和线程池
        limiter.acquire(clientKey(httpRequest));
        // 计数放在限流之后：被限掉的请求不计入 QPS（它们是"应该被挡在门外"的），
        // 这样指标反映的是"真实放行的流量"
        metrics.recordRequest();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 用户关闭页面 / 网络断开时，必须让生成线程尽早停下来，
        // 否则模型 token 会继续被消耗，账单却没人看
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onCompletion(() -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));
        emitter.onError(e -> clientGone.set(true));

        sseExecutor.execute(() -> {
            try {
                ChatResult result = chatService.ask(query.question(), query.conversationId(), delta -> {
                    if (clientGone.get()) {
                        return;
                    }
                    send(emitter, Map.of("type", "delta", "content", delta));
                });

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("retrievalMs", result.retrievalMs());
                done.put("generationMs", result.generationMs());
                done.put("totalMs", result.totalMs());
                done.put("embeddingModel", result.embeddingModel());
                done.put("chatModel", result.chatModel());
                done.put("retrievalMode", retriever.mode());
                done.put("conversationId", result.conversationId());
                done.put("rewritten", result.rewritten());
                done.put("effectiveQuery", result.effectiveQuery());
                done.put("degraded", result.degraded());
                done.put("contexts", toViews(result.contexts()));
                send(emitter, done);

                emitter.complete();
            } catch (Exception e) {
                log.error("问答处理失败", e);
                send(emitter, Map.of("type", "error",
                        "message", e.getMessage() == null ? "服务内部错误" : e.getMessage()));
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 限流维度：优先取 X-Forwarded-For 的第一跳。
     * 直接拿 remoteAddr 的话，一旦前面挂了 Nginx，所有请求都会被当成同一个来源，
     * 限流立刻退化成"全局限一个人"。反过来，XFF 可被伪造，所以它只适合做限流维度，
     * 绝不能用于鉴权。
     */
    private String clientKey(HttpServletRequest request) {
        if (request == null) {
            return "anonymous";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "anonymous" : request.getRemoteAddr();
    }

    private List<ContextView> toViews(List<RetrievedChunk> contexts) {
        return contexts.stream()
                .map(rc -> new ContextView(
                        rc.chunk().id(),
                        rc.chunk().docId(),
                        rc.chunk().docName(),
                        rc.chunk().index(),
                        rc.chunk().preview(),
                        rc.chunk().text(),
                        round(rc.score()),
                        rc.vectorRank(),
                        rc.keywordRank()))
                .toList();
    }

    private double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private void send(SseEmitter emitter, Object payload) {
        try {
            emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new UncheckedIOException(new IOException("SSE 连接已关闭", e));
        }
    }

    /** 送给前端的召回片段视图：只暴露展示需要的字段，不把向量带出去 */
    public record ContextView(
            String id,
            String docId,
            String docName,
            int index,
            String preview,
            String text,
            double score,
            Integer vectorRank,
            Integer keywordRank
    ) {
    }
}
