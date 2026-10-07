package com.insightkb.common;

import com.insightkb.config.InsightKbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流（进程内实现）。
 *
 * 注意：本类不再是 Spring 组件（已移除 @Component）。它由 RateLimiterConfig 按配置
 * 统一装配——单机用我、多副本用 RedisTokenBucketLimiter，接口相同，业务代码不用改。
 * 因为是用 @Bean 方法返回我这个实例，所以 @Scheduled 的空闲清理仍然会生效。
 *
 * 为什么是令牌桶而不是固定窗口计数：固定窗口在窗口切换的瞬间会放过两倍流量
 * （窗口末尾打满 + 窗口开头打满），压测时表现为"偶尔突发 429 之后又全部放行"。
 * 令牌桶按时间匀速补充，既能限制平均速率，又允许一定程度的突发（桶容量），
 * 对"用户偶尔连问两句"这种真实场景更友好。
 *
 * 为什么是进程内而不是 Redis：限流的正确性要求是"全局一致"，单机部署下
 * 进程内实现完全够用，而且省掉一次网络往返（每次问答都要查一次 Redis 的话，
 * 光限流就要多花 1~2 ms）。代价是**多实例部署时会各限各的** —— 这一点必须写清楚：
 * 真正上线做多副本时，要把这个类换成 Redis + Lua 版本（Lua 保证"读-算-写"原子，
 * 否则并发下会超发）。接口保持不变，替换成本只有一个类。
 *
 * 清理策略：空闲超过 10 分钟的桶会被后台清理，否则每个访问过的 IP 都留一个桶，
 * 内存会随访问来源数无限增长 —— 这是进程内限流最常见的内存泄漏点。
 */
public class TokenBucketLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    private static final long IDLE_EVICT_MILLIS = 10 * 60 * 1000L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final InsightKbProperties props;

    public TokenBucketLimiter(InsightKbProperties props) {
        this.props = props;
    }

    public boolean enabled() {
        return props.limit() != null && props.limit().isEnabled();
    }

    /**
     * 尝试取一个令牌。
     *
     * @param key 限流维度（目前按 IP；多租户场景可换成 userId）
     * @throws RateLimitException 桶空时抛出，带上重试等待时间
     */
    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public void acquire(String key) {
        if (!enabled()) {
            return;
        }
        double qps = props.limit().qpsOrDefault();
        int burst = props.limit().burstOrDefault();
        long now = System.currentTimeMillis();

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
        long waitMillis;
        synchronized (bucket) {
            // 按流逝的时间匀速补令牌，上限是桶容量
            long elapsed = Math.max(0, now - bucket.lastRefillMillis);
            bucket.tokens = Math.min(burst, bucket.tokens + elapsed * qps / 1000.0);
            bucket.lastRefillMillis = now;

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return;
            }
            // 还差多少毫秒才能凑够一个令牌
            waitMillis = (long) Math.ceil((1.0 - bucket.tokens) / qps * 1000);
        }

        log.warn("限流触发 | key={} | qps={} | 需等待 {} ms", key, qps, waitMillis);
        throw new RateLimitException(
                "请求过于频繁，请稍后再试（当前限制 " + qps + " 次/秒）", waitMillis);
    }

    /** 空闲桶清理，避免内存随访问来源数无限增长（每 10 分钟跑一次） */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000)
    public int evictIdle() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> e = it.next();
            synchronized (e.getValue()) {
                if (now - e.getValue().lastRefillMillis > IDLE_EVICT_MILLIS) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public int bucketCount() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;

        private Bucket(double tokens, long lastRefillMillis) {
            this.tokens = tokens;
            this.lastRefillMillis = lastRefillMillis;
        }
    }
}
