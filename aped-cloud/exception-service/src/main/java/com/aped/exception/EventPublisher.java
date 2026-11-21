package com.aped.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 轨迹事件转发（批次 6 收口）：本服务的异常登记/异常关闭/理赔受理/理赔驳回/理赔赔付事件
 * 原先只落本服务本地日志（批次 5 口径），本批次起在本地落账后同步转发 track-service，
 * 使 track 持有与原版 events 表等价的全量轨迹。转发 best-effort：track 不可用时静默跳过，
 * 本地日志仍在（异常详情与安检兜底可降级读本地）。
 */
@Component
public class EventPublisher {

    private final TrackClient trackClient;

    public EventPublisher(TrackClient trackClient) {
        this.trackClient = trackClient;
    }

    /** 转发一条事件到 track-service（waybill 维度，order_no 同原版 claim.js 口径为空）。 */
    public void publish(String waybillNo, String node, String descText) {
        try {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("waybill_no", waybillNo);
            e.put("order_no", null);
            e.put("node", node);
            e.put("desc_text", descText);
            trackClient.addEvent(e);
        } catch (Exception ignored) {
            // best-effort：不阻断异常/理赔主流程
        }
    }

    /** 从 track 读某运单全量轨迹（整行）；track 不可用返回 null（调用方降级读本地日志）。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> trackEventsOf(String waybillNo) {
        try {
            Map<String, Object> resp = trackClient.listEvents(waybillNo);
            if (resp != null && Boolean.TRUE.equals(resp.get("ok")) && resp.get("data") instanceof List<?> l) {
                return (List<Map<String, Object>>) l;
            }
        } catch (Exception ignored) {
            // 落到本地日志兜底
        }
        return null;
    }
}
