package com.example.trading.application;

import com.example.trading.mapper.OrderMapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 订单幂等服务：基于布隆过滤器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderIdempotentService {

    private final OrderMapper orderMapper;

    /**
     * 预计订单量：根据实际业务调整，例如 1000万
     */
    private static final int EXPECTED_INSERTIONS = 10_000_000;

    /**
     * 期望误判率：0.01% (万分之一)，越低则内存占用越大
     */
    private static final double FPP = 0.0001;

    private volatile BloomFilter<String> orderIdBloomFilter;

    @PostConstruct
    public void init() {
        log.info("【幂等服务】正在初始化布隆过滤器...");
        // 初始化一个空的过滤器
        this.orderIdBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS,
                FPP
        );
        log.info("【幂等服务】布隆过滤器初始化完成，预计容量: {}, 误判率: {}", EXPECTED_INSERTIONS, FPP);
    }

    /**
     * 【对外接口】判断订单是否可能存在
     * true: 可能存在 (需要去 DB/Redis 二次确认)
     * false: 绝对不存在 (直接放行)
     */
    public boolean mightExist(String clOrderId) {
        if (clOrderId == null) return false;
        return orderIdBloomFilter.mightContain(clOrderId);
    }

    /**
     * 【对外接口】将订单 ID 加入过滤器
     */
    public void add(String clOrderId) {
        if (clOrderId != null) {
            orderIdBloomFilter.put(clOrderId);
        }
    }

    /**
     * 【初始化接口】批量加载历史数据
     * 由 OrderRecoveryService 调用
     */
    public void bulkLoad(List<String> clOrderIds) {
        if (clOrderIds == null || clOrderIds.isEmpty()) {
            return;
        }
        log.info("【幂等服务】开始批量加载 {} 个历史订单ID...", clOrderIds.size());
        for (String id : clOrderIds) {
            orderIdBloomFilter.put(id);
        }
        log.info("【幂等服务】批量加载完成");
    }
}