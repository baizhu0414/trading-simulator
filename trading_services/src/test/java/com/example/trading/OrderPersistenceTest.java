package com.example.trading;

import com.example.trading.application.ExchangeService;
import com.example.trading.common.enums.OrderStatusEnum;
import com.example.trading.common.enums.SideEnum;
import com.example.trading.domain.model.Order;
import com.example.trading.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@SpringBootTest
class OrderPersistenceTest {
    @Autowired
    private ExchangeService orderService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testSingleOrderPersistence() throws InterruptedException {
        // 1. 构造测试订单（使用唯一ID，避免幂等拦截）
        String testClOrderId = "TEST_" + UUID.randomUUID().toString().substring(0, 11); // 唯一ID
        Order testOrder = Order.builder()
                .clOrderId(testClOrderId)
                .shareholderId("USER000001") // 测试专用股东ID，避免风控拦截
                .market("XSHG")
                .securityId("600000")
                .side(SideEnum.BUY)
                .originalQty(100)
                .qty(100)
                .price(new BigDecimal("10.00"))
                .status(OrderStatusEnum.NOT_FILLED)
                .createTime(LocalDateTime.now())
                .version(0)
                .build();

        log.info("开始处理测试订单，ID：{}", testClOrderId);
        // 2. 发送订单
        orderService.processOrder(JsonUtils.toJson(testOrder));

        // 3. 延长等待时间，确保异步任务执行完成
        log.info("等待10秒，让Disruptor处理持久化任务...");
        Thread.sleep(10000);

        // 4. 校验记录数
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_exchange_order WHERE cl_order_id = ?",
                new Object[]{testClOrderId},
                Integer.class
        );
        log.info("订单[{}]记录数: {}", testClOrderId, count);

        // 5. 若记录数为0，直接返回，避免空指针
        if (count == 0) {
            log.error("订单[{}]未入库，测试失败", testClOrderId);
            org.junit.jupiter.api.Assertions.fail("订单未入库，记录数为0");
        }

        // 6. 校验字段
        Order dbOrder = jdbcTemplate.queryForObject(
                "SELECT cl_order_id, status, qty, version FROM t_exchange_order WHERE cl_order_id = ?",
                new Object[]{testClOrderId},
                (rs, rowNum) -> Order.builder()
                        .clOrderId(rs.getString("cl_order_id"))
                        .status(OrderStatusEnum.values()[rs.getInt("status")])
                        .qty(rs.getInt("qty"))
                        .version(rs.getInt("version"))
                        .build()
        );
        log.info("数据库中的订单: {}", dbOrder);
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatusEnum.NOT_FILLED, dbOrder.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(100, dbOrder.getQty());
        org.junit.jupiter.api.Assertions.assertEquals(0, dbOrder.getVersion());

        log.info("订单[{}]持久化测试成功", testClOrderId);
    }
}