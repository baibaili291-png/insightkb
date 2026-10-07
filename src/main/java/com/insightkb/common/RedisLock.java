package com.insightkb.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的分布式锁（SET NX EX + Lua 释放）。
 *
 * 为什么需要它：会话历史是"两级缓存"，Redis 未命中时要回源 MySQL 再预热。
 * 高并发下，同一个冷会话可能同时涌来几十个请求，每个都发现缓存未命中、
 * 各自去查一次 MySQL —— 这就是经典的"缓存击穿 / 缓存并发预热"问题。
 * 用一把按 conversationId 的锁，保证同一时刻只有一个线程去做预热，
 * 其余线程要么命中别人刚写好的缓存、要么直接回退到 MySQL 直查，避免把 DB 打爆。
 *
 * 释放为什么用 Lua 而不是 DEL：
 * 如果直接 `DEL key`，可能删掉"别人刚拿到的锁"。比如 A 拿锁、过期了，
 * B 拿到锁，A 这时执行完去 DEL —— 就把 B 的锁删了。
 * Lua 释放先比对 token（只有持有者才能删），从根本上杜绝误删。
 */
@Component
public class RedisLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLock(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider.getIfAvailable();
    }

    /** 没有 Redis 时锁不可用：调用方应退化成"直接执行"，靠幂等兜底，而不是报错 */
    public boolean available() {
        return redis != null;
    }

    /**
     * 尝试加锁。成功返回 token（释放时要用），失败（被别人持有）返回 null。
     *
     * @param key        锁键，建议带业务维度，如 "ikb:lock:warm:<conversationId>"
     * @param ttlSeconds 锁自动过期时间。必须大于"临界区预计耗时"，
     *                  否则会出现"业务没跑完锁先过期、别人又拿到锁"的窗口。
     */
    public String tryLock(String key, long ttlSeconds) {
        if (redis == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, java.time.Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            log.warn("获取锁 {} 失败：{}", key, e.getMessage());
            return null;
        }
    }

    /** 释放锁（仅当仍是自己持有）。Lua 保证不会误删别人的锁 */
    public boolean unlock(String key, String token) {
        if (redis == null || token == null) {
            return false;
        }
        try {
            Long r = redis.execute(UNLOCK_SCRIPT, List.of(key), token);
            return r != null && r == 1L;
        } catch (Exception e) {
            log.warn("释放锁 {} 失败：{}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 便捷封装：拿到锁就执行 action，finally 里释放。
     * 没拿到锁返回 false（调用方可据此走"直接查库"的降级路径）。
     */
    public boolean runLocked(String key, long ttlSeconds, Runnable action) {
        String token = tryLock(key, ttlSeconds);
        if (token == null) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            unlock(key, token);
        }
    }
}
