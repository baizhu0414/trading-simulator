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
        // 1. 核心线程数：与Druid连接池匹配（Druid max-active=20 → 核心线程数=8，避免连接浪费）
        executor.setCorePoolSize(8);
        // 2. 最大线程数：略大于核心数，应对突发批量任务（避免创建过多临时线程）
        executor.setMaxPoolSize(10);
        // 3. 队列容量：足够大，容纳压测下的批量任务（避免频繁触发拒绝策略）
        executor.setQueueCapacity(10000);
        // 4. 空闲线程存活时间：缩短，减少TIMED_WAITING的daemon线程
        executor.setKeepAliveSeconds(10);
        // 5. 线程名前缀：便于定位问题
        executor.setThreadNamePrefix("db-persist-");
        // 6. 拒绝策略：CallerRunsPolicy（核心！避免任务丢失，保护Disruptor）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 7. 优雅停机：等待任务完成，避免数据丢失
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 使用独立线程池处理重试
     */
    @Bean("retryExecutor")
    public ThreadPoolTaskExecutor retryExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("retry-");
        ex.initialize();
        return ex;
    }

    /**
     * 分片撮合线程池
     */
    @Bean("shardingMatchingExecutor")
    public ShardingMatchingExecutor shardingMatchingExecutor() {
        // 建议分片数设置为 CPU 核心数，或者 2 * CPU
        int shardCount = Runtime.getRuntime().availableProcessors();
        return new ShardingMatchingExecutor(Math.min(8, Math.max(4, shardCount)));
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
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };
    }
}