package com.aped.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** 报表服务通用小工具：入参取值、下游响应拆包、JS 数值/字符串语义。 */
final class ReportJson {

    private ReportJson() {}

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

    static double jsNumber(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
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

    /** 等价于 JS 的 +v.toFixed(n)（对精确二进制值四舍五入，与前批次同口径）。 */
    static double round(double v, int scale) {
        return new BigDecimal(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    static double round1(double v) { return round(v, 1); }
    static double round2(double v) { return round(v, 2); }
    static double round3(double v) { return round(v, 3); }

    /** JSON 数值：整数值输出为整数（与 JS JSON.stringify(Number) 一致）。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    /** JS String(v) 形态（CSV 单元格用）：null/undefined → 空串由调用方处理。 */
    static String jsStr(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        if (v instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(v);
    }
}
