# aped-cloud — AEPDS 微服务版（Spring Boot + Spring Cloud）

AEPDS《航空快递揽收与派送管理系统》从 Node/Express 模块化单体**全量迁移**到 Spring Boot + Spring Cloud 的 Java 工程，与原项目（`~/workspace/aped_midterm/src/`）并列存放，原版不改动、作对照与回退。

## 技术版本

- JDK 21.0.13（`~/workspace/tools/`，运行前 `source ~/workspace/tools/env.sh`）
- Maven 3.9.9
- Spring Boot 3.5.7 / Spring Cloud 2025.0.0

## 模块（14 个）

| 模块 | 端口 | 说明 |
|---|---|---|
| eureka-server | 8761 | 注册中心 |
| gateway-service | 8080 | Spring Cloud Gateway 统一入口 |
| auth-service | 8100 | 登录鉴权 / JWT |
| order-service | 8101 | 订单 |
| customer-service | 8102 | 客户 |
| rate-service | 8103 | 计费/运价 |
| billing-service | 8104 | 账单 |
| dispatch-service | 8105 | 派单引擎 |
| task-service | 8106 | 揽派任务/中转 |
| exception-service | 8107 | 异常定责 |
| claim-service | 8108 | 理赔 |
| track-service | 8109 | 轨迹 |
| report-service | 8110 | 绩效/报表 |
| notify-service | 8111 | 通知 |

## 状态

**全量迁移已完成（批次 0–7 全部验收通过，2026-11-19）。** 总验收：原 `e2e.js` 44 条断言改指向网关、在 Node 完全停机时一次全过（`e2e-final-run.log`）；原前端 14 页指向网关逐页联调通过。分批记录见 `PROGRESS.md`，最终已知口径清单在 PROGRESS 批次 7 节。

## 构建与运行

```bash
source ~/workspace/tools/env.sh
# 1) Maven 依赖中转（构建前必须先跑，见下方“本机坑”）
setsid nohup python3 tools/maven-relay.py > /tmp/maven-relay.log 2>&1 &
# 2) 编译/打包
mvn -B package -DskipTests
# 3) 一键启动全部 14 个 JVM（Eureka 先行、等注册齐、预热；日志在 logs/）
bash start-all.sh
# 停止全部并释放端口
bash stop-all.sh
```

入口与约定：

- 正式入口：`http://localhost:8080/api/**`（与原版 server.js 的 `/api` 挂载一一对应，如登录 `POST /api/auth/login`、健康检查 `GET /api/health`）；前端沿用 `/api` 前缀零改动即可接入（dev 时把 vite proxy 的 target 指向 8080）。
- 调试入口保留：`GET http://localhost:8080/gw/<服务名>/ping`。
- 账号与原版一致（admin/dispatch/courier/claims/finance/ops + 客户 zhangsan/lisi/wangwu，初始密码 123456）。
- 冷启动慢：14 个 JVM 并发启动单个最长约 150–250 秒，start-all.sh 已含注册等待；启动后先看 `/api/health` 返回 5 个业务域再使用。

## 本机坑（实测，勿删）

1. **Maven 拉不动 Central**：Java wagon 经本机出口代理做 HTTPS CONNECT 会被重置。解法：`tools/maven-relay.py` 在 127.0.0.1:18099 做本地中转（curl 拉取 + 缓存到 `~/workspace/m2cache`），`~/.m2/settings.xml` 已配 mirror `localcache` 指向它。构建前先确认它在跑。
2. **Java 走 IPv4 回环会卡死**：本沙箱 IPv4 loopback 连接能建立但数据被丢弃，Eureka 客户端会卡在 “Getting all instance registry info”。解法：所有 JVM 带 `-Djava.net.preferIPv6Addresses=true`，且各服务 `application.yml` 里 Eureka 地址用 `http://localhost:8761/eureka/`（不要写 127.0.0.1），实例 hostname 用 localhost。
3. 服务启动慢（本机 CPU 弱，单个约 30–45 秒），验收脚本要留足等待。

## 进度

见 `PROGRESS.md`（分批验收记录）。总计划：`~/workspace/your_files/aped_SpringCloud全量迁移_实施计划.md`。
