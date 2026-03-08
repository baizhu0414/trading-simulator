"""
Python 风控微服务
端口：9002（与项目 README 架构约定一致）
职责：接收 Java 交易服务的 IPC 请求，执行对敲风控检查

启动方式：
    cd native-modules/python/risk
    pip install -r requirements.txt
    python main.py

或使用 uvicorn：
    uvicorn main:app --host 0.0.0.0 --port 9002 --reload
"""

import logging
import uvicorn
from fastapi import FastAPI, HTTPException
from contextlib import asynccontextmanager

from config import SERVER_PORT
from models.schemas import RiskCheckRequest, RiskCheckResponse
from services.self_trade_checker import SelfTradeChecker

# ── 日志配置 ─────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)-5s] %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("risk-service")

# ── 全局实例 ─────────────────────────────────────────────────
self_trade_checker: SelfTradeChecker | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动时初始化风控检查器"""
    global self_trade_checker
    self_trade_checker = SelfTradeChecker()
    logger.info("=" * 55)
    logger.info(" Python 风控服务启动成功")
    logger.info(" 端口         : %d", SERVER_PORT)
    logger.info(" 风控检查     : POST /api/risk/check")
    logger.info(" 健康检查     : GET  /api/risk/health")
    logger.info(" API 文档     : http://localhost:%d/docs", SERVER_PORT)
    logger.info("=" * 55)
    yield
    logger.info("Python 风控服务已关闭")


app = FastAPI(
    title="Trading Risk Service",
    description="证券交易对敲风控微服务（Python）",
    version="0.1.0",
    lifespan=lifespan,
)


# ── 接口定义 ─────────────────────────────────────────────────


@app.post("/api/risk/check", response_model=RiskCheckResponse)
async def risk_check(request: RiskCheckRequest) -> RiskCheckResponse:
    """
    对敲风控检查

    请求/响应协议：
      - 请求：protocol/ipc/risk_check_request.schema.json
      - 响应：protocol/ipc/risk_check_response.schema.json
      - 示例：protocol/examples/ipc/risk_check_request.json
              protocol/examples/ipc/risk_check_response.json

    Java 主控调用示例：
      POST http://localhost:9002/api/risk/check
      Content-Type: application/json
    """
    try:
        return self_trade_checker.check(request)
    except Exception as e:
        logger.error("风控检查异常：%s", str(e), exc_info=True)
        raise HTTPException(status_code=500, detail=f"风控检查异常：{str(e)}")


@app.get("/api/risk/health")
async def health_check():
    """
    健康检查接口

    供 Java 主控服务探活使用。
    返回服务状态和风控模块启用情况。
    """
    return {
        "status": "UP",
        "service": "risk-service",
        "port": SERVER_PORT,
        "checks": {
            "selfTradeChecker": (
                "enabled"
                if self_trade_checker and self_trade_checker.enable
                else "disabled"
            )
        },
    }


# ── 启动入口 ─────────────────────────────────────────────────

if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=SERVER_PORT,
        reload=True,
        log_level="info",
    )
