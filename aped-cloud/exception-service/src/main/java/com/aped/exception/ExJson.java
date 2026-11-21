package com.aped.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** exception-service 内部小工具：行→JSON、入参转换、Feign 响应/错误提取（与 task-service 同口径）。 */
final class ExJson {

    private ExJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 异常单整行（对应 SELECT * FROM exceptions：建表列序 + 迁移追加的 closed_at/severity）。 */
    static Map<String, Object> exceptionJson(ExceptionStore.ExceptionRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("waybill_no", r.waybillNo);
        m.put("type", r.type);
        m.put("photos", r.photos);
        m.put("desc_text", r.descText);
        m.put("status", r.status);
        m.put("created_at", r.createdAt);
        m.put("closed_at", r.closedAt);
        m.put("severity", r.severity);
        return m;
    }

    /** 轨迹事件整行（对应 SELECT * FROM events）。 */
    static Map<String, Object> eventJson(ExceptionStore.EventRow e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("waybill_no", e.waybillNo);
        m.put("order_no", e.orderNo);
        m.put("node", e.node);
        m.put("desc_text", e.descText);
        m.put("created_at", e.createdAt);
        return m;
    }

    static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** JS Number()：Number/字符串数字 → 数值，其余 → NaN；空串 → 0（同 JS）。 */
    static double jsNumber(Object o) {
        if (o == null) return Double.NaN;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return 0;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    /** 从 {ok,data} 响应里取 data 对象；形态不对返回 null。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> dataMap(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) return null;
        return resp.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : null;
    }

    /** 从 {ok,data} 响应里取 data 数组；形态不对返回 null。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> dataList(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) return null;
        if (!(resp.get("data") instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** 从 Feign 异常体里取下游的 error 文案（下游响应为 {ok:false,error} 时）。 */
    static String feignError(FeignException e) {
        try {
            String body = e.contentUTF8();
            if (body == null) return null;
            var node = MAPPER.readTree(body);
            return node.hasNonNull("error") ? node.get("error").asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
