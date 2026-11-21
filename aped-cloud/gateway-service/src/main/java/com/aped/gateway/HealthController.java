package com.aped.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对应原版 server.js 的 GET /api/health（公开，无需登录）：
 * 返回 {ok:true, data:{ts, services:[...]}}，services 为原版五大业务域名。
 * 与原版静态列表的区别：每个业务域按其背后微服务在 Eureka 的注册状态聚合，
 * 全部有 UP 实例才计入——形态与原版一致，内容为真实状态而非写死。
 * 五大域与微服务的对应即全量计划的拆分表（auth/gateway/eureka 为基础设施，不计入业务域，同原版）。
 */
@RestController
public class HealthController {

    private static final List<Map.Entry<String, List<String>>> DOMAINS = List.of(
            Map.entry("订单服务", List.of("order-service", "customer-service", "track-service")),
            Map.entry("计费服务", List.of("rate-service", "billing-service")),
            Map.entry("揽派调度", List.of("dispatch-service", "task-service")),
            Map.entry("异常理赔", List.of("exception-service", "claim-service")),
            Map.entry("绩效统计", List.of("report-service")));

    private final DiscoveryClient discovery;

    public HealthController(DiscoveryClient discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        List<String> up = new ArrayList<>();
        for (Map.Entry<String, List<String>> d : DOMAINS) {
            boolean allUp = d.getValue().stream()
                    .allMatch(s -> !discovery.getInstances(s).isEmpty());
            if (allUp) up.add(d.getKey());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ts", Instant.now().toString());
        data.put("services", up);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        return body;
    }
}
