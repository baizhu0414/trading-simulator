package com.example.trading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

// 新增线程池配置类
@Configuration
@EnableAsync
public class AsyncConfig {
    /**
     * 数据库持久化线程池
     */
    @Bean("dbPersistenceExecutor")
    public ThreadPoolTaskExecutor dbPersistenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 1. 核心线程数：建议 4-8，不需要太多
        executor.setCorePoolSize(5);

        // 2. 最大线程数：比核心多一点即可，用于应对突发流量
        executor.setMaxPoolSize(8);

        // 3. 队列容量：1000-5000 均可，根据你的内存容忍度
        executor.setQueueCapacity(1000);

        // 4. 空闲线程存活时间
        executor.setKeepAliveSeconds(60);

        // 5. 线程名前缀（方便排查问题）
        executor.setThreadNamePrefix("db-persist-");

        // 6. 【关键修改】拒绝策略：
        // 使用 AbortPolicy，队列满时直接报错，保护 Disruptor 不被拖垮
        // 或者自定义一个策略，记录日志后丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        // 7. 【可选】优雅停机相关配置
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 初始化
        executor.initialize();
        return executor;
    }

    /**
     * 分片撮合线程池
     */
    @Bean("shardingMatchingExecutor")
    public ShardingMatchingExecutor shardingMatchingExecutor() {
        // 建议分片数设置为 CPU 核心数，或者 2 * CPU
        int shardCount = Runtime.getRuntime().availableProcessors();
        return new ShardingMatchingExecutor(Math.max(4, shardCount));
    }

    @Bean("disruptorThreadFactory")
    public ThreadFactory disruptorThreadFactory() {
        String threadNamePrefix = "disruptor-event-"; // 定义前缀常量
        AtomicInteger threadNum = new AtomicInteger(1);

        // 返回自定义ThreadFactory，同时通过匿名内部类暴露前缀
        return new ThreadFactory() {
            // 新增：暴露线程名前缀的方法
            public String getThreadNamePrefix() {
                return threadNamePrefix;
            }

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setName(threadNamePrefix + threadNum.getAndIncrement());
                thread.setDaemon(false);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };
    }
}