package com.insightkb.controller;

import com.insightkb.common.ApiResponse;
import com.insightkb.common.InsightKbException;
import com.insightkb.model.RetrievedChunk;
import com.insightkb.model.RetrievalCompare;
import com.insightkb.service.RetrieverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索对比调试接口。
 *
 * 存在的唯一目的：把"混合检索到底有没有用"从一句主观判断变成一组可复现的数字。
 *
 * 用法：
 *   GET /api/retrieval/debug?q=Model%20X200%20的额定功率&topK=5
 * 一次返回向量路、关键词路、融合路各自的 Top-K 与各路耗时。
 *
 * 想拿到能写进简历的召回率数字，流程是：
 *   1. 准备 30~50 条问题，人工标注每条的"正确答案应该来自哪一块"；
 *   2. 批量调这个接口，分别统计三种模式下 Top-K 是否命中标注块；
 *   3. 得到三条命中率曲线 —— 这就是"召回率从 X% 提升到 Y%"的真实来源。
 * 数字必须是自己跑出来的：面试官一定会问"你怎么测的"。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final RetrieverService retriever;

    public RetrievalController(RetrieverService retriever) {
        this.retriever = retriever;
    }

    @GetMapping("/debug")
    public ApiResponse<Map<String, Object>> debug(@RequestParam("q") String question,
                                                  @RequestParam(value = "topK", defaultValue = "5") int topK) {
        if (question == null || question.isBlank()) {
            throw new InsightKbException("q 不能为空");
        }
        if (topK <= 0 || topK > 50) {
            throw new InsightKbException("topK 需在 1~50 之间");
        }

        RetrievalCompare c = retriever.compare(question, topK);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", c.question());
        data.put("mode", c.mode());
        data.put("vector", toViews(c.vector()));
        data.put("keyword", toViews(c.keyword()));
        data.put("fused", toViews(c.fused()));
        data.put("vectorMs", c.vectorMs());
        data.put("keywordMs", c.keywordMs());
        data.put("fuseMs", c.fuseMs());
        data.put("totalMs", c.totalMs());
        // 融合路里有多少条是"只有关键词路召回的" —— 这个数字直接说明补位效果
        data.put("keywordOnlyCount", c.fused().stream()
                .filter(rc -> rc.vectorRank() == null && rc.keywordRank() != null)
                .count());
        data.put("vectorOnlyCount", c.fused().stream()
                .filter(rc -> rc.vectorRank() != null && rc.keywordRank() == null)
                .count());
        return ApiResponse.ok(data);
    }

    private List<ChunkView> toViews(List<RetrievedChunk> list) {
        return list.stream().map(rc -> new ChunkView(
                rc.chunk().id(),
                rc.chunk().docName(),
                rc.chunk().index(),
                rc.chunk().preview(),
                Math.round(rc.score() * 10000) / 10000.0,
                rc.vectorRank(),
                rc.keywordRank()
        )).toList();
    }

    public record ChunkView(
            String id,
            String docName,
            int index,
            String preview,
            double score,
            Integer vectorRank,
            Integer keywordRank
    ) {
    }
}
