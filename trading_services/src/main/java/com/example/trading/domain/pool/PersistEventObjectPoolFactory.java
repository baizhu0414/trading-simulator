package com.example.trading.infrastructure.disruptor.pool;

import com.example.trading.infrastructure.disruptor.PersistEvent;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;

/**
 * PersistEvent对象池：适配Disruptor的Event创建逻辑，避免频繁new PersistEvent()
 */
@Component
public class PersistEventObjectPoolFactory extends BasePooledObjectFactory<PersistEvent> {

    private final GenericObjectPool<PersistEvent> eventPool;

    public PersistEventObjectPoolFactory() {
        GenericObjectPoolConfig<PersistEvent> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(10000);     // 匹配Disruptor的bufferSize
        poolConfig.setMaxIdle(2000);
        poolConfig.setMinIdle(500);
        this.eventPool = new GenericObjectPool<>(this, poolConfig);
    }

    @Override
    public PersistEvent create() {
        return new PersistEvent();
    }

    @Override
    public PooledObject<PersistEvent> wrap(PersistEvent event) {
        return new DefaultPooledObject<>(event);
    }

    @Override
    public void passivateObject(PooledObject<PersistEvent> pooledObject) {
        // 重置Event的Signal引用
        pooledObject.getObject().setSignal(null);
    }

    // 对外API
    public PersistEvent borrowEvent() {
        try {
            return eventPool.borrowObject();
        } catch (Exception e) {
            return new PersistEvent();
        }
    }

    public void returnEvent(PersistEvent event) {
        if (event != null) {
            try {
                eventPool.returnObject(event);
            } catch (Exception e) {
                // 兜底忽略
            }
        }
    }
}