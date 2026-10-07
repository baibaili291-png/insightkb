package com.insightkb.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightkb.common.RedisLock;
import com.insightkb.config.InsightKbProperties;
import com.insightkb.model.ChatMessage;
import com.insightkb.model.Conversation;
import com.insightkb.model.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话与消息的持久化（M3 · 多轮对话记忆）。
 *
 * 为什么要存消息而不只是存摘要：摘要是有损的，一旦用户回头追问被压缩掉的那一段，
 * 系统只能答"我不知道"。原始消息留着可以兜底，摘要只用于压缩送进 Prompt 的长度。
 *
 * 存储分层（M3 · 两级缓存）：
 *   - MySQL 是冷存储 / 真相源：每次读写都落库，重启不丢、可审计。
 *   - Redis 是热缓存：把"最近活跃的会话"按消息列表缓存起来，设 TTL。
 *     取历史时先打 Redis，命中就省掉一次 MySQL 查询；未命中再回源 MySQL 并回写预热。
 *     Redis 挂了 / 没配：自动退化成纯 MySQL，绝不因此让对话功能不可用。
 */
@Component
public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    /** Redis 里会话消息的 key 前缀；TTL 是热缓存的存活时间，过期即回源 MySQL */
    private static final String CACHE_KEY_PREFIX = "ikb:conv:";
    private static final long CACHE_TTL_SECONDS = 1800L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringRedisTemplate redis;
    private final RedisLock redisLock;

    public ConversationStore(ObjectProvider<JdbcTemplate> provider,
                            ObjectProvider<StringRedisTemplate> redisProvider,
                            ObjectProvider<RedisLock> lockProvider,
                            InsightKbProperties props) {
        this.jdbc = props.storage().persist() ? provider.getIfAvailable() : null;
        this.redis = redisProvider.getIfAvailable();
        this.redisLock = lockProvider.getIfAvailable();
    }

    public boolean enabled() {
        return jdbc != null;
    }

    public void createIfAbsent(String id, String title) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO kb_conversation (id, title, turn_count)
                    VALUES (?, ?, 0)
                    ON DUPLICATE KEY UPDATE id = id
                    """, id, truncate(title, 200));
        } catch (Exception e) {
            log.error("创建会话 {} 失败", id, e);
        }
    }

    public Conversation get(String id) {
        if (jdbc == null) {
            return null;
        }
        try {
            List<Conversation> list = jdbc.query(
                    "SELECT id, title, summary, turn_count FROM kb_conversation WHERE id = ?",
                    (rs, i) -> new Conversation(rs.getString("id"), rs.getString("title"),
                            rs.getString("summary"), rs.getInt("turn_count")), id);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.error("读取会话 {} 失败", id, e);
            return null;
        }
    }

    /** 取最近 n 条消息，按时间正序（旧 → 新），拼 Prompt 时要的就是这个顺序 */
    public List<ChatMessage> recentMessages(String conversationId, int limit) {
        if (jdbc == null) {
            return List.of();
        }
        // 两级缓存：先打 Redis 热缓存，命中直接返回；未命中回源 MySQL 并预热
        if (redis != null) {
            try {
                List<String> cached = redis.opsForList().range(cacheKey(conversationId), -limit, -1);
                if (cached != null && !cached.isEmpty()) {
                    List<ChatMessage> msgs = new ArrayList<>(cached.size());
                    boolean allOk = true;
                    for (String json : cached) {
                        ChatMessage m = fromCacheJson(json);
                        if (m == null) {
                            allOk = false;
                            break;
                        }
                        msgs.add(m);
                    }
                    if (allOk) {
                        return msgs;
                    }
                }
            } catch (Exception e) {
                log.warn("Redis 读取会话历史失败，回退 MySQL：{}", e.getMessage());
            }
            // 回源 + 防击穿：同一会话高并发冷读时，用一把按 conversationId 的分布式锁，
            // 保证只有一个线程真正去查 MySQL 预热，其余线程要么命中别人刚写好的缓存，
            // 要么回退到 MySQL 直查最近 limit 条，避免"缓存击穿"把 DB 打爆。
            if (redisLock != null && redisLock.available()) {
                String lockKey = CACHE_KEY_PREFIX + "lock:warm:" + conversationId;
                redisLock.runLocked(lockKey, 5, () -> {
                    // 双重检查：等锁期间可能已有别的线程预热好了
                    List<String> again = redis.opsForList().range(cacheKey(conversationId), -limit, -1);
                    if (again == null || again.isEmpty()) {
                        List<ChatMessage> all = queryAll(conversationId);
                        warmCache(conversationId, all);
                    }
                });
            } else {
                List<ChatMessage> all = queryAll(conversationId);
                warmCache(conversationId, all);
            }
            // 锁持有者通常已预热好；没拿到锁（别人正在预热）或预热失败，则直查 MySQL 兜底
            List<String> cached = redis.opsForList().range(cacheKey(conversationId), -limit, -1);
            if (cached != null && !cached.isEmpty()) {
                List<ChatMessage> msgs = new ArrayList<>(cached.size());
                boolean allOk = true;
                for (String json : cached) {
                    ChatMessage cm = fromCacheJson(json);
                    if (cm == null) {
                        allOk = false;
                        break;
                    }
                    msgs.add(cm);
                }
                if (allOk) {
                    int from = Math.max(0, msgs.size() - limit);
                    return new ArrayList<>(msgs.subList(from, msgs.size()));
                }
            }
            return queryRecent(conversationId, limit);
        }
        return queryRecent(conversationId, limit);
    }

    /** 仅查最近 limit 条（无 Redis 时的直连路径） */
    private List<ChatMessage> queryRecent(String conversationId, int limit) {
        try {
            return jdbc.query("""
                    SELECT role, content FROM (
                        SELECT id, role, content FROM kb_message
                        WHERE conversation_id = ?
                        ORDER BY id DESC LIMIT ?
                    ) t ORDER BY id
                    """,
                    (rs, i) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                    conversationId, limit);
        } catch (Exception e) {
            log.error("读取会话 {} 的历史消息失败", conversationId, e);
            return List.of();
        }
    }

    /** 取出该会话的全部消息（用于冷启动预热），按时间正序 */
    private List<ChatMessage> queryAll(String conversationId) {
        try {
            return jdbc.query(
                    "SELECT role, content FROM kb_message WHERE conversation_id = ? ORDER BY id",
                    (rs, i) -> new ChatMessage(rs.getString("role"), rs.getString("content")),
                    conversationId);
        } catch (Exception e) {
            log.error("预热会话 {} 历史失败", conversationId, e);
            return List.of();
        }
    }

    /** 把消息列表整体写入 Redis 列表并刷新 TTL（写穿） */
    private void warmCache(String conversationId, List<ChatMessage> messages) {
        if (redis == null || messages.isEmpty()) {
            return;
        }
        try {
            String key = cacheKey(conversationId);
            redis.delete(key);
            for (ChatMessage m : messages) {
                redis.opsForList().rightPush(key, toCacheJson(m.role(), m.content()));
            }
            redis.expire(key, java.time.Duration.ofSeconds(CACHE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("预热会话 {} 到 Redis 失败：{}", conversationId, e.getMessage());
        }
    }

    public void appendMessage(String conversationId, String role, String content,
                              List<RetrievedChunk> cited, long retrievalMs, long generationMs) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO kb_message
                        (conversation_id, role, content, cited_chunks, retrieval_ms, generation_ms)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, conversationId, role, content, toJson(cited), (int) retrievalMs, (int) generationMs);

            if ("assistant".equals(role)) {
                jdbc.update("""
                        UPDATE kb_conversation
                        SET turn_count = turn_count + 1,
                            title = IFNULL(title, ?)
                        WHERE id = ?
                        """, truncate(content, 200), conversationId);
            }
            // 写穿：新消息追加进 Redis 热缓存并刷新 TTL，下一次取历史就能命中
            pushCache(conversationId, role, content);
        } catch (Exception e) {
            log.error("写入会话消息失败", e);
        }
    }

    /** 单条消息追加进 Redis 列表（写穿），失败仅告警不影响主流程 */
    private void pushCache(String conversationId, String role, String content) {
        if (redis == null) {
            return;
        }
        try {
            String key = cacheKey(conversationId);
            redis.opsForList().rightPush(key, toCacheJson(role, content));
            redis.expire(key, java.time.Duration.ofSeconds(CACHE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("追加会话消息到 Redis 失败：{}", e.getMessage());
        }
    }

    public void updateSummary(String conversationId, String summary) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("UPDATE kb_conversation SET summary = ? WHERE id = ?", summary, conversationId);
        } catch (Exception e) {
            log.error("更新会话摘要失败", e);
        }
    }

    private String toJson(List<RetrievedChunk> cited) {
        if (cited == null || cited.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(cited.stream().map(rc -> rc.chunk().id()).toList());
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    /** 缓存里每条消息存成 {"role":..,"content":..} 的紧凑 JSON，读时再解析 */
    private String toCacheJson(String role, String content) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("content", content);
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private ChatMessage fromCacheJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> m = mapper.readValue(json, Map.class);
            String role = (String) m.get("role");
            String content = (String) m.get("content");
            if (role == null || content == null) {
                return null;
            }
            return new ChatMessage(role, content);
        } catch (Exception e) {
            return null;
        }
    }

    private String cacheKey(String conversationId) {
        return CACHE_KEY_PREFIX + conversationId;
    }
}
