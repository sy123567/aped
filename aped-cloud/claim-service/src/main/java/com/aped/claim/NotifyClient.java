package com.aped.claim;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * claim → notify 同步调用：理赔赔付完成后写通知记录（计划矩阵：各服务 → notify，
 * 失败不阻断主流程，容错在调用方 best-effort 处理）。notify-service 为新增横切模块，
 * 原 Node 版无对应功能。
 */
@FeignClient(name = "notify-service")
public interface NotifyClient {

    @PostMapping("/internal/notifications")
    Map<String, Object> create(@RequestBody Map<String, Object> body);
}
