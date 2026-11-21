#!/usr/bin/env bash
# start-all.sh — 一键启动 aped-cloud 全部 14 个 JVM（Eureka + 网关 + 12 个微服务）
# 用法：bash start-all.sh    停止：bash stop-all.sh
# 说明：本机坑（见 README）——JVM 必须带 IPv6 优先参数；堆上限 256m 是本机 14 个 JVM 同跑的实测值。
set -u
cd "$(dirname "$0")"
source ~/workspace/tools/env.sh
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true -Xmx256m"
mkdir -p logs

start() {
  local mod="$1"
  (setsid nohup java -jar "$mod/target/$mod-1.0.0.jar" > "logs/$mod.log" 2>&1 &)
  echo "started $mod"
}

# 1) 注册中心先行
start eureka-server
for i in $(seq 1 60); do
  sleep 3
  if curl -s -m 2 http://localhost:8761/actuator/health | grep -q UP; then
    echo "eureka UP after $((i * 3))s"
    break
  fi
  if [ "$i" = 60 ]; then echo "eureka 启动超时，看 logs/eureka-server.log"; exit 1; fi
done

# 2) 其余 13 个并行拉起
for m in auth-service order-service customer-service rate-service billing-service \
         dispatch-service task-service exception-service claim-service \
         track-service report-service notify-service gateway-service; do
  start "$m"
done

# 3) 等全部注册到 Eureka（12 服务 + 网关 = 13 个应用），并用 /api/health 预热网关负载均衡缓存
for i in $(seq 1 100); do
  sleep 3
  n=$(curl -s -m 3 http://localhost:8761/eureka/apps | grep -c "<name>")
  h=$(curl -s -m 3 http://localhost:8080/api/health | grep -o '"services"' | wc -l)
  if [ "$n" -ge 13 ] && [ "$h" -ge 1 ]; then
    echo "all registered ($n apps) after $((i * 3))s"
    break
  fi
  if [ "$i" = 100 ]; then echo "等待注册超时（当前 $n 个应用），看 logs/ 下各服务日志"; exit 1; fi
done

echo "OK: 网关入口 http://localhost:8080/api （健康检查 /api/health，登录 /api/auth/login）"
