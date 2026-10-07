package com.insightkb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文档处理线程池。
 *
 * 为什么要异步：一个 30 页的 PDF，抽文本 + 切块 + 向量化要几秒到几十秒。
 * 如果放在上传接口里同步做，用户点完上传要干等，浏览器还可能超时，
 * 更要命的是这个线程被长时间占用，并发上传几个文档就把 Tomcat 线程池打满了。
 * 所以上传接口只做"落盘 + 建任务"，真正的处理丢到独立线程池。
 *
 * 拒绝策略选 CallerRunsPolicy 而不是 AbortPolicy：
 * 队列满时让调用线程自己执行，代价是上传请求变慢，但任务不会丢。
 * 对文档处理这种"慢但要保结果"的任务，丢任务比变慢严重得多。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * SSE 生成线程池：直接用 Java 21 虚拟线程。
     *
     * 流式生成的特点是"大部分时间在等网络 IO"，用平台线程池的话，
     * 池子大小就成了并发上限，调大了浪费内存、调小了请求排队。
     * 虚拟线程在阻塞时自动让出载体线程，可以按请求创建，
     * 不用再为"池子开多大"纠结。
     */
    @Bean("sseExecutor")
    public Executor sseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("docExecutor")
    public Executor docExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("doc-process-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等在途的文档处理完再退出，避免出现"永久 PROCESSING"的脏状态
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
