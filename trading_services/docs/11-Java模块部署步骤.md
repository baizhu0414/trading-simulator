将步骤分为 **「需要管理员权限的环境准备」** 和 **「你自己可以操作的应用部署」** 两部分。

请确保你的云服务器安全组（防火墙）已开放以下端口：`22` (SSH), `8081` (应用), `3000` (Grafana), `9090` (Prometheus), `3306` (MySQL), `6379` (Redis)。

---

### 第一部分：环境准备（需要管理员权限，建议让管理员协助）

这部分主要是安装数据库、监控组件等基础环境。

#### 1. 安装 MySQL 8.4
我们使用通用的 Linux 二进制包安装。

```bash
# 1. 下载并解压
cd /usr/local
wget https://dev.mysql.com/get/Downloads/MySQL-8.4/mysql-8.4.0-linux-glibc2.28-x86_64.tar.xz
tar -xvf mysql-8.4.0-linux-glibc2.28-x86_64.tar.xz
ln -s mysql-8.4.0-linux-glibc2.28-x86_64 mysql # 软连接

# 2. 进入 MySQL 目录（请根据你实际存放的位置调整，假设在你家目录下）
cd /home/group10/software/mysql-8.4.0-linux-glibc2.28-x86_64
mkdir -p /home/group10/data/mysql
# 执行初始化
./bin/mysqld --initialize --user=group10 --datadir=/home/group10/data/mysql
输出：
A temporary password is generated for root@localhost: k,8)r/G/7Fuu

# 3. 编写配置文件
vi /home/group10/my.cnf
填入下面内容：
```
```
[mysqld]
# 这里改成你解压 MySQL 的实际路径
basedir=/home/group10/software/mysql-8.4.0-linux-glibc2.28-x86_64
# 这里改成你想存放数据的路径
datadir=/home/group10/data/mysql
socket=/tmp/mysql.sock
port=3306
# 这里改成你的 Linux 用户名（group10），不需要新建 mysql 用户
user=group10
bind-address=127.0.0.1
character-set-server=utf8mb4

[client]
socket=/tmp/mysql.sock
default-character-set=utf8mb4

```
```
# 4. 启动
cd /home/group10/software/mysql-8.4.0-linux-glibc2.28-x86_64
# 后台启动，并指定配置文件（路径配置了：'/home/group10/data/mysql/VM-0-3-rockylinux.err'）
./bin/mysqld_safe --defaults-file=/home/group10/my.cnf > /dev/null 2>&1 &

---
# 5. 登录（使用第 1 步保存的临时密码）
./bin/mysql -uroot -p

-- 1. 先修改 root 密码（把临时密码改掉）
ALTER USER 'root'@'localhost' IDENTIFIED BY 'Root@123456';
FLUSH PRIVILEGES;

-- 2. 远程连接，创建用户
CREATE USER IF NOT EXISTS 'test'@'%' IDENTIFIED BY 'test';
GRANT ALL PRIVILEGES ON trading_db.* TO 'test'@'%';
FLUSH PRIVILEGES;
EXIT;

# 6. 修改配置允许远程连接，并重启mysql
 pgrep mysqld
 pkill -f mysqld
 ./bin/mysqld_safe --defaults-file=/home/group10/my.cnf > /dev/null 2>&1 &

# 7. Navicat本地远程连接mysql
- 设置ssh
- 设置本地数据库账号密码
- 创建数据库和表
```

#### 2. 安装 Redis
利用服务器已有的 GCC 编译安装。

```bash
cd /usr/local
sudo wget https://download.redis.io/releases/redis-7.0.14.tar.gz
sudo tar -xvf redis-7.0.14.tar.gz
cd redis-7.0.14
# 1. 编译（不需要 sudo）
make
# 2. 安装到自己的家目录下（比如 /home/group10/redis）
# 这里 PREFIX 改为你有权限的路径
make install PREFIX=/home/group10/redis

# 进入你安装好的 redis 的 bin 目录
cd /home/group10/redis/bin
# 后台启动 Redis
./redis-server --daemonize yes
```


#### 3. 安装 Prometheus & Grafana & Exporter

##### 0. 准备工作（创建目录）
```bash
# 先回到家目录
cd /home/group10
```

---

##### 1. 安装 Prometheus
```bash
# 下载
wget https://github.com/prometheus/prometheus/releases/download/v3.9.1/prometheus-3.9.1.linux-amd64.tar.gz

# 解压
tar -xvf prometheus-3.9.1.linux-amd64.tar.gz

# 创建软链接（方便以后升级）
ln -s prometheus-3.9.1.linux-amd64 prometheus
```

**配置 Prometheus：**
```bash
cd /home/group10/prometheus
vi prometheus.yml
```
**替换内容**（和之前一样，无需修改）：
```yaml
global:
  scrape_interval: 5s  # 5秒抓取一次指标
  evaluation_interval: 5s

scrape_configs:
  - job_name: 'trading-simulator'
    metrics_path: '/trading/actuator/prometheus'  # 应用监控端点（含上下文路径）
    static_configs:
      - targets: ['localhost:8081']  # 应用地址+端口

  - job_name: "mysql"
    static_configs:
      - targets: ["localhost:9104"]
```
`:wq` 保存。

