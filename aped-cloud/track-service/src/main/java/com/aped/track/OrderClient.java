package com.aped.track;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * track → order 同步调用：轨迹查询拼装订单行（orders 归 order-service 独占），
 * 支持按 id（运单反查）与按订单号（直接以订单号查询轨迹）两种取法。
 * 订单不存在时下游 404。
 */
@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/internal/orders/{id}")
    Map<String, Object> get(@PathVariable("id") long id);

    @GetMapping("/internal/orders/by-no/{orderNo}")
    Map<String, Object> getByNo(@PathVariable("orderNo") String orderNo);
}
