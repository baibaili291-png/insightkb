package com.insightkb.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.insightkb.common.InsightKbException;
import com.insightkb.config.InsightKbProperties;
import com.insightkb.llm.HttpJsonClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远端 Embedding 实现（OpenAI 兼容协议）。
 *
 * 需要注意：DeepSeek 不提供 embedding 接口，所以 embedding 的服务商
 * 往往和对话模型不是同一家。配置里把 chat 与 embedding 拆成两组参数，就是这个原因。
 *
 * 返回向量统一做 L2 归一化，与本地实现保持一致 ——
 * 上层 VectorStore 依赖"已归一化"这个约定来把余弦相似度简化成点积。
 */
public class OpenAiEmbedding implements EmbeddingModel {

    private final InsightKbProperties props;
    private final HttpJsonClient http;

    /** 首次调用后才确定维度（不同模型不同），用于前端展示与一致性校验 */
    private volatile int dimension = -1;

    public OpenAiEmbedding(InsightKbProperties props, HttpJsonClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** 覆写批量：一次请求编码多段文本，切块入库时能省下大量往返 */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.llm().embeddingModel());
        body.put("input", texts);

        JsonNode resp = http.postJson(url("/embeddings"), authHeaders(), body,
                props.llm().timeoutSecondsOrDefault());

        JsonNode data = resp.path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new InsightKbException("Embedding 接口返回结构异常，期望 " + texts.size() + " 条向量");
        }

        List<float[]> result = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode arr = item.path("embedding");
            if (!arr.isArray() || arr.isEmpty()) {
                throw new InsightKbException("Embedding 接口返回了空向量");
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            result.add(l2Normalize(v));
        }
        dimension = result.get(0).length;
        return result;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String name() {
        return props.llm().embeddingModel();
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

    private float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum == 0) {
            return v;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
        return v;
    }
}
