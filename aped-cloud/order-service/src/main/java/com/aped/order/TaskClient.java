package com.aped.order;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * order → task 同步调用：订单取消时把该订单尚待接单的揽收任务回滚
 * （原版单体内同一事务 UPDATE pickup_tasks；pickup_tasks 归 task-service 独占）。
 */
@FeignClient(name = "task-service")
public interface TaskClient {

    @PostMapping("/internal/pickup-tasks/rollback-by-order")
    Map<String, Object> rollbackByOrder(@RequestBody Map<String, Object> body);
}
