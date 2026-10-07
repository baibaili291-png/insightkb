package com.insightkb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切块。
 *
 * 为什么不用"定长硬切"：
 * 定长切块会把一句话、一个完整的表格行从中间劈开，检索到的片段失去上下文，
 * 大模型拿到半句话很容易答偏。所以这里采用「段落优先 + 句子兜底 + 尾部重叠」三段式策略：
 *
 *   1. 先按空行切成段落 —— 段落是作者已经划好的语义边界，应尽量保留；
 *   2. 单个段落超过 chunkSize 时，再按中文句末标点切句，避免出现超长块；
 *   3. 相邻切块之间保留 overlap 字符重叠 —— 防止答案正好落在两个块的接缝处，
 *      导致两块各含半句、谁都检索不到完整答案。
 */
@Component
public class Chunker {

    private static final Logger log = LoggerFactory.getLogger(Chunker.class);

    public List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(100, chunkSize);
        // 重叠不能超过块长的一半，否则切块会几乎完全重复，白白浪费向量与上下文预算
        int ov = Math.min(Math.max(0, overlap), size / 2);

        List<String> units = toUnits(text, size);
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String unit : units) {
            if (buf.length() > 0 && buf.length() + unit.length() + 1 > size) {
                String finished = buf.toString().trim();
                if (!finished.isEmpty()) {
                    chunks.add(finished);
                }
                buf.setLength(0);
                String tail = tailOf(finished, ov);
                if (!tail.isEmpty()) {
                    buf.append(tail);
                }
            }
            buf.append(unit).append('\n');
        }

        String last = buf.toString().trim();
        if (!last.isEmpty()) {
            chunks.add(last);
        }

        log.info("切块完成: {} 字符 -> {} 块, 平均 {} 字符",
                text.length(), chunks.size(),
                chunks.isEmpty() ? 0 : text.length() / chunks.size());
        return chunks;
    }

    /** 段落优先；超长段落降级为句子序列 */
    private List<String> toUnits(String text, int chunkSize) {
        List<String> units = new ArrayList<>();
        for (String para : text.split("\n\\s*\n")) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.length() <= chunkSize) {
                units.add(p);
            } else {
                units.addAll(packSentences(p, chunkSize));
            }
        }
        return units;
    }

    /** 按句末标点切句，再重新打包到接近 chunkSize，避免产生一堆几十字的碎块 */
    private List<String> packSentences(String para, int chunkSize) {
        List<String> result = new ArrayList<>();
        String[] sentences = para.split("(?<=[。！？!?；;])");
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() > 0 && buf.length() + s.length() > chunkSize) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(s);
        }
        if (!buf.toString().isBlank()) {
            result.add(buf.toString().trim());
        }
        return result;
    }

    private String tailOf(String s, int overlap) {
        if (overlap <= 0 || s.isEmpty()) {
            return "";
        }
        return s.length() <= overlap ? s : s.substring(s.length() - overlap);
    }
}
