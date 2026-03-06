package com.example.trading.infrastructure.disruptor;

import com.lmax.disruptor.EventFactory;
import org.springframework.stereotype.Component;

/**
 * Disruptor事件工厂（必须实现EventFactory）
 */
@Component
public class PersistEventFactory implements EventFactory<PersistEvent> {
    @Override
    public PersistEvent newInstance() {
        return new PersistEvent();
    }
}