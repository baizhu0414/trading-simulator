package com.example.trading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为了方便对撮合任务按照ID分片，并且order也需要分片存储。
 */
@Slf4j
public class ShardingMatchingExecutor {

    private final Executor[] executors;
    private final int shardCount;
    private final AtomicInteger threadCounter = new AtomicInteger(0);

    public ShardingMatchingExecutor(int shardCount) {
        this.shardCount = shardCount;
        this.executors = new Executor[shardCount];
        for (int i = 0; i < shardCount; i++) {
            final int index = i;
            this.executors[i] = new ThreadPoolExecutor(
                    1, // 核心线程 1
                    1, // 最大线程 1，保证该分片绝对串行
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(3000),
                    r -> new Thread(r, "matching-shard-" + index + "-" + threadCounter.incrementAndGet()),
                    // 拒绝策略：由调用线程处理（或者根据业务需求抛异常/入队）
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
        log.info("撮合引擎分片初始化完成，共 {} 个分片", shardCount);
    }

    // 新增：暴露所有分片线程池（用于状态监控）
    public List<Executor> getAllShardExecutors() {
        return Arrays.asList(executors);
    }

    // 原有方法保持不变
    public Executor getExecutor(String securityId) {
        // 利用 hashCode 取模，保证同一个 securityId 永远走到同一个 executor
        int index = Math.abs(securityId.hashCode()) % shardCount;
        return executors[index];
    }

    /**
     * 通用方法，提交任务并等待结果。
     */
    public <T> T submitAndWait(String securityId, java.util.function.Supplier<T> task) {
        Executor executor = getExecutor(securityId);
        try {
            return CompletableFuture.supplyAsync(task::get, executor).join();
        } catch (Exception e) {
            throw new RuntimeException("分片任务执行失败", e);
        }
    }

    /**
     * 提交无返回值的任务
     */
    public void submitAsync(String securityId, Runnable task) {
        Executor executor = getExecutor(securityId);
        executor.execute(task);
    }
}

