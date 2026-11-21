package com.aped.dispatch;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * dispatch → order 同步调用：运力看板的待派单计数、揽收候选页按 id 取订单。
 * 订单不存在时下游返回 404，Feign 抛 FeignException，由调用方按原版口径转 404。
 */
@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/internal/orders/stats")
    Map<String, Object> stats();

    @GetMapping("/internal/orders/{id}")
    Map<String, Object> get(@PathVariable("id") long id);
}
