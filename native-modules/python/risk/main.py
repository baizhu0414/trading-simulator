import socket
import logging
import sys
import json
from risk_manager import RiskManager

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

# 初始化风控管理器
risk_manager = RiskManager()

HOST = '0.0.0.0'  # 监听所有接口
PORT = 9002       # 定义端口

def start_server():
    """启动 TCP 服务端"""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind((HOST, PORT))
            s.listen()
            logger.info(f"Risk Management Service listening on {HOST}:{PORT}")
            
            while True:
                conn, addr = s.accept()
                with conn:
                    logger.info(f"Connected by {addr}")
                    try:
                        while True:
                            data = conn.recv(1024)
                            if not data:
                                break
                            logger.info(f"Received data: {data}")
                            
                            try:
                                # 1. Parse message (Assuming JSON for now)
                                message = json.loads(data.decode('utf-8'))
                                
                                # 2. Risk Check
                                result = risk_manager.check(message)
                                
                                # 3. Construct Response based on protocol/ipc/risk_check_response.schema.json
                                if result["passed"]:
                                    # allow: true, reason: null or empty
                                    response_data = {"allow": True, "reason": ""}
                                else:
                                    # allow: false, reason: "同一股东号存在对敲交易" (Matching ErrorCodeEnum.SELF_TRADE)
                                    response_data = {"allow": False, "reason": "同一股东号存在对敲交易"}
                                
                                response = json.dumps(response_data).encode('utf-8')
                                conn.sendall(response)
                                
                            except json.JSONDecodeError:
                                logger.error("Invalid JSON format")
                                # Return a safe deny with reason
                                conn.sendall(b'{"allow": false, "reason": "Invalid JSON format"}')
                            except Exception as e:
                                logger.error(f"Error processing request: {e}")
                                error_msg = f"Internal Error: {str(e)}"
                                conn.sendall(json.dumps({"allow": False, "reason": error_msg}).encode('utf-8'))
                    except Exception as e:
                        logger.error(f"Error handling connection: {e}")
                    finally:
                        logger.info(f"Connection closed for {addr}")

    except OSError as e:
        logger.error(f"Failed to bind to {HOST}:{PORT}. Error: {e}")
        sys.exit(1)
    except KeyboardInterrupt:
        logger.info("Server stopping...")
        sys.exit(0)

if __name__ == "__main__":
    start_server()
