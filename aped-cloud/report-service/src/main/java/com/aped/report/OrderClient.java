package com.aped.report;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * report → order 只读：订单统计（工作台待处理订单数，orders 归 order-service 独占）。
 */
@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/internal/orders/stats")
    Map<String, Object> stats();
}
