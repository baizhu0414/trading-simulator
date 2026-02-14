import socket
import logging
import sys

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

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
                            
                            # TODO: Implement strict protocol parsing based on protocol/ipc schemas
                            # Expected Flow:
                            # 1. Parse JSON/Protobuf message
                            # 2. Validate against risk_check_request.schema.json
                            # 3. Perform risk check logic
                            # 4. Return response matching risk_check_response.schema.json
                            
                            # Current implementation: Echo for connectivity verification
                            response = b"ACK: " + data
                            conn.sendall(response)
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
