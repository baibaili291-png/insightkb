package com.insightkb.service;

import com.insightkb.common.InsightKbException;
import com.insightkb.config.InsightKbProperties;
import com.insightkb.model.Chunk;
import com.insightkb.model.DocumentMeta;
import com.insightkb.model.DocumentMeta.DocumentStatus;
import com.insightkb.rag.Bm25Index;
import com.insightkb.rag.VectorStore;
import com.insightkb.repository.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文档接入的对外入口。
 *
 * 职责边界刻意划得很窄：只负责"校验 → 落盘 → 登记任务 → 交给异步处理器"，
 * 真正的解析与向量化在 DocumentProcessor 里。
 * 这样上传接口的响应时间与文档大小解耦，不会因为一份大 PDF 而变慢。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md", "markdown");

    private final DocumentRegistry registry;
    private final DocumentProcessor processor;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final KnowledgeStore knowledgeStore;
    private final InsightKbProperties props;

    public DocumentService(DocumentRegistry registry,
                           DocumentProcessor processor,
                           VectorStore vectorStore,
                           Bm25Index bm25Index,
                           KnowledgeStore knowledgeStore,
                           InsightKbProperties props) {
        this.registry = registry;
        this.processor = processor;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.knowledgeStore = knowledgeStore;
        this.props = props;
    }

    public DocumentMeta upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InsightKbException("上传文件为空");
        }
        String originalName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new InsightKbException("不支持的文件类型 ." + ext + "，目前支持：pdf / docx / txt / md");
        }

        String docId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Path dir = Paths.get(props.storage().uploadDir()).toAbsolutePath().normalize();
        // 落盘文件名用 docId，不沿用原始文件名：
        // 一是防止 "../" 这类路径穿越，二是避免中文名在不同文件系统上的编码问题
        Path target = dir.resolve(docId + "." + ext);

        try {
            Files.createDirectories(dir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new InsightKbException("文件保存失败：" + e.getMessage(), e);
        }

        DocumentMeta meta = new DocumentMeta(
                docId, originalName, ext, file.getSize(),
                0, DocumentStatus.PROCESSING, null, System.currentTimeMillis());

        registry.put(meta);
        registry.bindFile(docId, target);
        // 先落一条 PROCESSING 记录：即便处理线程崩了，库里也知道有这份文档存在
        knowledgeStore.saveDocument(meta, target);

        log.info("接收文档 {} ({} KB)，已提交异步处理", originalName, file.getSize() / 1024);
        processor.process(docId);

        return meta;
    }

    public List<DocumentMeta> list() {
        return registry.list();
    }

    public DocumentMeta get(String docId) {
        DocumentMeta meta = registry.get(docId);
        if (meta == null) {
            throw new InsightKbException("文档不存在：" + docId);
        }
        return meta;
    }

    public void delete(String docId) {
        get(docId);
        removeFromIndexes(docId);
        Path file = registry.fileOf(docId);
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("原文件删除失败: {}", file, e);
            }
        }
        registry.remove(docId);
        log.info("文档 {} 已删除", docId);
    }

    /** 重新跑一遍处理流水线。换了 Embedding 模型之后必须调用，否则新旧向量维度不一致会被检索跳过。 */
    public DocumentMeta reindex(String docId) {
        DocumentMeta meta = get(docId);
        if (registry.fileOf(docId) == null) {
            throw new InsightKbException("原始文件已丢失，无法重建索引");
        }
        removeFromIndexes(docId);
        DocumentMeta processing = meta.withStatus(DocumentStatus.PROCESSING, 0, null);
        registry.put(processing);
        processor.process(docId);
        return processing;
    }

    public List<Chunk> chunksOf(String docId) {
        get(docId);
        return vectorStore.listByDocument(docId);
    }

    public int totalChunks() {
        return vectorStore.size();
    }

    /** 两个索引一起清：只清一个会导致删除后仍能按关键词/语义被召回，属于典型的脏索引 bug */
    private void removeFromIndexes(String docId) {
        vectorStore.removeByDocument(docId);
        bm25Index.removeByDocument(docId);
        knowledgeStore.deleteDocument(docId);
    }

    private String extensionOf(String filename) {
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase();
    }
}
