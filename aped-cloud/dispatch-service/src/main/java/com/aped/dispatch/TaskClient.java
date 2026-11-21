package com.aped.dispatch;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * dispatch → task 同步调用：取快递员实时负载（v_courier_load 的数据源在 task-service）
 * 与今日已派数（运力看板用）。无 fallback：调用失败由调用方转 503 降级响应，不臆造负载。
 */
@FeignClient(name = "task-service")
public interface TaskClient {

    /** 全部 courier 的负载行：{id, code, name, grid_code, load, capacity}。 */
    @GetMapping("/internal/courier-loads")
    Map<String, Object> courierLoads();

    /** 任务侧统计：{today_assigned}。 */
    @GetMapping("/internal/task-stats")
    Map<String, Object> taskStats();
}
