package com.insightkb.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightkb.config.InsightKbProperties;
import com.insightkb.model.Chunk;
import com.insightkb.model.DocumentMeta;
import com.insightkb.model.DocumentMeta.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档与切块的持久化（M3）。
 *
 * 为什么落 MySQL 而不是继续放内存：内存方案每重启一次就要重新上传全部文档、
 * 重新跑一遍 Embedding —— 开发时只是烦，一旦要演示或压测，几十份文档的重建成本
 * 会直接吃掉整个下午。元信息与切块入库后，启动时直接把索引从库里读回来即可。
 *
 * 两个刻意的设计：
 *
 * 1. **可关闭。** 开关默认是关的，未配置数据库时全部方法直接退化成空操作，
 *    保证"clone 下来 mvn spring-boot:run 就能跑"这件事永远成立。
 *    一个必须依赖外部数据库才能启动的项目，对 reviewer 极不友好。
 *
 * 2. **向量按 JSON 存，但不是检索主力。** 这里存向量只是为了重启后不必重新调用
 *    Embedding 接口（省钱省时间）。真正的相似度计算仍在内存里做 —— MySQL 没有向量索引，
 *    全表算余弦在万级切块下会慢到不可用，这正是后面要迁 ES / pgvector 的动机。
 *    换模型后维度对不上，重启时会跳过这些向量并提示需要重建索引。
 */
@Component
public class KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeStore(ObjectProvider<JdbcTemplate> provider, InsightKbProperties props) {
        JdbcTemplate template = props.storage().persist() ? provider.getIfAvailable() : null;
        this.jdbc = template;
        log.info("持久化：{}", template == null
                ? "关闭（纯内存模式，重启后需重新上传文档）"
                : "开启（MySQL，重启后自动恢复索引）");
    }

    public boolean enabled() {
        return jdbc != null;
    }

    /* ==================== 写 ==================== */

    /** 文档 upsert：上传时写 PROCESSING，处理完成后再写一次终态 */
    public void saveDocument(DocumentMeta meta, Path storagePath) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO kb_document
                        (id, name, ext, size_bytes, storage_path, chunk_count, status, error_msg)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        chunk_count = VALUES(chunk_count),
                        status      = VALUES(status),
                        error_msg   = VALUES(error_msg),
                        storage_path = IFNULL(VALUES(storage_path), storage_path)
                    """,
                    meta.id(), meta.name(), meta.type(), meta.size(),
                    storagePath == null ? null : storagePath.toString(),
                    meta.chunkCount(), meta.status().name(), truncate(meta.message(), 1000));
        } catch (Exception e) {
            // 落库失败不应该让上传整体失败：内存里已经可用，下次重启丢失只是体验问题
            log.error("文档 {} 写入 MySQL 失败，本次仅存在于内存", meta.id(), e);
        }
    }

    /** 切块整篇覆盖写入：先删后插，避免重建索引后留下旧切块 */
    public void saveChunks(List<Chunk> chunks, String modelName) {
        if (jdbc == null || chunks.isEmpty()) {
            return;
        }
        try {
            jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", chunks.get(0).docId());
            jdbc.batchUpdate(
                    """
                    INSERT INTO kb_chunk
                        (id, doc_id, doc_name, chunk_index, content, char_count, embedding, dimension, model_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    chunks, chunks.size(),
                    (ps, c) -> {
                        ps.setString(1, c.id());
                        ps.setString(2, c.docId());
                        ps.setString(3, c.docName());
                        ps.setInt(4, c.index());
                        ps.setString(5, c.text());
                        ps.setInt(6, c.text().length());
                        ps.setString(7, toJson(c.embedding()));
                        ps.setObject(8, c.embedding() == null ? null : c.embedding().length);
                        ps.setString(9, modelName);
                    });
            log.info("切块写入 MySQL：文档 {} 共 {} 块（模型 {}）", chunks.get(0).docId(), chunks.size(), modelName);
        } catch (Exception e) {
            log.error("切块写入 MySQL 失败，文档 {}", chunks.get(0).docId(), e);
        }
    }

    public void deleteDocument(String docId) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("DELETE FROM kb_chunk WHERE doc_id = ?", docId);
            jdbc.update("DELETE FROM kb_document WHERE id = ?", docId);
        } catch (Exception e) {
            log.error("从 MySQL 删除文档 {} 失败", docId, e);
        }
    }

    /* ==================== 读 ==================== */

    public List<StoredDocument> loadDocuments() {
        if (jdbc == null) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT id, name, ext, size_bytes, storage_path, chunk_count, status, error_msg, created_at
                FROM kb_document
                ORDER BY created_at
                """,
                (rs, i) -> new StoredDocument(
                        new DocumentMeta(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getString("ext"),
                                rs.getLong("size_bytes"),
                                rs.getInt("chunk_count"),
                                parseStatus(rs.getString("status")),
                                rs.getString("error_msg"),
                                ts(rs.getTimestamp("created_at"))),
                        rs.getString("storage_path")));
    }

    public List<StoredChunk> loadChunks() {
        if (jdbc == null) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT id, doc_id, doc_name, chunk_index, content, embedding, dimension, model_name
                FROM kb_chunk
                ORDER BY doc_id, chunk_index
                """,
                (rs, i) -> new StoredChunk(
                        new Chunk(
                                rs.getString("id"),
                                rs.getString("doc_id"),
                                rs.getString("doc_name"),
                                rs.getInt("chunk_index"),
                                rs.getString("content"),
                                parseVector(rs.getString("embedding"))),
                        (Integer) rs.getObject("dimension"),
                        rs.getString("model_name")));
    }

    /* ==================== 辅助 ==================== */

    private String toJson(float[] vector) {
        if (vector == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(vector);
        } catch (Exception e) {
            log.warn("向量序列化失败，该块将不保存向量");
            return null;
        }
    }

    private float[] parseVector(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, float[].class);
        } catch (Exception e) {
            log.warn("向量反序列化失败，该块将需要重建索引");
            return null;
        }
    }

    private DocumentStatus parseStatus(String s) {
        try {
            return DocumentStatus.valueOf(s);
        } catch (Exception e) {
            return DocumentStatus.FAILED;
        }
    }

    private long ts(Timestamp t) {
        return t == null ? System.currentTimeMillis() : t.getTime();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    /** 汇总每个文档的向量可用情况，用于启动时判断哪些需要重建索引 */
    public List<String> docsWithoutUsableVectors(int expectedDimension) {
        if (jdbc == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try {
            ids = jdbc.queryForList(
                    """
                    SELECT DISTINCT doc_id FROM kb_chunk
                    WHERE embedding IS NULL OR dimension IS NULL OR dimension <> ?
                    """, String.class, expectedDimension);
        } catch (Exception e) {
            log.warn("检查向量维度失败", e);
        }
        return ids;
    }

    public record StoredDocument(DocumentMeta meta, String storagePath) {
    }

    public record StoredChunk(Chunk chunk, Integer dimension, String modelName) {
    }
}
