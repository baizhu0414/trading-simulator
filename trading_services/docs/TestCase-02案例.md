## 第2类：基础校验与约束验证
### 需求点映射
| 测试子项 | 对应需求点 |
|------|------------|
| 2.1  | 基础校验-cl_order_id缺失 |
| 2.2  | 基础校验-cl_order_id长度不足16位 |
| 2.3  | 基础校验-shareholder_id为空 |
| 2.4  | 基础校验-market非法（非XSHG/XSHE/BJSE） |
| 2.5  | 基础校验-side非法（非B/S） |
| 2.6  | 基础校验-qty为负数 |
| 2.7  | 幂等校验-重复提交相同cl_order_id |

---

#### 案例2.1：基础校验-cl_order_id缺失
**测试目标**：验证请求缺少`cl_order_id`时，基础校验拦截并返回参数错误
**输入数据**：
```json
{
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示参数缺失
    ```
    {
        "code": "3000",
        "msg": "参数为空",
        "clOrderId": null,
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```
- 数据库`t_exchange_order`无插入记录

---

#### 案例2.2：基础校验-cl_order_id长度不足16位
**测试目标**：验证`cl_order_id`长度小于16位时，基础校验拦截并返回格式错误

**输入数据**：
```json
{
  "clOrderId": "BUY001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示参数格式错误
    ```
    {
        "code": "3002",
        "msg": "参数格式错误",
        "clOrderId": "BUY001",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```
- 数据库`t_exchange_order`无插入记录

---

#### 案例2.3：基础校验-shareholder_id为空
**测试目标**：验证`shareholder_id`为空时，基础校验拦截并返回参数错误

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": ""
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示参数缺失
    ```
    {
        "code": "3000",
        "msg": "参数为空",
        "clOrderId": "BUY0000000000001",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": ""
    }
    ```
- 数据库`t_exchange_order`无插入记录

---

#### 案例2.4：基础校验-market非法（非XSHG/XSHE/BJSE）
**测试目标**：验证`market`不为指定市场代码时，基础校验拦截并返回参数错误

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000001",
  "market": "NYSE",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示参数格式错误
    ```
    {
        "code": "3003",
        "msg": "交易市场非法",
        "clOrderId": "BUY0000000000001",
        "market": "NYSE",
        "securityId": "600030",
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```
- 数据库`t_exchange_order`无插入记录

---

#### 案例2.5：基础校验-side非法（非B/S）
**测试目标**：验证`side`不为"B"或"S"时，基础校验拦截并返回参数错误

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "X",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示参数格式错误
    ```
    // 由于请求的side是Enum的，所以初始化后参数会丢失。
    {
        "code": "3000",
        "msg": "参数为空",
        "clOrderId": "BUY0000000000001",
        "market": "XSHG",
        "securityId": "600030",
        "side": "",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```
- 数据库`t_exchange_order`无插入记录

---

#### 案例2.6：基础校验-qty为负数
**测试目标**：验证委托数量`qty`为负数时，基础校验拦截并返回参数错误

**输入数据**：
```json
{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": -100,
  "shareholderId": "SH00000001"
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示参数格式错误
    ```
    {
        "code": "3005",
        "msg": "订单数量非法",
        "clOrderId": "BUY0000000000001",
        "market": "XSHG",
        "securityId": "600030",
        "side": "B",
        "qty": -100,
        "price": 20,
        "shareholderId": "SH00000001"
    }
    ```
- 数据库`t_exchange_order`无插入记录

---

#### 案例2.7：幂等校验-重复提交相同cl_order_id
**测试目标**：验证重复提交相同`cl_order_id`时，幂等校验拦截并返回订单已存在错误

**前置条件**：数据库中已存在`cl_order_id = "BUY0000000000001"`的订单

**输入数据**：
```
{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600030",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000001"
}

{
  "clOrderId": "BUY0000000000001",
  "market": "XSHG",
  "securityId": "600031",
  "side": "B",
  "price": 20.00,
  "qty": 100,
  "shareholderId": "SH00000003"
}
```
**预期输出**：
- 接口返回`RejectResponse`，提示订单已存在
    ```json
    {
        "code": "3012",
        "msg": "订单号已存在",
        "clOrderId": "BUY0000000000001",
        "market": "XSHG",
        "securityId": "600031",
        "side": "B",
        "qty": 100,
        "price": 20,
        "shareholderId": "SH00000003"
    }
    ```
- 数据库`t_exchange_order`中仅保留1条`cl_order_id = "BUY0000000000001"`的记录，无新增或更新