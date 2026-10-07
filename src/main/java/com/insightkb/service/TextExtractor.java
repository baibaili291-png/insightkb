package com.insightkb.service;

import com.insightkb.common.InsightKbException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档 → 纯文本。
 *
 * 设计取舍：
 * - 没有引入 Apache Tika，因为 Tika 会带进几十 MB 传递依赖，而本项目只需要 4 种格式。
 *   按扩展名直接分派，依赖更可控，启动也更快。
 * - 提取后统一做一次文本规整，因为 PDF 抽出来的文本经常有大量碎行和多余空白，
 *   不规整的话切块边界会很脏，直接影响检索质量。
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    public String extract(Path file, String filename) {
        String name = filename.toLowerCase();
        try {
            String raw;
            if (name.endsWith(".pdf")) {
                raw = fromPdf(file);
            } else if (name.endsWith(".docx")) {
                raw = fromDocx(file);
            } else if (name.endsWith(".doc")) {
                throw new InsightKbException("暂不支持旧版 .doc 格式，请用 Word 另存为 .docx 后重新上传");
            } else {
                raw = Files.readString(file, StandardCharsets.UTF_8);
            }
            String normalized = normalize(raw);
            if (normalized.isBlank()) {
                throw new InsightKbException("文档内容为空，可能是扫描版 PDF（图片型），需要先做 OCR");
            }
            log.info("文档解析完成: {} | 原始 {} 字符 -> 规整后 {} 字符", filename, raw.length(), normalized.length());
            return normalized;
        } catch (InsightKbException e) {
            throw e;
        } catch (IOException e) {
            throw new InsightKbException("文档解析失败：" + filename, e);
        }
    }

    private String fromPdf(Path file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private String fromDocx(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * 文本规整：
     * 1. 统一换行符
     * 2. 去掉行尾空白、合并行内连续空格
     * 3. 把连续 3 个以上换行压成 2 个（保留段落分隔，去掉 PDF 常见的碎空行）
     * 4. 修复 PDF 里常见的"中文之间被插入空格"问题
     */
    private String normalize(String raw) {
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("[ \\t\\u00A0]+", " ");
        s = s.replaceAll("(?<=[\\u4e00-\\u9fa5]) (?=[\\u4e00-\\u9fa5])", "");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
