package com.insightkb.common;

import com.insightkb.config.InsightKbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Redis + Lua 令牌桶限流。
 *
 * 为什么必须用 Lua 而不是"GET → 算 → SET"三步：
 * 三步之间有时间窗口，两个并发请求可能同时读到 tokens=1、各自减成 0 然后写回，
 * 结果是放行了两次 —— 限流在并发下悄悄失效，而且只在压测时才暴露。
 * Lua 脚本在 Redis 里是单线程原子执行的，把读、算、写合成一步，这是唯一可靠做法。
 *
 * 为什么用 Hash 而不是两个 String key：
 * tokens 和 ts 必须一起读写，拆成两个 key 就会出现"读了新 ts、用了旧 tokens"的错配。
 *
 * 为什么给 key 设 EXPIRE：不设的话每个访问过的 IP 都会在 Redis 里留一个永久 key，
 * 时间一长就是一堆僵尸数据。设 1 小时足够覆盖限流的时间尺度。
 */
public class RedisTokenBucketLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketLimiter.class);

    /**
     * 返回值约定：-1 表示放行，正数表示"还需等待多少毫秒"。
     * 用返回值而不是抛异常，是因为 Lua 里没法抛 Java 异常。
     */
    private static final String LUA = """
            local key    = KEYS[1]
            local rate   = tonumber(ARGV[1])
            local burst  = tonumber(ARGV[2])
            local now    = tonumber(ARGV[3])
            local ttl    = tonumber(ARGV[4])

            local last   = tonumber(redis.call('HGET', key, 'ts') or now)
            local tokens = tonumber(redis.call('HGET', key, 'tokens') or burst)

            local elapsed = math.max(0, now - last)
            tokens = math.min(burst, tokens + elapsed * rate / 1000.0)

            if tokens >= 1.0 then
                tokens = tokens - 1.0
                redis.call('HSET', key, 'tokens', tokens, 'ts', now)
                redis.call('EXPIRE', key, ttl)
                return -1
            end

            local wait = math.ceil((1.0 - tokens) / rate * 1000)
            redis.call('HSET', key, 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', key, ttl)
            return wait
            """;

    private static final int KEY_TTL_SECONDS = 3600;

    private final StringRedisTemplate redis;
    private final InsightKbProperties props;
    private final DefaultRedisScript<Long> script;

    public RedisTokenBucketLimiter(StringRedisTemplate redis, InsightKbProperties props) {
        this.redis = redis;
        this.props = props;
        this.script = new DefaultRedisScript<>(LUA, Long.class);
    }

    @Override
    public String backend() {
        return "redis";
    }

    @Override
    public void acquire(String key) {
        if (!props.limit().isEnabled()) {
            return;
        }
        double qps = props.limit().qpsOrDefault();
        int burst = props.limit().burstOrDefault();

        Long result;
        try {
            result = redis.execute(script,
                    List.of("ikb:rl:" + key),
                    String.valueOf(qps),
                    String.valueOf(burst),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(KEY_TTL_SECONDS));
        } catch (Exception e) {
            // Redis 挂了不能把业务一起拖死：降级为放行，并记日志报警。
            // 限流的目的是"保护系统"，如果限流器本身成了单点故障，就本末倒置了。
            log.error("Redis 限流执行失败，本次请求放行（需检查 Redis 是否可用）", e);
            return;
        }

        long wait = result == null ? -1 : result;
        if (wait > 0) {
            log.warn("限流触发（Redis）| key={} | qps={} | 需等待 {} ms", key, qps, wait);
            throw new RateLimitException(
                    "请求过于频繁，请稍后再试（当前限制 " + qps + " 次/秒）", wait);
        }
    }
}
