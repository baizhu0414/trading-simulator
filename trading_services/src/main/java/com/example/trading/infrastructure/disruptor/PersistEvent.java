package com.example.trading.infrastructure.disruptor;

import com.example.trading.domain.event.PersistSignal;
import lombok.Data;

/**
 * Disruptor事件载体（包装PersistSignal）
 */
@Data
public class PersistEvent {
    private PersistSignal signal;
}