**启动 Prometheus（支持远程访问）：**
Prometheus 默认监听所有网卡（0.0.0.0），直接启动即可：
```bash
-- 启动Prometheus服务：
    cd /home/group10/software/prometheus
    nohup ./prometheus --config.file=prometheus.yml > prometheus.log 2>&1 &


-- 同时映射 Prometheus + Grafana到本地ssh连接，暂时替代服务器端没开放的端口访问：
    ssh -L 9090:127.0.0.1:9090 -L 3000:127.0.0.1:3000 group10@129.211.187.179
或者PuTTY 配置 SSH 隧道，一次配置4个
    - 8081服务端口、9104数据库监测、9090检测底座、3000图形化界面
-- 访问：
    http://localhost:9090

-- 开启远程端口后直接访问：
    *   **远程访问地址**：`http://129.211.187.179:9090`
    *   **记得去云服务器控制台开放安全组端口：`9090`**
```
---

##### 2. 安装 mysqld_exporter
```bash
cd /home/group10

# 下载
wget https://github.com/prometheus/mysqld_exporter/releases/download/v0.18.0/mysqld_exporter-0.18.0.linux-amd64.tar.gz

# 解压
tar -xvf mysqld_exporter-0.18.0.linux-amd64.tar.gz

# 软链接
ln -s mysqld_exporter-0.18.0.linux-amd64 mysqld_exporter
```

**配置数据库连接文件：**
```bash
cd /home/group10/mysqld_exporter
vi .my.cnf
```
**写入内容**（注意密码要和你数据库里的 exporter 密码一致）：
```ini
[client]
user=test
password=test
host=127.0.0.1
port=3306
```
`:wq` 保存。

**启动 mysqld_exporter：**
```bash
cd /home/group10/software/mysqld_exporter
nohup ./mysqld_exporter --config.my-cnf=.my.cnf > exporter.log 2>&1 &
```
*   Exporter 默认端口 `9104`，通常不需要远程访问，Prometheus 能访问到就行。

---

##### 3. 安装 Grafana
```bash
cd /home/group10

# 下载
wget https://dl.grafana.com/oss/release/grafana-10.2.0.linux-amd64.tar.gz

# 解压
tar -xvf grafana-10.2.0.linux-amd64.tar.gz

# 软链接
ln -s grafana-10.2.0 grafana
```

**启动 Grafana（支持远程访问）：**
Grafana 默认监听所有网卡（0.0.0.0），直接启动即可：
```bash
cd /home/group10/software/grafana/bin
nohup ./grafana-server > grafana.log 2>&1 &
```
*   **远程访问地址**：`http://129.211.187.179:3000`
*   **默认账号密码**：`admin` / `admin`
*   **记得去云服务器控制台开放安全组端口：`3000`**

---

### 第二部分：应用部署

#### 1. 本地 IDEA 打包 Jar 包（步骤不变）
1.  打开 IDEA 右侧 **Maven** 面板；
2.  展开 `Lifecycle`，先双击 `clean` 清理旧包；
3.  双击 `package` 打包（可跳过测试：点击 `package` 旁的小三角 → 选择 `Edit Configurations` → 增加参数 `-Dmaven.test.skip=true`）；
4.  打包成功后，在项目 `target` 文件夹找到 Jar 包（如 `trading-simulator-0.0.1-SNAPSHOT.jar`）。

#### 2. 上传 Jar 包到服务器
推荐用 **WinSCP/FileZilla**（图形化拖拽，新手友好）：
- 主机：`129.211.187.179`
- 端口：`22`
- 用户名：`group10`
- 密码：`groupxxx`
- 上传路径：直接拖拽到服务器的 `/home/group10/trading_services/` 目录下（该目录已存在，无需新建）。

#### 3. 一键启动所有服务（适配路径的脚本）
回到 SSH 终端，创建适配你路径的启动脚本：

##### 步骤 1：创建并编辑启动脚本
```bash
# 进入 Jar 包所在目录
cd /home/group10/trading_services

# 创建启动脚本（无需 sudo）
vi start_all.sh
```

