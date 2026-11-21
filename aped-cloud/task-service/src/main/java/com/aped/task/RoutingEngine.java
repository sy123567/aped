package com.aped.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分拨路由（对应 core/routing.js 的 planRoute / suggestForWaybill）：
 * 规则优先级、枢纽命中/降级、ETA 口径逐行照原版，包括两个不显眼的原版口径——
 * ① 降级到异类型枢纽时 mode/mode_label/eta 仍按最初选定的 mode 计算（原版未改 mode 变量）；
 * ② 同城分支的 destCity 取 CITY_OF_PROVINCE[prov]，省份未识别时 prov='未知'、destCity='未知'。
 * 区域划分 classifyZone / provinceOf 是 core/pricing.js 的逻辑，与 rate-service PricingEngine
 * 同源；本服务按既有口径持本地副本（routing_hubs 为本服务独占表，直接读 TaskStore 种子）。
 */
public final class RoutingEngine {

    private RoutingEngine() {}

    /* ---------------- 区域划分（core/pricing.js 同口径副本） ---------------- */

    private static final List<String> PROVINCES = List.of(
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江",
            "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川",
            "贵州", "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆");
    private static final List<String> EAST = List.of("上海", "江苏", "浙江", "安徽", "福建", "江西", "山东");
    private static final List<String> REMOTE = List.of("西藏", "新疆", "青海", "宁夏", "内蒙古", "甘肃");
    private static final String ORIGIN_PROVINCE = "浙江";
    private static final String ORIGIN_CITY = "杭州";

    /** provinceOf：按 PROVINCES 顺序取首个被地址包含的省份，无命中返回 null。 */
    public static String provinceOf(String addr) {
        String a = addr == null ? "" : addr;
        for (String p : PROVINCES) if (a.contains(p)) return p;
        return null;
    }

    public record Zone(String zone, String label) {}

    /** classifyZone：同城 / 省内 / 偏远 / 同大区 / 跨大区（判定序与原版一致）。 */
    public static Zone classifyZone(String addr) {
        String a = addr == null ? "" : addr;
        String p = provinceOf(a);
        if (p == null) return new Zone("cross", "未识别省份，按跨大区计");
        if (p.equals(ORIGIN_PROVINCE) && a.contains(ORIGIN_CITY)) {
            return new Zone("same_city", "同城（" + ORIGIN_CITY + "）");
        }
        if (p.equals(ORIGIN_PROVINCE)) return new Zone("same_province", "省内");
        if (REMOTE.contains(p)) return new Zone("remote", "偏远（" + p + "）");
        if (EAST.contains(p)) return new Zone("region", "同大区（华东·" + p + "）");
        return new Zone("cross", "跨大区（" + p + "）");
    }

    /* ---------------- 分拨路由（core/routing.js planRoute） ---------------- */

    private static final Map<String, String> CITY_OF_PROVINCE = Map.ofEntries(
            Map.entry("浙江", "杭州"), Map.entry("上海", "上海"), Map.entry("北京", "北京"),
            Map.entry("江苏", "南京"), Map.entry("广东", "广州"), Map.entry("四川", "成都"),
            Map.entry("陕西", "西安"), Map.entry("湖北", "武汉"), Map.entry("湖南", "长沙"),
            Map.entry("山东", "济南"), Map.entry("福建", "福州"), Map.entry("安徽", "合肥"));

    private static TaskStore.Hub pickHub(String type, String prov) {
        for (TaskStore.Hub h : TaskStore.HUBS) {
            if (!h.type().equals(type)) continue;
            for (String cov : h.coverage().split(",")) {
                if (cov.equals(prov)) return h;
            }
        }
        return null;
    }

    /** planRoute：fromCity 固定 '杭州'（原版默认值，调用方均未传）。 */
    public static Map<String, Object> planRoute(String destAddr, String product) {
        String dest = destAddr == null ? "" : destAddr;
        String prod = product == null ? "" : product;
        String fromCity = "杭州";
        Zone zone = classifyZone(dest);
        String prov = provinceOf(dest);
        if (prov == null) prov = "未知";
        String destCity = CITY_OF_PROVINCE.getOrDefault(prov, prov);
        List<String> rules = new ArrayList<>();

        // 1. 同城
        if (dest.contains(fromCity)) {
            rules.add("目的地在 " + fromCity + "，同城无需中转");
            return route(zone, "direct", "同城直发", destCity + "目的网点", null, 4,
                    destCity + "-网点-段道", rules);
        }

        // 2. 省内
        if (zone.zone().equals("same_province")) {
            rules.add("省内（" + prov + "），走省内分拨中心陆运");
            return route(zone, "land", "省内陆运", prov + "省内分拨中心", null, 12,
                    prov + "-分拨-网点", rules);
        }

        // 3~6. 跨省
        String mode;
        if (zone.zone().equals("remote")) {
            mode = "land";
            rules.add("目的省份 " + prov + " 属偏远，不排空运，走陆运枢纽");
        } else if (prod.equals("经济件")) {
            mode = "land";
            rules.add("产品为经济件，优先陆运以控制成本");
        } else if (prod.equals("次日达") || prod.equals("隔日达")) {
            mode = "air";
            rules.add("产品为" + prod + "，时效硬约束 → 必须空运");
        } else {
            mode = "air";
            rules.add("未指定产品，默认空运");
        }

        TaskStore.Hub hub = pickHub(mode, prov);
        if (hub == null) {
            String other = mode.equals("air") ? "land" : "air";
            hub = pickHub(other, prov);
            if (hub != null) {
                rules.add((mode.equals("air") ? "空运" : "陆运") + "枢纽不覆盖 " + prov
                        + "，降级为" + (other.equals("air") ? "空运" : "陆运") + "枢纽 " + hub.code());
            }
        }
        if (hub == null) {
            rules.add("无覆盖 " + prov + " 的枢纽，需人工指定路由");
            return route(zone, mode, mode.equals("air") ? "空运" : "陆运",
                    "待人工指定", null, null, null, rules);
        }

        rules.add("命中枢纽 " + hub.code() + "（" + hub.name() + "，"
                + (hub.type().equals("air") ? "空运" : "陆运") + "）");
        // eta 按最初选定的 mode 计（原版口径，降级异类型枢纽时不改）
        long eta = (mode.equals("air") ? 10 : 36) + (zone.zone().equals("remote") ? 36 : 0);
        return route(zone, mode, mode.equals("air") ? "空运" : "陆运",
                hub.name(), hub.code(), eta, hub.code() + "-" + destCity + "-段道", rules);
    }

    private static Map<String, Object> route(Zone zone, String mode, String modeLabel,
                                             String nextHop, String hubCode, Object etaHours,
                                             String threeSegHint, List<String> rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zone", zone.zone());
        m.put("zone_label", zone.label());
        m.put("mode", mode);
        m.put("mode_label", modeLabel);
        m.put("next_hop", nextHop);
        m.put("hub_code", hubCode);
        m.put("eta_hours", etaHours);
        m.put("three_seg_hint", threeSegHint);
        m.put("rules", rules);
        return m;
    }
}
