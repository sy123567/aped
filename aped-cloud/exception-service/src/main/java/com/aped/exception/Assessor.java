package com.aped.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 对应 claim-engine.js 的 assessForException：把异常单 + 运单信息喂进定责引擎。
 * 运单（保价额/运费）经 billing 内部接口取；安检判定先查 task 的 transit_logs（security=1），
 * 再用轨迹事件里含「安检」的描述兜底（原版双口径；批次 6 起 events 已收口 track-service，
 * 兜底先查 track 的全量轨迹，再查本服务本地日志）。
 * 试算为只读：下游不可用时按「无运单/未过安检」降级计算，不阻断（原版同库读取无此情形）。
 */
@Component
public class Assessor {

    private final ExceptionStore store;
    private final WaybillGateway waybills;
    private final TaskClient taskClient;
    private final EventPublisher events;

    public Assessor(ExceptionStore store, WaybillGateway waybills, TaskClient taskClient,
                    EventPublisher events) {
        this.store = store;
        this.waybills = waybills;
        this.taskClient = taskClient;
        this.events = events;
    }

    /** 异常单不存在返回 null（调用方转 404）。severity/photos 为 null 时走原版默认链。 */
    public Map<String, Object> assessForException(long exceptionId, String severity, String photos) {
        ExceptionStore.ExceptionRow ex;
        synchronized (store.lock) {
            ex = store.byId(exceptionId);
        }
        if (ex == null) return null;

        Map<String, Object> w = null;
        try {
            w = waybills.fetch(ex.waybillNo);
        } catch (WaybillGateway.Down ignored) {
            // 只读试算降级：按无运单数据计（insured/fee 取 0）
        }
        double insured = w == null ? 0 : ExJson.jsNumber(w.get("insured"));
        double fee = w == null ? 0 : ExJson.jsNumber(w.get("fee"));
        if (Double.isNaN(insured)) insured = 0;
        if (Double.isNaN(fee)) fee = 0;

        boolean securityPassed = securityPassed(ex.waybillNo);
        String effSeverity = severity != null ? severity : (ex.severity != null ? ex.severity : "中度");
        String effPhotos = photos != null ? photos : (ex.photos != null ? ex.photos : "");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exception", ExJson.exceptionJson(ex));
        out.putAll(ClaimEngine.assessClaim(ex.type, effSeverity, insured, fee, effPhotos, securityPassed));
        return out;
    }

    private boolean securityPassed(String waybillNo) {
        try {
            Map<String, Object> data = ExJson.dataMap(taskClient.securityPassed(waybillNo));
            if (data != null && Boolean.TRUE.equals(data.get("security_passed"))) return true;
        } catch (Exception ignored) {
            // task 不可用时落到 events 兜底口径
        }
        // 原版兜底口径：events 描述含「安检」即视为已过安检（批次 6 起查 track 全量轨迹）
        java.util.List<Map<String, Object>> trackEvents = events.trackEventsOf(waybillNo);
        if (trackEvents != null) {
            for (Map<String, Object> e : trackEvents) {
                if (e.get("desc_text") instanceof String d && d.contains("安检")) return true;
            }
        }
        synchronized (store.lock) {
            for (ExceptionStore.EventRow e : store.eventsOf(waybillNo)) {
                if (e.descText != null && e.descText.contains("安检")) return true;
            }
        }
        return false;
    }
}
