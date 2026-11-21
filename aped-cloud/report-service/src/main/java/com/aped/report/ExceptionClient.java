package com.aped.report;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * report → exception 只读聚合：异常单全量（exceptions 归 exception-service 独占），
 * 供运营汇总的异常计数/分类与异常数据集（理赔 JOIN 在本服务内完成）。
 */
@FeignClient(name = "exception-service")
public interface ExceptionClient {

    @GetMapping("/internal/exceptions")
    Map<String, Object> listAll();
}
