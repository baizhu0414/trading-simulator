package com.example.trading.gate;

import com.example.trading.config.ShardingMatchingExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池状态监控工具：判断任意线程池是否满，阻止任务进入
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThreadPoolStatusMonitor {
    // 队列剩余容量阈值百分比（10%）：剩余容量≤10%则判定为队列满
    private static final double QUEUE_FULL_THRESHOLD = 0.1;

    // 注入需要监控的线程池
    @Qualifier("dbPersistenceExecutor")
    private final ThreadPoolTaskExecutor dbPersistenceExecutor;
    @Qualifier("retryExecutor")
    private final ThreadPoolTaskExecutor retryExecutor;
    private final ShardingMatchingExecutor shardingMatchingExecutor;

    /**
     * 核心方法：判断任意线程池是否满
     * @return true=任意线程池满，false=所有线程池正常
     */
    public boolean isAnyThreadPoolFull() {
        // 1. 检查dbPersistenceExecutor
        if (isThreadPoolTaskExecutorFull(dbPersistenceExecutor, "dbPersistenceExecutor")) {
            return true;
        }
        // 2. 检查retryExecutor
        if (isThreadPoolTaskExecutorFull(retryExecutor, "retryExecutor")) {
            return true;
        }
        // 3. 检查ShardingMatchingExecutor的所有分片
        if (isShardingMatchingExecutorFull(shardingMatchingExecutor)) {
            return true;
        }
        // 所有线程池正常
        return false;
    }

    /**
     * 判断ThreadPoolTaskExecutor是否满
     */
    private boolean isThreadPoolTaskExecutorFull(ThreadPoolTaskExecutor executor, String executorName) {
        if (executor == null) {
            log.warn("线程池{}未初始化", executorName);
            return false;
        }
        // 获取底层的ThreadPoolExecutor（Spring封装的适配器）
        ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
        int activeCount = threadPoolExecutor.getActiveCount();
        int maxPoolSize = threadPoolExecutor.getMaximumPoolSize();
        int queueRemainingCapacity = threadPoolExecutor.getQueue().remainingCapacity();
        int queueTotalCapacity = threadPoolExecutor.getQueue().size() + queueRemainingCapacity;

        // 判定逻辑：活跃线程数≥最大线程数 且 队列剩余容量≤总容量的10%
        boolean isFull = activeCount >= maxPoolSize
                && (double) queueRemainingCapacity / queueTotalCapacity <= QUEUE_FULL_THRESHOLD;

        if (isFull) {
            log.warn("[线程池满] {} - 活跃线程数={}, 最大线程数={}, 队列剩余容量={}, 队列总容量={}",
                    executorName, activeCount, maxPoolSize, queueRemainingCapacity, queueTotalCapacity);
        }
        return isFull;
    }

    /**
     * 判断ShardingMatchingExecutor的所有分片是否有任意一个满
     */
    private boolean isShardingMatchingExecutorFull(ShardingMatchingExecutor shardingExecutor) {
        List<Executor> shardExecutors = shardingExecutor.getAllShardExecutors();
        for (int i = 0; i < shardExecutors.size(); i++) {
            Executor executor = shardExecutors.get(i);
            if (!(executor instanceof ThreadPoolExecutor)) {
                log.warn("分片{}的执行器不是ThreadPoolExecutor类型", i);
                continue;
            }
            ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
            int activeCount = threadPoolExecutor.getActiveCount();
            int maxPoolSize = threadPoolExecutor.getMaximumPoolSize();
            int queueRemainingCapacity = threadPoolExecutor.getQueue().remainingCapacity();
            int queueTotalCapacity = 3000; // 分片队列总容量固定为3000（与初始化一致）

            // 判定逻辑：活跃线程数≥最大线程数（1） 且 队列剩余容量≤300（10%）
            boolean isFull = activeCount >= maxPoolSize
                    && queueRemainingCapacity <= queueTotalCapacity * QUEUE_FULL_THRESHOLD;

            if (isFull) {
                log.warn("[分片线程池满] 分片{} - 活跃线程数={}, 最大线程数={}, 队列剩余容量={}, 队列总容量={}",
                        i, activeCount, maxPoolSize, queueRemainingCapacity, queueTotalCapacity);
                return true;
            }
        }
        return false;
    }
}
