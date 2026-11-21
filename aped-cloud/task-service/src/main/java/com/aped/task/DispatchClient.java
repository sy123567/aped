package com.aped.task;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * task → dispatch 同步调用（计划矩阵：揽收/派送任务要定人）。
 * 定人决策全在 dispatch-service 的分配引擎里，本服务不自算人选；
 * 航班校验查 dispatch 独占的 flights 主数据。无 fallback：失败由调用方转 503 降级，不臆造人选。
 */
@FeignClient(name = "dispatch-service")
public interface DispatchClient {

    /** 定人：返回候选第一名（含 reason），无可用运力时 data=null。 */
    @GetMapping("/internal/assign/pick")
    Map<String, Object> pick(@RequestParam("addr") String addr);

    /** 候选排名（派件候选页用）：字段与原版 candidatesForAddr 行一致。 */
    @GetMapping("/internal/assign/candidates")
    Map<String, Object> candidates(@RequestParam("addr") String addr,
                                   @RequestParam(value = "limit", required = false) Integer limit);

    /** 航班主数据单查：不存在时下游 404，Feign 抛 FeignException。 */
    @GetMapping("/internal/flights/{flightNo}")
    Map<String, Object> flight(@PathVariable("flightNo") String flightNo);
}
