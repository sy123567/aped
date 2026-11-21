package com.aped.track;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 轨迹服务通用小工具：入参取值、下游响应拆包、事件行 JSON、JS 数字形态。 */
final class TrackJson {

    private TrackJson() {}

    static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> dataMap(Map<String, Object> resp) {
        if (resp != null && Boolean.TRUE.equals(resp.get("ok")) && resp.get("data") instanceof Map<?, ?> d) {
            return (Map<String, Object>) d;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> dataList(Map<String, Object> resp) {
        if (resp != null && Boolean.TRUE.equals(resp.get("ok")) && resp.get("data") instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return null;
    }

    /** 事件整行（对应原版 SELECT * FROM events 的列序）。 */
    static Map<String, Object> eventJson(TrackStore.EventRow e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("waybill_no", e.waybillNo);
        m.put("order_no", e.orderNo);
        m.put("node", e.node);
        m.put("desc_text", e.descText);
        m.put("created_at", e.createdAt);
        return m;
    }

    /** 轨迹查询的事件投影（原版只取 node/desc_text/created_at 三列）。 */
    static Map<String, Object> eventBrief(TrackStore.EventRow e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", e.node);
        m.put("desc_text", e.descText);
        m.put("created_at", e.createdAt);
        return m;
    }
}
