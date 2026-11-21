package com.aped.report;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * report → task 只读聚合（计划矩阵）：绩效原始行、中转登记、工作台今日计数。
 * 任务/员工/中转数据归 task-service 独占，本服务只消费其内部聚合接口。
 */
@FeignClient(name = "task-service")
public interface TaskClient {

    @GetMapping("/internal/kpi-rows")
    Map<String, Object> kpiRows();

    @GetMapping("/internal/transit-logs")
    Map<String, Object> transitLogs();

    @GetMapping("/internal/board-stats")
    Map<String, Object> boardStats();
}
