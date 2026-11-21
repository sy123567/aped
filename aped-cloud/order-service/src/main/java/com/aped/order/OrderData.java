package com.aped.order;

import java.util.List;

/**
 * 下单依赖的主数据内存副本（种子与原版 db.js 逐字一致；落库留待后续批次）：
 * - 客户月结标记：下单/试算时查客户协议（原版 order→customers 表查询；
 *   全量计划中 order→customer 为 Feign 同步，本批次先用同种子内存副本，
 *   与批次 1 auth/customer 的内存种子口径一致）。
 * - 服务范围表：对应 helpers.js checkArea() 的判定数据。
 */
public final class OrderData {

    public record CustomerInfo(long id, String name, int monthly) {}

    private static final List<CustomerInfo> CUSTOMERS = List.of(
            new CustomerInfo(1, "张三", 0),
            new CustomerInfo(2, "李四", 1),
            new CustomerInfo(3, "王五", 1));

    public static CustomerInfo customer(Long id) {
        if (id == null) return null;
        for (CustomerInfo c : CUSTOMERS) if (c.id() == id) return c;
        return null;
    }

    /* ---------------- 服务范围（service_areas 种子 + checkArea 翻译） ---------------- */

    private record Area(String province, String city, String district, int supported) {}

    private static final List<Area> AREAS = List.of(
            new Area("浙江省", "杭州市", "滨江区", 1),
            new Area("浙江省", "杭州市", "西湖区", 1),
            new Area("浙江省", "杭州市", "拱墅区", 1),
            new Area("上海市", "上海市", "浦东新区", 1),
            new Area("上海市", "上海市", "徐汇区", 1),
            new Area("北京市", "北京市", "朝阳区", 1),
            new Area("广东省", "深圳市", "南山区", 1),
            new Area("西藏自治区", "那曲市", "安多县", 0));

    /** 对应原版 SELECT * FROM service_areas ORDER BY id（id 为种子序 1..n，supported 为 0/1 整数）。 */
    public static java.util.List<java.util.Map<String, Object>> areas() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        long id = 1;
        for (Area a : AREAS) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id++);
            m.put("province", a.province());
            m.put("city", a.city());
            m.put("district", a.district());
            m.put("supported", a.supported());
            out.add(m);
        }
        return out;
    }

    public record AreaResult(boolean supported, boolean checked, String note) {}

    /** 对应 helpers.js checkArea()：按表序首个“区县或城市被地址包含”的行命中（含原版命中口径）。 */
    public static AreaResult checkArea(String addr) {
        if (addr == null || addr.isEmpty()) return new AreaResult(true, false, "");
        Area hit = null;
        for (Area a : AREAS) {
            if (addr.contains(a.district()) || addr.contains(a.city())) { hit = a; break; }
        }
        if (hit != null && hit.supported() == 0) {
            return new AreaResult(false, true,
                    hit.city() + hit.district() + " 暂不支持上门揽收，建议就近网点自寄");
        }
        return new AreaResult(true, hit != null,
                hit != null ? hit.city() + hit.district() + " 在服务范围内" : "");
    }
}
