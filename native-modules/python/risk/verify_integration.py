import socket
import json
import time
import subprocess
import sys

def verify_response():
    # 启动服务端
    process = subprocess.Popen([sys.executable, "main.py"])
    time.sleep(2) # 等待启动

    try:
        # 1. 发送正常订单 -> Expect allow: True
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.connect(('localhost', 9002))
        order1 = {"clOrderId": "1", "shareholderId": "SH001", "securityId": "600000", "side": "BUY"}
        client.sendall(json.dumps(order1).encode('utf-8'))
        response1 = json.loads(client.recv(1024).decode('utf-8'))
        print(f"Test 1 (BUY): {response1}")
        assert response1["allow"] is True
        client.close()

        # 2. 发送对敲订单 -> Expect allow: False
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.connect(('localhost', 9002))
        order2 = {"clOrderId": "2", "shareholderId": "SH001", "securityId": "600000", "side": "SELL"}
        client.sendall(json.dumps(order2).encode('utf-8'))
        response2 = json.loads(client.recv(1024).decode('utf-8'))
        print(f"Test 2 (SELL Self-Trade): {response2}")
        assert response2["allow"] is False
        assert response2["reason"] == "同一股东号存在对敲交易"
        client.close()

        print("\nAll integration tests passed!")

    finally:
        process.terminate()
        process.wait()

if __name__ == "__main__":
    verify_response()
