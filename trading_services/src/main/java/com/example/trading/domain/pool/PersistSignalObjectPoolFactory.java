package com.example.trading.domain.pool;

import com.example.trading.domain.event.PersistSignal;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;

/**
 * PersistSignal对象池：解决Disruptor批量处理时Signal频繁创建的问题
 */
@Component
public class PersistSignalObjectPoolFactory extends BasePooledObjectFactory<PersistSignal> {

    private final GenericObjectPool<PersistSignal> signalPool;

    public PersistSignalObjectPoolFactory() {
        GenericObjectPoolConfig<PersistSignal> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(5000);     // 池最大对象数
        poolConfig.setMaxIdle(1000);      // 最大空闲
        poolConfig.setMinIdle(200);       // 最小空闲
        poolConfig.setMaxWaitMillis(50);  // 获取超时
        this.signalPool = new GenericObjectPool<>(this, poolConfig);
    }

    @Override
    public PersistSignal create() {
        return new PersistSignal();
    }

    @Override
    public PooledObject<PersistSignal> wrap(PersistSignal signal) {
        return new DefaultPooledObject<>(signal);
    }

    /**
     * 归还时重置Signal状态（核心：避免脏数据）
     */
    @Override
    public void passivateObject(PooledObject<PersistSignal> pooledObject) {
        PersistSignal signal = pooledObject.getObject();
        signal.setBizId("");
//        signal.setSignalType(null);
        signal.setMatchedOrder(null);
        signal.setCounterOrders(null);
        signal.setTrades(null);
        signal.setCanceledOrder(null);
        signal.setRecoveryResults(null);
        signal.setProcessingKey("");
    }

    @Override
    public boolean validateObject(PooledObject<PersistSignal> pooledObject) {
        PersistSignal signal = pooledObject.getObject();
        return signal != null && signal.getSignalType() != null; // 校验signalType
    }

    // 对外API
    public PersistSignal borrowSignal() {
        try {
            return signalPool.borrowObject();
        } catch (Exception e) {
            return new PersistSignal();
        }
    }

    public void returnSignal(PersistSignal signal) {
        if (signal != null) {
            try {
                signalPool.returnObject(signal);
            } catch (Exception e) {
                // 兜底忽略
            }
        }
    }
}