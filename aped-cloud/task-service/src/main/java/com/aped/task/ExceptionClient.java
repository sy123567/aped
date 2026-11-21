package com.aped.task;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * task → exception 同步调用：派送异常转异常件时登记异常记录
 * （原版单体内 dispatch.js 直接 INSERT INTO exceptions；exceptions 归 exception-service 独占）。
 */
@FeignClient(name = "exception-service")
public interface ExceptionClient {

    @PostMapping("/internal/exceptions")
    Map<String, Object> create(@RequestBody Map<String, Object> body);
}
