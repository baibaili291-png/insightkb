package com.insightkb.config;

import com.insightkb.common.RateLimiter;
import com.insightkb.common.RedisTokenBucketLimiter;
import com.insightkb.common.TokenBucketLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 按环境与配置装配限流器。
 *
 * backend 三种取值：
 *   redis  —— 强制用 Redis（配了却连不上就直接报错，便于早发现配置问题）
 *   memory —— 强制用进程内实现（压测时想排除 Redis 网络开销就用这个）
 *   auto   —— 默认。Redis 能 ping 通就用 Redis，否则安静地退回进程内实现
 *
 * 为什么默认 auto 而不是一上来就要求 Redis：
 * 这个项目要能被"clone 下来就跑"，强制依赖 Redis 会让第一次启动就失败。
 * 但用了 Redis 就必须在 /api/system/info 里能看到 backend 到底是哪个 ——
 * 否则"以为用了 Redis 其实在跑内存版"这种事根本发现不了。
 */
@Configuration
public class RateLimiterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfig.class);

    @Bean
    public RateLimiter rateLimiter(ObjectProvider<StringRedisTemplate> redisProvider,
                                   InsightKbProperties props) {
        String backend = props.limit().backendOrDefault();
        StringRedisTemplate redis = redisProvider.getIfAvailable();

        if ("redis".equals(backend)) {
            if (redis == null) {
                throw new IllegalStateException(
                        "insightkb.limit.backend=redis，但未配置 Redis（检查 spring.data.redis.*）");
            }
            log.info("限流后端：Redis + Lua");
            return new RedisTokenBucketLimiter(redis, props);
        }

        if ("memory".equals(backend)) {
            log.info("限流后端：进程内令牌桶（显式指定）");
            return new TokenBucketLimiter(props);
        }

        boolean reachable = false;
        if (redis != null) {
            try {
                String pong = redis.getConnectionFactory().getConnection().ping();
                reachable = "PONG".equalsIgnoreCase(pong);
            } catch (Exception e) {
                log.warn("Redis 不可用，限流回退到进程内实现：{}", e.getMessage());
            }
        }
        log.info("限流后端：{}", reachable ? "Redis + Lua（自动探测可用）" : "进程内令牌桶（未检测到 Redis）");
        return reachable ? new RedisTokenBucketLimiter(redis, props) : new TokenBucketLimiter(props);
    }
}
