package com.aped.task;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * task → track 同步调用：揽收分配/回滚/揽收登记/中转/派件/签收/派送异常写轨迹
 * （原版单体内 helpers.event() 直接写 events 表；events 按全量计划归 track-service 独占）。
 * 计划矩阵口径：task → track 为「失败重试、最终一致」，调用方 best-effort 写入，
 * 轨迹写失败不阻断揽派主流程（与原版同事务写入的差异见 PROGRESS 批次 6）。
 */
@FeignClient(name = "track-service")
public interface TrackClient {

    @PostMapping("/internal/events")
    Map<String, Object> addEvent(@RequestBody Map<String, Object> body);
}
