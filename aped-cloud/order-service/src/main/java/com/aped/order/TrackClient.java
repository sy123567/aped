package com.aped.order;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * order → track 同步调用：下单受理/订单取消写轨迹（原版单体内 helpers.event() 直接写 events 表；
 * events 按全量计划归 track-service 独占）。轨迹为旁路日志，调用方以 best-effort 方式写入，
 * 失败不阻断下单/取消主流程（计划矩阵「最终一致」口径）。
 */
@FeignClient(name = "track-service")
public interface TrackClient {

    @PostMapping("/internal/events")
    Map<String, Object> addEvent(@RequestBody Map<String, Object> body);
}
