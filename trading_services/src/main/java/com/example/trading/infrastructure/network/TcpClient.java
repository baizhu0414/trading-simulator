package com.example.trading.infrastructure.network;

import com.example.trading.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * TCP 短链接客户端，用于和 Python 风控 / C++ 撮合服务进行通信
 */
@Slf4j
@Component
public class TcpClient {

    /**
     * 发送 JSON 数据并获取响应
     *
     * @param host       服务器地址
     * @param port       端口
     * @param requestObj 请求对象
     * @return 响应字符串
     */
    public String sendRequest(String host, int port, Object requestObj) {
        String jsonPayload = JsonUtils.toJson(requestObj);
        try (Socket socket = new Socket(host, port);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            log.info("Try to send request to {}:{}. Payload: {}", host, port, jsonPayload);
            out.println(jsonPayload);

            String response = in.readLine();
            log.info("Received response from {}:{}. Payload: {}", host, port, response);
            return response;
        } catch (Exception e) {
            log.error("Failed to connect or communicate with {}:{}", host, port, e);
            throw new RuntimeException("TCP Communication Error", e);
        }
    }
}
