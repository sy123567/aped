#!/usr/bin/env bash
# stop-all.sh — 停掉 aped-cloud 全部 JVM 并释放端口（8761 / 8080 / 8100–8111）
pkill -f "aped-cloud.*-1\.0\.0\.jar" 2>/dev/null
sleep 2
for p in 8761 8080 8100 8101 8102 8103 8104 8105 8106 8107 8108 8109 8110 8111; do
  fuser -k "$p/tcp" 2>/dev/null
done
sleep 1
left=$(ss -ltn | grep -cE ":(8761|8080|810[0-9]|811[01]) " || true)
echo "stopped; ports still listening: $left"
