package com.insightkb.repository;

import com.insightkb.model.Chunk;
import com.insightkb.model.DocumentMeta;
import com.insightkb.rag.Bm25Index;
import com.insightkb.rag.EmbeddingModel;
import com.insightkb.rag.VectorStore;
import com.insightkb.service.DocumentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时把索引从 MySQL 读回内存。
 *
 * 为什么要单独做这件事：现在的检索是纯内存的，进程一停索引就没了。
 * 有了这一步，"重启" 从"全部重建"降级成"读表 + 反序列化"，几十份文档也就几秒。
 *
 * 一个容易踩的坑在这里处理掉了：**向量维度可能与当前 Embedding 模型不一致**
 * （典型场景是换了模型、或上次是用本地哈希向量存的）。
 * 维度对不上的块只进 BM25 索引、不进向量库 —— 硬塞进去会算出完全错误的相似度，
 * 表现为"检索结果莫名其妙变差"，而且极难定位。这里改为显式提示需要重建索引。
 */
@Component
public class IndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final KnowledgeStore store;
    private final DocumentRegistry registry;
    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final EmbeddingModel embeddingModel;

    public IndexBootstrap(KnowledgeStore store,
                          DocumentRegistry registry,
                          VectorStore vectorStore,
                          Bm25Index bm25Index,
                          EmbeddingModel embeddingModel) {
        this.store = store;
        this.registry = registry;
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!store.enabled()) {
            log.info("持久化未开启，跳过索引恢复（需要先在 application.yml 打开 insightkb.storage.persist）");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            List<KnowledgeStore.StoredDocument> docs = store.loadDocuments();
            for (KnowledgeStore.StoredDocument d : docs) {
                registry.put(d.meta());
                if (d.storagePath() != null) {
                    Path p = Paths.get(d.storagePath());
                    // 原始文件可能被手工删掉了：绑上去但不报错，重建索引时会明确提示
                    if (Files.exists(p)) {
                        registry.bindFile(d.meta().id(), p);
                    }
                }
            }

            int expectedDim = embeddingModel.dimension();
            List<KnowledgeStore.StoredChunk> rows = store.loadChunks();
            List<Chunk> withVector = new ArrayList<>();
            int skipped = 0;

            for (KnowledgeStore.StoredChunk row : rows) {
                // BM25 只依赖文本，任何时候都能直接恢复
                bm25Index.addAll(List.of(row.chunk()));
                float[] vec = row.chunk().embedding();
                if (vec != null && vec.length == expectedDim) {
                    withVector.add(row.chunk());
                } else {
                    skipped++;
                }
            }
            vectorStore.addAll(withVector);

            log.info("索引恢复完成 | 文档 {} 篇 | 切块 {} 块（向量可用 {} 块）| 耗时 {} ms",
                    docs.size(), rows.size(), withVector.size(), System.currentTimeMillis() - start);

            if (skipped > 0) {
                List<String> needReindex = store.docsWithoutUsableVectors(expectedDim);
                log.warn("有 {} 块的向量维度与当前模型（{} 维，{}）不一致，已跳过向量召回。" +
                                "涉及文档 {} 篇，需调用 POST /api/documents/{{id}}/reindex 重建索引",
                        skipped, expectedDim, embeddingModel.name(), needReindex.size());
            }
        } catch (Exception e) {
            // 恢复失败不能阻断启动：服务照常可用，只是这一轮没有历史数据
            log.error("从 MySQL 恢复索引失败，将以空索引继续运行", e);
        }
    }

    /** 供运维查看：当前库里的文档数（与内存里的可能对不上，用于排查恢复是否完整） */
    public int persistedDocumentCount() {
        return store.enabled() ? store.loadDocuments().size() : 0;
    }

    /** 恢复后把状态仍停留在 PROCESSING 的文档标成失败 —— 说明上次处理被中断了 */
    public void markInterrupted(DocumentMeta meta) {
        log.info("文档 {} 上次处理未完成（进程中断），标记为失败", meta.id());
    }
}