##### 步骤 2：粘贴以下脚本内容（已适配所有路径）
```bash
#!/bin/bash

# 脚本说明：适配 group10 路径的一键启动脚本，包含所有依赖服务+应用
echo "=== 开始启动所有服务 ==="

echo "--- 1. 启动 Redis ---"
/home/group10/redis/bin/redis-server --daemonize yes
sleep 1  # 短暂等待，避免服务启动冲突

echo "--- 2. 启动 MySQL（后台运行，无日志刷屏） ---"
/home/group10/software/mysql/bin/mysqld_safe --defaults-file=/home/group10/software/mysql/my.cnf > /dev/null 2>&1 &
sleep 3  # MySQL 启动稍慢，多等几秒

echo "--- 3. 启动 Prometheus ---"
cd /home/group10/software/prometheus
nohup ./prometheus --config.file=prometheus.yml > prometheus.log 2>&1 &
sleep 1

echo "--- 4. 启动 MySQL Exporter ---"
cd /home/group10/software/mysqld_exporter
nohup ./mysqld_exporter --config.my-cnf=.my.cnf > exporter.log 2>&1 &
sleep 1

echo "--- 5. 启动 Grafana ---"
cd /home/group10/software/grafana/bin
nohup ./grafana-server > grafana.log 2>&1 &
sleep 1

echo "--- 6. 启动 Spring Boot 应用 ---"
cd /home/group10/trading_services
nohup java -jar trading-simulator-0.0.1-SNAPSHOT.jar > startup-error.log 2>&1 &

echo "=== 所有服务启动完成！==="
echo "日志查看路径："
echo "- 应用日志：/home/group10/trading_services/app.log"
echo "- Prometheus 日志：/home/group10/software/prometheus/prometheus.log"
echo "- Grafana 日志：/home/group10/software/grafana/bin/grafana.log"
```

##### 步骤 3：保存并赋予执行权限
- 按 `Esc` → 输入 `:wq` 保存退出；
- 给脚本加执行权限：
  ```bash
  chmod +x start_all.sh
  ```

##### 步骤 4：执行启动脚本
```bash
# 在 /home/group10/trading_services 目录下执行
./start_all.sh
```

#### 4. 验证部署（适配路径+SSH隧道访问）
##### 方式 1：查看应用日志（确认启动状态）
```bash
# 进入 Jar 包目录
cd /home/group10/trading_services

# 实时查看应用日志
tail -f app.log
```
- 看到 `Started TradingSimulatorApplication in XXX seconds` 说明应用启动成功；
- 按 `Ctrl + C` 退出日志查看。

##### 方式 2：健康检查（结合 SSH 隧道访问）
1.  确保 PuTTY 已建立隧道（映射 8081 端口）；
2.  本地浏览器访问：`http://localhost:8081/trading/actuator/health`；
3.  显示 `{"status":"UP"}` 代表应用+依赖（MySQL/Redis）都正常。

##### 方式 3：验证监控服务（均通过 SSH 隧道）
| 服务         | 本地访问地址                  | 验证标准                     |
|--------------|-------------------------------|------------------------------|
| Prometheus   | http://localhost:9090         | 能打开 Prometheus 界面       |
| MySQL Exporter | http://localhost:9104/metrics | 能看到 MySQL 指标文本        |
| Grafana      | http://localhost:3000         | 能登录 Grafana（默认 admin/admin） |

---

#### 补充：停止所有服务的脚本（可选，方便重启）
如果需要停止所有服务，可创建 `stop_all.sh` 脚本：
```bash
# 进入 /home/group10/trading_services 目录
cd /home/group10/trading_services
vi stop_all.sh
```
粘贴以下内容：
```bash
#!/bin/bash

echo "=== 开始停止所有服务 ==="

# 停止 Spring Boot 应用
pkill -f trading-simulator-0.0.1-SNAPSHOT.jar
echo "--- 已停止 Spring Boot 应用 ---"

# 停止 Grafana
pkill -f grafana
echo "--- 已停止 Grafana ---"

# 停止 MySQL Exporter
pkill -f mysqld_exporter
echo "--- 已停止 MySQL Exporter ---"

# 停止 Prometheus
pkill -f prometheus
echo "--- 已停止 Prometheus ---"

# 停止 MySQL
pkill -u group10 -f mysqld
echo "--- 已停止 MySQL ---"

# 停止 Redis
pkill -f redis-server
echo "--- 已停止 Redis ---"

echo "=== 所有服务已停止！==="
```
保存后加执行权限：
```bash
chmod +x stop_all.sh
```
执行停止：
```bash
./stop_all.sh
```

---


#### 4. 验证部署

**1. 看应用日志：**
```bash
cd /usr/local/app
tail -f app.log
```
看到 `Started TradingSimulatorApplication...` 说明启动成功。按 `Ctrl + C` 退出日志查看。

**2. 访问健康检查：**
在浏览器打开：`http://129.211.187.179:8081/trading/actuator/health`
如果显示 `{"status":"UP"}`，恭喜你，部署成功了！

**3. 配置 Grafana 看板：**
1.  访问：`http://129.211.187.179:3000` (账号/密码: `admin` / `admin`)。
2.  首次登录修改密码后，点击左侧 `Connections` -> `Data Sources` -> `Add data source`。
3.  选择 `Prometheus`，URL 填 `http://localhost:9090`，点击 `Save & Test`。
4.  点击左侧 `Dashboards` -> `Import`，输入 `12856` (JVM监控)，选择数据源，Import。
5.  再次 Import，输入 `7362` (MySQL监控)，Import。

---
