package com.example.trading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
public class RedisTest {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testRedis() {
        // 写入数据
        stringRedisTemplate.opsForValue().set("test_key", "test_value");
        // 读取数据
        String value = stringRedisTemplate.opsForValue().get("test_key");
        System.out.println("Redis 读取值：" + value); // 输出 test_value
    }
}
