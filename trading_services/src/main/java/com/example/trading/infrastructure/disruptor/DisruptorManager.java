package com.example.trading.infrastructure.disruptor;

import com.example.trading.domain.event.PersistSignal;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ThreadFactory;

@Slf4j
@Component
public class DisruptorManager {
    private final PersistEventHandler persistEventHandler;

    @Autowired
    @Qualifier("disruptorThreadFactory")
    private ThreadFactory disruptorThreadFactory;

    private Disruptor<PersistEvent> disruptor;

    public DisruptorManager(PersistEventHandler persistEventHandler) {
        this.persistEventHandler = persistEventHandler;
    }

    @PostConstruct
    public void initDisruptor() {
        int bufferSize = 1024 * 1024;
        disruptor = new Disruptor<>(
                PersistEvent::new,
                bufferSize,
                disruptorThreadFactory,
                com.lmax.disruptor.dsl.ProducerType.MULTI,
                new BlockingWaitStrategy()
        );

        disruptor.handleEventsWith(persistEventHandler);
        disruptor.start();
        log.info("Disruptor初始化完成，使用自定义ThreadFactory：{}",
                disruptorThreadFactory.getClass().getSimpleName());
    }

    public void producePersistSignal(PersistSignal signal) {
        disruptor.getRingBuffer().publishEvent((event, sequence) -> event.setSignal(signal));
    }

    @PreDestroy
    public void shutdown() {
        if (disruptor != null) {
            disruptor.shutdown();
            log.info("Disruptor已优雅关闭");
        }
    }
}