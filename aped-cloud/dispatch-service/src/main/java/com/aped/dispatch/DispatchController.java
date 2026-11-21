package com.aped.dispatch;

import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 派单服务（对应 services/dispatch.js 的「分配部分」+ core/assign.js）：
 * - GET /flights            航班主数据（本服务独占表）
 * - GET /dispatch/rule      分配规则说明（规则 SQL 原文展示）
 * - GET /dispatch/capacity  运力看板：快递员负载经 Feign 取 task-service，待派单数取 order-service
 * - GET /pickup/candidates  揽收候选：待派单走「视图排名」形态，其余状态退化为按地址直排（同原版）
 * - /internal/assign/*      供 task-service 定人（pick/candidates），对应计划矩阵 task → dispatch
 * 角色门在网关（揽派前缀 dispatcher/courier，admin 恒过）；本服务只校验登录态存在，
 * 与 order/billing 的服务内口径一致。原版 dispatch.js 对 courier 无任务归属过滤，本服务照原版不自创。
 */
@RestController
public class DispatchController {

    private final AssignEngine engine;
    private final TaskClient taskClient;
    private final OrderClient orderClient;

    public DispatchController(AssignEngine engine, TaskClient taskClient, OrderClient orderClient) {
        this.engine = engine;
        this.taskClient = taskClient;
        this.orderClient = orderClient;
    }

