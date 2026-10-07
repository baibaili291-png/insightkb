package com.insightkb.common;

/**
 * 限流器抽象。
 *
 * 抽这个接口不是为了"面向接口编程"的教条，而是因为两种实现的适用面完全不同：
 *   - 进程内令牌桶：零依赖、零网络开销，但多副本部署时各限各的；
 *   - Redis + Lua：多副本共享计数，代价是每次请求多一次网络往返。
 * 部署形态变了只换实现类，业务代码（Controller）不用动一行。
 */
public interface RateLimiter {

    /** 取不到令牌时抛 RateLimitException（带重试等待时间） */
    void acquire(String key);

    /** 当前实际用的后端，展示在 /api/system/info 里，便于确认配置是否生效 */
    String backend();
}
