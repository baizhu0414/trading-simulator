package com.example.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TradingSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingSimulatorApplication.class, args);
        System.out.println("=====================================");
        System.out.println("交易撮合系统启动成功！访问地址：http://localhost:8081/trading/api/trading/order");
        System.out.println("=====================================");
    }

}
