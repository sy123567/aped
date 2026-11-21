package com.aped.claim;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** claim-service 内部小工具：行→JSON、JS 语义转换、Feign 响应/错误提取（与既有服务同口径）。 */
final class ClaimJson {

    private ClaimJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 理赔单整行（对应 SELECT * FROM claims：建表列序 + 迁移追加的 evidence）。 */
    static Map<String, Object> claimJson(ClaimStore.ClaimRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("exception_id", c.exceptionId);
        m.put("amount", ClaimStore.num(c.amount));
        m.put("rule", c.rule);
        m.put("status", c.status);
        m.put("approved_by", c.approvedBy);
        m.put("paid_at", c.paidAt);
        m.put("evidence", c.evidence);
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

    /** JS 真值判定（!!v）：null/false/0/NaN/"" 为假，其余（含字符串 "false"）为真。 */
    static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }

    /** 等价 JS 的 +v.toFixed(2)。 */
    static double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 等价 JS 的 v.toFixed(2) 字符串。 */
    static String fixed2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** JS 模板串里 ${number} 的表示：整数不带小数点，其余最短往返表示。 */
    static String jsNum(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
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
