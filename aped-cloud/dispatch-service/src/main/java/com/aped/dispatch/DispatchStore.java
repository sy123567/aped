package com.aped.dispatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 派单服务静态主数据与数值工具（对应原版 db.js 种子中归 dispatch 的部分）：
 * - flights 航班主数据（计划拆分表：flights 归 dispatch-service）；
 * - grids 片区网格（计划拆分表归 customer-service，但其批次 1 未落 grids；
 *   分配引擎判同片区依赖它，本服务先持同种子副本，与 order-service 持 service_areas
 *   副本的既有口径一致，待 customer-service 补 grids 接口后再改走 Feign）；
 * - CAP 与 RULE_SQL 逐字照 core/assign.js。
 */
public final class DispatchStore {

    private DispatchStore() {}

    /** 在途上限（core/assign.js 的 CAP，dispatch.js 的 MAX_INFLIGHT 与之共用）。 */
    public static final int CAP = 20;

    /** 派单 SQL 原文（core/assign.js RULE_SQL，逐字照抄，供规则说明接口展示）。 */
    public static final String RULE_SQL = """
            SELECT c.id
            FROM v_courier_load c
            WHERE c.load < c.capacity
            ORDER BY (c.grid_code = (SELECT g.grid_code FROM grids g
                                     WHERE :addr LIKE '%'||g.district||'%' LIMIT 1)) DESC,
                     c.load ASC, c.id ASC
            LIMIT 1""";

    public record Flight(String flightNo, String dep, String arr, String etd, String atd) {}

    /** 航班种子（db.js 原样；对外列表按 flight_no 排序，同原版 ORDER BY flight_no）。 */
    public static final List<Flight> FLIGHTS = List.of(
            new Flight("MU5101", "杭州萧山", "上海虹桥", "08:20", "09:15"),
            new Flight("CA1832", "杭州萧山", "北京首都", "10:40", "13:05"),
            new Flight("ZH9502", "上海虹桥", "深圳宝安", "14:10", "16:35"));

    public record Grid(String gridCode, String province, String city, String district) {}

    /** 网格种子（db.js 原样，顺序即原版插入顺序——地址匹配取首个命中区县，同 SQL LIMIT 1 的表扫描序）。 */
    public static final List<Grid> GRIDS = List.of(
            new Grid("G-HZ-BJ", "浙江省", "杭州市", "滨江区"),
            new Grid("G-HZ-XH", "浙江省", "杭州市", "西湖区"),
            new Grid("G-HZ-GS", "浙江省", "杭州市", "拱墅区"),
            new Grid("G-SH-PD", "上海市", "上海市", "浦东新区"),
            new Grid("G-SH-XH", "上海市", "上海市", "徐汇区"),
            new Grid("G-BJ-CY", "北京市", "北京市", "朝阳区"),
            new Grid("G-SZ-NS", "广东省", "深圳市", "南山区"));

    /* ---------------- JS 数值语义（与 rate/billing 同口径） ---------------- */

    /** 等价于 JS 的 +v.toFixed(scale)。 */
    static double round(double v, int scale) {
        return new BigDecimal(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** JSON 数值：整数值输出为整数（与 JS JSON.stringify(Number) 一致）。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }
}
