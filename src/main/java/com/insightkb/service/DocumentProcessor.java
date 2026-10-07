package com.insightkb.service;

import com.insightkb.config.InsightKbProperties;
import com.insightkb.model.Chunk;
import com.insightkb.model.DocumentMeta;
import com.insightkb.model.DocumentMeta.DocumentStatus;
import com.insightkb.rag.Bm25Index;
import com.insightkb.repository.KnowledgeStore;
import com.insightkb.rag.EmbeddingModel;
import com.insightkb.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档处理流水线：文本提取 → 切块 → 批量向量化 → 写入向量库。
 *
 * 每个阶段的失败都会被捕获并写入文档状态，而不是抛到线程池里悄无声息地消失 ——
 * 否则用户只会看到文档永远停在"处理中"，却拿不到任何线索。
 */
@Component
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    private final TextExtractor extractor;
    private final Chunker chunker;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final KnowledgeStore knowledgeStore;
    private final DocumentRegistry registry;
    private final InsightKbProperties props;

    public DocumentProcessor(TextExtractor extractor,
                             Chunker chunker,
                             EmbeddingModel embeddingModel,
                             VectorStore vectorStore,
                             Bm25Index bm25Index,
                             KnowledgeStore knowledgeStore,
                             DocumentRegistry registry,
                             InsightKbProperties props) {
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.knowledgeStore = knowledgeStore;
        this.registry = registry;
        this.props = props;
    }

    @Async("docExecutor")
    public void process(String docId) {
        DocumentMeta meta = registry.get(docId);
        Path file = registry.fileOf(docId);
        if (meta == null || file == null) {
            log.warn("文档 {} 的任务不存在，跳过", docId);
            return;
        }

        long start = System.currentTimeMillis();
        try {
            // 1. 提取文本
            String text = extractor.extract(file, meta.name());

            // 2. 切块
            List<String> pieces = chunker.split(text,
                    props.rag().chunkSize(), props.rag().chunkOverlap());
            if (pieces.isEmpty()) {
                throw new IllegalStateException("切块结果为空");
            }

            // 3. 组装切块对象（此刻还没有向量）
            List<Chunk> chunks = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                chunks.add(new Chunk(docId + "#" + i, docId, meta.name(), i, pieces.get(i), null));
            }

            // 4. 批量向量化：一次请求处理整篇文档，比逐块调用快一个数量级
            List<float[]> vectors = embeddingModel.embedBatch(pieces);
            if (vectors.size() != chunks.size()) {
                throw new IllegalStateException("向量数量与切块数量不一致");
            }
            List<Chunk> embedded = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                embedded.add(chunks.get(i).withEmbedding(vectors.get(i)));
            }

            // 5. 写入两个索引：向量库负责语义召回，BM25 负责关键词召回
            //    两路必须同时成功，否则混合检索会退化成单路，而且这种退化是静默的
            vectorStore.addAll(embedded);
            bm25Index.addAll(embedded);
            knowledgeStore.saveChunks(embedded, embeddingModel.name());

            DocumentMeta ready = meta.withStatus(DocumentStatus.READY, embedded.size(), null);
            registry.put(ready);
            knowledgeStore.saveDocument(ready, file);
            log.info("文档处理完成 | {} | {} 块 | 耗时 {} ms | Embedding={}",
                    meta.name(), embedded.size(), System.currentTimeMillis() - start, embeddingModel.name());

        } catch (Exception e) {
            log.error("文档处理失败 | {} | 耗时 {} ms", meta.name(), System.currentTimeMillis() - start, e);
            DocumentMeta failed = meta.withStatus(DocumentStatus.FAILED, 0, e.getMessage());
            registry.put(failed);
            knowledgeStore.saveDocument(failed, file);
        }
    }
}
