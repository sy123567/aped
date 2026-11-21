package com.aped.exception;

import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * exception → track 同步调用：异常/理赔流程事件在写入本服务本地日志的同时转发 track-service
 * （events 按全量计划归 track 独占，批次 6 收口）；异常详情的全量轨迹与定责的安检文本兜底
 * 也从 track 读。转发为 best-effort（失败不影响异常/理赔主流程，本地日志仍可兜底）。
 */
@FeignClient(name = "track-service")
public interface TrackClient {

    @PostMapping("/internal/events")
    Map<String, Object> addEvent(@RequestBody Map<String, Object> body);

    @GetMapping("/internal/events")
    Map<String, Object> listEvents(@RequestParam("waybill_no") String waybillNo);
}
