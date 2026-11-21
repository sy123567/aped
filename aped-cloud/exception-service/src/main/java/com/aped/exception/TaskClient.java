package com.aped.exception;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * exception → task 同步调用：定责时查运单是否已过航空安检
 * （原版 assessForException 查 transit_logs.security=1；transit_logs 归 task-service 独占）。
 */
@FeignClient(name = "task-service")
public interface TaskClient {

    @GetMapping("/internal/transit/security-passed")
    Map<String, Object> securityPassed(@RequestParam("waybill_no") String waybillNo);
}
