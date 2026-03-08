import random
import time
import multiprocessing
from multiprocessing import Pipe, Process
import socket
import threading

#该模块输入形式  市场标识,股票代码,买入申报价格

market_cache = {}
main_pipe = None
price_process = None

PRICE_BASE = 10.0
PRICE_PRECISION = 2
CACHE_EXPIRE = 5

def get_seed(security_id):
    return sum(ord(c) for c in str(security_id).strip())

def gen_ask_price(security_id):
    seed = get_seed(security_id) + int(time.time() % 1000)
    rng = random.Random(seed)
    return round(PRICE_BASE + rng.uniform(-1.5, 1.5), PRICE_PRECISION)

def price_provider(pipe):
    while True:
        if pipe.poll():
            req = pipe.recv()
            if req == "EXIT":
                break
            _, code = req
            pipe.send(gen_ask_price(code))
        else :
            # 修改：添加这行，解决死循环导致cpu占用太高问题
            time.sleep(0.01)
    pipe.close()

def get_ask_price(market, security_id):
    key = (market, security_id)
    now = time.time()
    if key in market_cache and now - market_cache[key]["time"] < CACHE_EXPIRE:
        return market_cache[key]["price"]
    main_pipe.send(key)
    ask_price = main_pipe.recv()
    market_cache[key] = {"price": ask_price, "time": now}
    return ask_price

def check_market_protection(market, security_id, buy_price):
    ask_price = get_ask_price(market, security_id)
    buy_price = round(buy_price, PRICE_PRECISION)
    return buy_price >= ask_price

def handle_client(client_socket, addr):
    data = client_socket.recv(1024).decode('utf-8').strip()
    if data:
        parts = data.split(",")
        # 仅处理3个字段的合法输入，其他情况直接返回False
        if len(parts) == 3:
            market = parts[0].strip()
            security_id = parts[1].strip()
            try:
                buy_price = float(parts[2].strip())
                result = check_market_protection(market, security_id, buy_price)
                client_socket.send(str(result).encode('utf-8'))
            except:
                # 价格转换失败直接返回False
                client_socket.send("False".encode('utf-8'))
        else:
            # 字段数不对直接返回False
            client_socket.send("False".encode('utf-8'))
    client_socket.close()

def start_tcp_server(host='0.0.0.0', port=9999):
    # 初始化行情服务
    global main_pipe, price_process
    main_pipe, p_pipe = Pipe()
    price_process = Process(target=price_provider, args=(p_pipe,))
    price_process.daemon = True
    price_process.start()
    
    # 创建TCP套接字
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # 修改错误：server_socket.setsockopt(socket.SOL_SOCKET, SO_REUSEADDR, 1) 改为server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)，解决跑不通问题
    server_socket.setsockopt(
        socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind((host, port))
    server_socket.listen(5)
    print(f"TCP服务已启动，监听端口：{host}:{port}")
    
    while True:
        client_socket, addr = server_socket.accept()
        client_thread = threading.Thread(target=handle_client, args=(client_socket, addr))
        client_thread.daemon = True
        client_thread.start()

if __name__ == "__main__":
    # 修改这里 9999 端口为 9003，匹配实际需要
    start_tcp_server(port=9003)