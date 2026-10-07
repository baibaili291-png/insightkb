package com.insightkb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求并发指标（原规划"高并发练兵场"融入 InsightKB 的一部分）。
 *
 * 记录三类数字，全部能在 /api/system/info 的 concurrency 字段看到，
 * 面试时可以直接说"线上 QPS 峰值 X、累计请求 Y"：
 *   - qps         当前这一秒的请求数（瞬时）
 *   - qps2s       当前秒 + 上一秒（更平滑的瞬时值）
 *   - total       累计请求数（重启归零；用 Redis 时多实例共享）
 *   - window60s   最近 60 秒请求数之和（Redis 时按分钟桶累加）
 *
 * 为什么计数也放 Redis：
 * 限流、缓存都已经是 Redis 了，指标如果只存进程内，多实例部署时每个实例
 * 只看到自己那部分流量，QPS 严重失真。Redis 的 INCR 是原子的，天然适合做
 * 全局计数器。Redis 没配时自动退化成进程内 AtomicLong，功能不丢、只是不再是全局。
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private static final String QPS_KEY = "ikb:metrics:qps:";        // 后缀 epochSecond
    private static final String MIN_KEY = "ikb:metrics:min:";         // 后缀 epochSecond/60
    private static final String TOTAL_KEY = "ikb:metrics:total";

    private final StringRedisTemplate redis;

    // 无 Redis 时的退化实现：环形数组保存最近 60 秒计数
    private final AtomicLong inMemTotal = new AtomicLong(0);
    private final AtomicLong[] ring = new AtomicLong[60];
    private final long[] ringSec = new long[60];

    public MetricsService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider.getIfAvailable();
        for (int i = 0; i < 60; i++) {
            ring[i] = new AtomicLong(0);
            ringSec[i] = -1;
        }
    }

    /** 每收到一个问答请求调用一次：把计数 +1。失败静默，绝不影响主流程。 */
    public void recordRequest() {
        long sec = Instant.now().getEpochSecond();
        if (redis != null) {
            try {
                String qk = QPS_KEY + sec;
                redis.opsForValue().increment(qk);
                redis.expire(qk, Duration.ofSeconds(3)); // 秒桶只留 3 秒就够算瞬时 QPS
                redis.opsForValue().increment(TOTAL_KEY);
                String mk = MIN_KEY + (sec / 60);
                redis.opsForValue().increment(mk);
                redis.expire(mk, Duration.ofSeconds(130)); // 分钟桶留 130 秒，够回看 60 秒窗口
            } catch (Exception e) {
                log.warn("记录请求指标失败：{}", e.getMessage());
            }
            return;
        }
        // 退化：进程内计数
        inMemTotal.incrementAndGet();
        int idx = (int) ((sec % 60 + 60) % 60);
        if (ringSec[idx] != sec) {
            ringSec[idx] = sec;
            ring[idx].set(0);
        }
        ring[idx].incrementAndGet();
    }

    /** 当前指标快照，喂给 /api/system/info */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("backend", redis != null ? "redis" : "in-memory");
        if (redis != null) {
            long sec = Instant.now().getEpochSecond();
            long cur = toLong(redis.opsForValue().get(QPS_KEY + sec));
            long prev = toLong(redis.opsForValue().get(QPS_KEY + (sec - 1)));
            long total = toLong(redis.opsForValue().get(TOTAL_KEY));
            long window = 0;
            for (int i = 0; i < 60; i++) {
                window += toLong(redis.opsForValue().get(MIN_KEY + (sec / 60 - i)));
            }
            m.put("qps", cur);
            m.put("qps2s", cur + prev);
            m.put("total", total);
            m.put("window60s", window);
        } else {
            long sec = Instant.now().getEpochSecond();
            int idx = (int) ((sec % 60 + 60) % 60);
            long cur = ringSec[idx] == sec ? ring[idx].get() : 0;
            int pidx = (int) (((sec - 1) % 60 + 60) % 60);
            long prev = ringSec[pidx] == sec - 1 ? ring[pidx].get() : 0;
            long window = 0;
            for (int i = 0; i < 60; i++) {
                window += ring[i].get();
            }
            m.put("qps", cur);
            m.put("qps2s", cur + prev);
            m.put("total", inMemTotal.get());
            m.put("window60s", window);
        }
        return m;
    }

    private long toLong(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