    /* ---------------- 基础响应/身份 ---------------- */

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static ResponseEntity<Map<String, Object>> degraded(String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", what + "暂不可用，暂时无法派单（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    private static boolean loggedIn(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        return role != null && !role.isBlank();
    }

    /* ---------------- 负载行（Feign → task-service） ---------------- */

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> courierLoads() {
        Map<String, Object> resp;
        try {
            resp = taskClient.courierLoads();
        } catch (Exception e) {
            return null;
        }
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))
                || !(resp.get("data") instanceof List<?> list)) {
            return null;
        }
        return (List<Map<String, Object>>) list;
    }

    private Long statLong(SupplierMap call, String key) {
        Map<String, Object> resp;
        try {
            resp = call.get();
        } catch (Exception e) {
            return null;
        }
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))
                || !(resp.get("data") instanceof Map<?, ?> data)
                || !(data.get(key) instanceof Number n)) {
            return null;
        }
        return n.longValue();
    }

    @FunctionalInterface
    private interface SupplierMap {
        Map<String, Object> get();
    }

    /* ---------------- 航班主数据 ---------------- */

    @GetMapping("/flights")
    public ResponseEntity<?> flights(HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<Map<String, Object>> out = new ArrayList<>();
        List<DispatchStore.Flight> sorted = new ArrayList<>(DispatchStore.FLIGHTS);
        sorted.sort((a, b) -> a.flightNo().compareTo(b.flightNo())); // ORDER BY flight_no
        for (DispatchStore.Flight f : sorted) out.add(flightJson(f));
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    private static Map<String, Object> flightJson(DispatchStore.Flight f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flight_no", f.flightNo());
        m.put("dep", f.dep());
        m.put("arr", f.arr());
        m.put("etd", f.etd());
        m.put("atd", f.atd());
        return m;
    }

    @GetMapping("/internal/flights/{flightNo}")
    public ResponseEntity<?> internalFlight(@PathVariable String flightNo) {
        for (DispatchStore.Flight f : DispatchStore.FLIGHTS) {
            if (f.flightNo().equals(flightNo)) {
                return ResponseEntity.ok(Map.of("ok", true, "data", flightJson(f)));
            }
        }
        return fail("航班不存在", HttpStatus.NOT_FOUND);
    }

    /* ---------------- 分配规则说明 ---------------- */

    @GetMapping("/dispatch/rule")
    public ResponseEntity<?> rule(HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "同片区优先 · 负载最低优先");
        data.put("summary", "在未满载的快递员里，先看是不是他的片区，再比谁手上单少，最后按工号定序。");
        data.put("capacity", DispatchStore.CAP);
        data.put("sql", DispatchStore.RULE_SQL);
        data.put("steps", List.of(
                "① 只取在途 < 20 单的快递员（硬约束）",
                "② 订单地址按「区县 → 网格码」匹配，同片区排前面",
                "③ 同片区里挑在途最少的；都不匹配片区就挑全局在途最少的",
                "④ 派完立刻落库，下一单重新查，负载自然递增，不会堆给同一个人"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 运力看板 ---------------- */

    @GetMapping("/dispatch/capacity")
    public ResponseEntity<?> capacity(HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<Map<String, Object>> loads = courierLoads();
        if (loads == null) return degraded("任务服务");
        Long pending = statLong(() -> orderClient.stats(), "pending");
        if (pending == null) return degraded("订单服务");
        Long todayAssigned = statLong(() -> taskClient.taskStats(), "today_assigned");
        if (todayAssigned == null) return degraded("任务服务");

        List<Map<String, Object>> couriers = new ArrayList<>();
        for (Map<String, Object> r : loads) {
            long load = AssignEngine.asLong(r.get("load"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", AssignEngine.asLong(r.get("id")));
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            m.put("grid_code", r.get("grid_code"));
            m.put("load", load);
            m.put("capacity", DispatchStore.CAP);
            m.put("full", load >= DispatchStore.CAP);
            // util = +(load / CAP * 100).toFixed(0)
            m.put("util", DispatchStore.num(DispatchStore.round(load * 100.0 / DispatchStore.CAP, 0)));
            couriers.add(m);
        }
        // .sort((a, b) => b.load - a.load)：JS Array.sort 稳定排序，同负载保持原（id）序
        couriers.sort((a, b) -> Long.compare((Long) b.get("load"), (Long) a.get("load")));

        long totalCap = (long) couriers.size() * DispatchStore.CAP;
        long totalLoad = couriers.stream().mapToLong(c -> (Long) c.get("load")).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("couriers", couriers);
        data.put("pending_orders", pending);
        data.put("today_assigned", todayAssigned);
        data.put("total_capacity", totalCap);
        data.put("total_load", totalLoad);
        // utilization = totalCap ? +(totalLoad / totalCap * 100).toFixed(1) : 0
        data.put("utilization", totalCap == 0 ? 0
                : DispatchStore.num(DispatchStore.round(totalLoad * 100.0 / totalCap, 1)));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 揽收候选（展示口径，照原版两分支） ---------------- */

    @GetMapping("/pickup/candidates")
    public ResponseEntity<?> pickupCandidates(@RequestParam(value = "order_id", required = false) Long orderId,
                                              HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> order = null;
        if (orderId != null) {
            try {
                Map<String, Object> resp = orderClient.get(orderId);
                if (resp != null && Boolean.TRUE.equals(resp.get("ok"))
                        && resp.get("data") instanceof Map<?, ?> d) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dd = (Map<String, Object>) d;
                    order = dd;
                }
            } catch (FeignException e) {
                if (e.status() != 404) return degraded("订单服务");
            } catch (Exception e) {
                return degraded("订单服务");
            }
        }
        if (order == null) return fail("订单不存在", HttpStatus.NOT_FOUND);

        List<Map<String, Object>> loads = courierLoads();
        if (loads == null) return degraded("任务服务");
        String addr = AssignEngine.asString(order.get("recv_addr"));
        // 待派单走视图（含排名 rn，无 LIMIT）；其他状态退化为 candidatesForAddr（LIMIT 10）
        boolean viewBranch = "待派单".equals(order.get("status"));
        List<AssignEngine.Cand> cands = engine.candidates(loads, addr == null ? "" : addr, viewBranch ? -1 : 10);
        if (viewBranch && cands.isEmpty()) viewBranch = false; // 视图无行时原版退化为直排分支

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < cands.size(); i++) {
            AssignEngine.Cand c = cands.get(i);
            Map<String, Object> m = AssignEngine.candJson(c);
            if (viewBranch) m.put("same_area", c.sameArea() == 1); // 视图分支原版 !!c.same_area
            m.put("rank", i + 1);
            m.put("reason", AssignEngine.reasonOf(c));
            list.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.get("order_no"));
        data.put("recv_addr", order.get("recv_addr"));
        data.put("slot", order.get("slot"));
        data.put("order_grid", list.isEmpty() ? null : list.get(0).get("grid_code"));
        data.put("capacity", DispatchStore.CAP);
        data.put("candidates", list);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 内部定人接口（task → dispatch） ---------------- */

    @GetMapping("/internal/assign/candidates")
    public ResponseEntity<?> internalCandidates(@RequestParam("addr") String addr,
                                                @RequestParam(value = "limit", required = false) Integer limit) {
        List<Map<String, Object>> loads = courierLoads();
        if (loads == null) return degraded("任务服务");
        List<AssignEngine.Cand> cands = engine.candidates(loads, addr, limit == null ? 10 : limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AssignEngine.Cand c : cands) {
            Map<String, Object> m = AssignEngine.candJson(c);
            m.put("reason", AssignEngine.reasonOf(c));
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @GetMapping("/internal/assign/pick")
    public ResponseEntity<?> internalPick(@RequestParam("addr") String addr) {
        List<Map<String, Object>> loads = courierLoads();
        if (loads == null) return degraded("任务服务");
        AssignEngine.Cand c = engine.pick(loads, addr);
        Map<String, Object> data = null;
        if (c != null) {
            data = AssignEngine.candJson(c);
            data.put("reason", AssignEngine.reasonOf(c));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
