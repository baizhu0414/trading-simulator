package com.example.trading.infrastructure.disruptor;

import com.example.trading.domain.event.PersistSignal;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.example.trading.domain.pool.PersistSignalObjectPoolFactory;

import java.util.concurrent.ThreadFactory;

@Slf4j
@Component
public class DisruptorManager {
    private final PersistEventHandler persistEventHandler;
    private final PersistSignalObjectPoolFactory signalPoolFactory;
    private final ThreadFactory disruptorThreadFactory;

    private Disruptor<PersistEvent> disruptor;
    private int bufferSize;

    // 构造函数注入所有依赖
    public DisruptorManager(PersistEventHandler persistEventHandler,
                            PersistSignalObjectPoolFactory signalPoolFactory,
                            @Qualifier("disruptorThreadFactory") ThreadFactory disruptorThreadFactory) {
        this.persistEventHandler = persistEventHandler;
        this.signalPoolFactory = signalPoolFactory;
        this.disruptorThreadFactory = disruptorThreadFactory;
    }

    @PostConstruct
    public void initDisruptor() {
        bufferSize = 1024 * 1024;

        // 修复点1：RingBuffer 预填充直接 new PersistEvent()（仅作为容器）
        disruptor = new Disruptor<>(
                PersistEvent::new, // 轻量级容器，避免对象池阻塞
                bufferSize,
                disruptorThreadFactory,
                com.lmax.disruptor.dsl.ProducerType.MULTI,
                new BlockingWaitStrategy()
        );

        disruptor.handleEventsWith(persistEventHandler);
        disruptor.start();
        log.info("Disruptor初始化完成，bufferSize={}", bufferSize);
    }

    // 生产时从 signalPoolFactory 借 PersistSignal
    public void producePersistSignal(PersistSignal signal) {
        // 注意：这里的 signal 应该是调用方从 signalPoolFactory 借的，
        // 或者我们在这里借，然后在消费端还（根据你的业务逻辑调整）

        RingBuffer<PersistEvent> ringBuffer = disruptor.getRingBuffer();
        long sequence = ringBuffer.next();
        try {
            PersistEvent event = ringBuffer.get(sequence);
            // 直接设置 signal（signal 由调用方或这里从池借）
            event.setSignal(signal);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    // ✅ 新增：提供从池借 Signal 的便捷方法（可选，看调用方是否方便）
    public PersistSignal borrowSignalFromPool() {
        return signalPoolFactory.borrowSignal();
    }

    // ✅ 新增：提供归还 Signal 到池的便捷方法（供消费端调用）
    public void returnSignalToPool(PersistSignal signal) {
        signalPoolFactory.returnSignal(signal);
    }

    @PreDestroy
    public void shutdown() {
        if (disruptor != null) {
            disruptor.shutdown();
            log.info("Disruptor已优雅关闭");
        }
    }

    public boolean isRingBufferEmpty() {
        RingBuffer<PersistEvent> ringBuffer = disruptor.getRingBuffer();
        return ringBuffer.remainingCapacity() == this.bufferSize;
    }
}