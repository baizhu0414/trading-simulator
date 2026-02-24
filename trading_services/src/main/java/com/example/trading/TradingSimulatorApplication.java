package com.example.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@SpringBootApplication
@EnableScheduling // 开启定时任务支持（必须加，否则@Scheduled无效）
public class TradingSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingSimulatorApplication.class, args);
        System.out.println("=====================================");
        System.out.println("交易撮合系统启动成功！访问地址：http://localhost:8081/trading/api/trading");
        System.out.println("=====================================");
    }

}
