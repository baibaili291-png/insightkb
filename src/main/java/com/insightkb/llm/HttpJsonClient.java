package com.insightkb.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightkb.common.InsightKbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 调用大模型 HTTP 接口的最小封装。
 *
 * 这里刻意没有引入 OkHttp / WebClient：
 * JDK 自带的 java.net.http.HttpClient 已经支持连接复用、超时和流式响应体，
 * 对"只调两个接口（chat + embedding）"的场景完全够用，少一个依赖少一份升级负担。
 * HttpClient 内部持有连接池，必须复用实例，所以这里注册为单例 Bean。
 */
@Component
public class HttpJsonClient {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /** 普通 POST，等完整响应后解析成 JSON */
    public JsonNode postJson(String url, Map<String, String> headers, Object body, int timeoutSeconds) {
        try {
            HttpRequest request = buildRequest(url, headers, body, timeoutSeconds);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new InsightKbException("模型接口返回 " + response.statusCode() + "：" + truncate(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new InsightKbException("调用模型接口失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InsightKbException("调用模型接口被中断", e);
        }
    }

    /**
     * 流式 POST：每读到一行就回调一次。
     * OpenAI 兼容接口的流式响应是 SSE 格式，逐行形如 "data: {...}"，以 "data: [DONE]" 结束。
     */
    public void postJsonStream(String url, Map<String, String> headers, Object body,
                               int timeoutSeconds, Consumer<String> onLine) {
        try {
            HttpRequest request = buildRequest(url, headers, body, timeoutSeconds);
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() >= 400) {
                String err = response.body().collect(Collectors.joining("\n"));
                throw new InsightKbException("模型流式接口返回 " + response.statusCode() + "：" + truncate(err));
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(onLine);
            }
        } catch (IOException e) {
            throw new InsightKbException("流式调用模型接口失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InsightKbException("流式调用模型接口被中断", e);
        }
    }

    private HttpRequest buildRequest(String url, Map<String, String> headers, Object body, int timeoutSeconds) throws IOException {
        String json = mapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return builder.build();
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
