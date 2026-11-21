package com.aped.dispatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 派单分配引擎（对应 core/assign.js）：原版把决策写在 SQL 视图里——
 * 在「未满载（load < capacity）」的快递员中，同片区优先 → 在途少优先 → 工号定序。
 * 微服务版按全量计划把视图改为 Java 查询 + 排序：负载行经 Feign 从 task-service 取
 * （v_courier_load 的数据源 pickup_tasks/employees 归 task-service），排序键、过滤条件、
 * 地址→网格的「首个命中区县」口径与原 SQL 逐项等价，结果必须与原版逐单一致。
 */
@Component
public class AssignEngine {

    /** 候选行（对应 v_courier_load 一行 + same_area 标记，same_area 沿用 SQL 的 1/0）。 */
    public record Cand(long id, String code, String name, String gridCode,
                       long load, long capacity, int sameArea) {}

    /**
     * 地址命中的网格码：对应子查询 (SELECT g.grid_code FROM grids g
     * WHERE :addr LIKE '%'||g.district||'%' LIMIT 1)——按种子顺序取首个「区县被地址包含」的网格，
     * 无命中返回 null（SQL 子查询无行时为 NULL，same_area 恒为 0）。
     */
    public String gridOf(String addr) {
        String a = addr == null ? "" : addr;
        for (DispatchStore.Grid g : DispatchStore.GRIDS) {
            if (a.contains(g.district())) return g.gridCode();
        }
        return null;
    }

    /**
     * 候选排名：对应 candidatesForAddr 的 SELECT_BASE + ORDER BY。
     * loads 为 task-service 的 v_courier_load 等价行（全部 courier，含满载）。
     */
    public List<Cand> candidates(List<Map<String, Object>> loads, String addr, int limit) {
        String grid = gridOf(addr);
        List<Cand> all = new ArrayList<>();
        for (Map<String, Object> r : loads) {
            long load = asLong(r.get("load"));
            long capacity = asLong(r.get("capacity"));
            if (!(load < capacity)) continue; // WHERE c.load < c.capacity（硬约束）
            String gc = asString(r.get("grid_code"));
            int same = grid != null && grid.equals(gc) ? 1 : 0;
            all.add(new Cand(asLong(r.get("id")), asString(r.get("code")), asString(r.get("name")),
                    gc, load, capacity, same));
        }
        // ORDER BY same_area DESC, c.load ASC, c.id ASC
        all.sort(Comparator.comparingInt(Cand::sameArea).reversed()
                .thenComparingLong(Cand::load)
                .thenComparingLong(Cand::id));
        if (limit >= 0 && all.size() > limit) return new ArrayList<>(all.subList(0, limit));
        return all;
    }

    /** 定人：候选第一名（对应 pickCourierForAddr），无可用运力返回 null。 */
    public Cand pick(List<Map<String, Object>> loads, String addr) {
        List<Cand> list = candidates(loads, addr, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 把选人结果翻成一句人话（对应 reasonOf）。 */
    public static String reasonOf(Cand c) {
        if (c == null) return "无可用运力（全部满载）";
        return c.sameArea() == 1
                ? "同片区（" + c.gridCode() + "），在途 " + c.load() + "/" + c.capacity()
                : "跨区调度，在途 " + c.load() + "/" + c.capacity() + "（最低）";
    }

    /** 候选行转 JSON 形态（字段与原版 candidatesForAddr 的行一致，same_area 为 1/0）。 */
    public static Map<String, Object> candJson(Cand c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", c.id());
        m.put("code", c.code());
        m.put("name", c.name());
        m.put("grid_code", c.gridCode());
        m.put("load", c.load());
        m.put("capacity", c.capacity());
        m.put("same_area", c.sameArea());
        return m;
    }

    static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
