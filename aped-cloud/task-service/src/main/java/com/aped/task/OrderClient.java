package com.aped.task;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * task → order 同步调用：取订单整行（派单/揽收/任务列表拼装）与回写订单状态
 * （待派单 → 待揽收 → 已揽收、回滚回 待派单）。原版单体内为同一库直接 SQL，
 * 微服务版 orders 归 order-service 独占，故走其内部接口。订单不存在时下游 404。
 */
@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/internal/orders/{id}")
    Map<String, Object> get(@PathVariable("id") long id);

    @PostMapping("/internal/orders/{id}/status")
    Map<String, Object> setStatus(@PathVariable("id") long id, @RequestBody Map<String, Object> body);
}
