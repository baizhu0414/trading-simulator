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
    public Executor dbPersistenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(1000); // 足够大的队列，避免任务溢出
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("db-persist-");
        // 拒绝策略 - 队列满时由调用线程执行，绝不丢弃任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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