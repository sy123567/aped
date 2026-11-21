package com.aped.claim;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * claim → exception 同步调用：读异常单、取定责核定结果、回写异常单状态/折损档、
 * 写理赔轨迹事件（exceptions 与 events 归 exception-service 独占；
 * 原版 claim.js 在单体内直接读写这两张表）。
 */
@FeignClient(name = "exception-service")
public interface ExceptionClient {

    @GetMapping("/internal/exceptions/{id}")
    Map<String, Object> get(@PathVariable("id") long id);

    @GetMapping("/internal/exceptions/{id}/assess")
    Map<String, Object> assess(@PathVariable("id") long id,
                               @RequestParam(name = "severity", required = false) String severity,
                               @RequestParam(name = "photos", required = false) String photos);

    @PostMapping("/internal/exceptions/{id}/status")
    Map<String, Object> updateStatus(@PathVariable("id") long id, @RequestBody Map<String, Object> body);

    @PostMapping("/internal/events")
    Map<String, Object> postEvent(@RequestBody Map<String, Object> body);
}
