package com.aped.task;

import feign.FeignException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口（批次 6 补，pickup_tasks/delivery_tasks/transit_logs/employees 归 task 独占）：
 * - POST /internal/pickup-tasks/rollback-by-order  订单取消联动：把该订单「待接单」的揽收任务
 *   置「已回滚」（rollback_reason 由调用方给，原版为 '订单取消'），对应原版 cancel 事务内 UPDATE。
 * - GET  /internal/kpi-rows     绩效原始聚合行：对应 core/kpi.js 的 rawRows() 五个子查询
 *   （揽收/签收/派送异常计数在本服务本地；准时计数需 JOIN waybills 的 promise_time/signed_at，
 *   经 billing 内部接口取运单行后在本服务内完成 JOIN 等价计算）。评分/归一化在 report-service。
 * - GET  /internal/transit-logs 中转登记全量（含操作员名拼装），供 report 的 transit 数据集。
 * - GET  /internal/board-stats  工作台的今日揽收/今日签收计数（原版 /board 的两条 date() 口径）。
 * 内部接口不做用户角色限制（网关层仍要求登录态），与既有批次口径一致。
 */
@RestController
public class InternalTaskDataController {

    private final TaskStore store;
    private final BillingClient billingClient;

    public InternalTaskDataController(TaskStore store, BillingClient billingClient) {
        this.store = store;
        this.billingClient = billingClient;
    }

    private static ResponseEntity<Map<String, Object>> degraded(String service) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", service + "暂不可用（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    /* ---------------- 订单取消联动回滚 ---------------- */

    @PostMapping("/internal/pickup-tasks/rollback-by-order")
    public ResponseEntity<?> rollbackByOrder(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        Long orderId = body.get("order_id") instanceof Number n ? n.longValue() : null;
        String reason = body.get("reason") instanceof String s ? s : "订单取消";
        int rolled = 0;
        if (orderId != null) {
            synchronized (store.lock) {
                for (TaskStore.PickupTask t : store.pickupTasks) {
                    if (t.orderId == orderId && "待接单".equals(t.status)) {
                        t.status = "已回滚";
                        t.rollbackReason = reason;
                        rolled++;
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of("rolled_back", rolled)));
    }

    /* ---------------- 绩效原始聚合行（kpi.js rawRows 等价） ---------------- */

    @GetMapping("/internal/kpi-rows")
    public ResponseEntity<?> kpiRows() {
        // 先快照任务行，避免持锁做 Feign 调用
        List<TaskStore.PickupTask> pickups;
        List<TaskStore.DeliveryTask> deliveries;
        synchronized (store.lock) {
            pickups = new ArrayList<>(store.pickupTasks);
            deliveries = new ArrayList<>(store.deliveryTasks);
        }
        // 已签收派件任务涉及的运单行（promise_time/signed_at）经 billing 取，缓存复用
        Map<String, Map<String, Object>> wbCache = new HashMap<>();
        try {
            for (TaskStore.DeliveryTask d : deliveries) {
                if (!"已签收".equals(d.status)) continue;
                if (!wbCache.containsKey(d.waybillNo)) wbCache.put(d.waybillNo, fetchWaybill(d.waybillNo));
            }
        } catch (RuntimeException e) {
            return degraded("计费服务");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskStore.Employee e : TaskStore.EMPLOYEES) { // 原版 FROM employees WHERE role='courier'（表序=id 序）
            if (!"courier".equals(e.role())) continue;
            long pk = 0, dv = 0, ex = 0, measured = 0, onTime = 0;
            for (TaskStore.PickupTask t : pickups) {
                if (t.courierId != null && t.courierId == e.id() && "已揽收".equals(t.status)) pk++;
            }
            for (TaskStore.DeliveryTask d : deliveries) {
                if (d.courierId == null || d.courierId != e.id()) continue;
                if ("已签收".equals(d.status)) {
                    dv++;
                    Map<String, Object> w = wbCache.get(d.waybillNo);
                    String promise = w == null ? null : asString(w.get("promise_time"));
                    String signed = w == null ? null : asString(w.get("signed_at"));
                    if (promise != null && signed != null) {
                        measured++;
                        if (signed.compareTo(promise) <= 0) onTime++;
                    }
                } else if ("派送异常".equals(d.status)) {
                    ex++;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("code", e.code());
            m.put("courier", e.name());
            m.put("grid_code", e.gridCode());
            m.put("pickups", pk);
            m.put("deliveries", dv);
            m.put("exceptions", ex);
            m.put("measured", measured);
            m.put("on_time_cnt", onTime);
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /** 取运单行：不存在返回 null；服务异常抛 RuntimeException（调用方转降级）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchWaybill(String no) {
        Map<String, Object> resp;
        try {
            resp = billingClient.getWaybill(no);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new RuntimeException(e);
        }
        if (resp != null && Boolean.TRUE.equals(resp.get("ok")) && resp.get("data") instanceof Map<?, ?> d) {
            return (Map<String, Object>) d;
        }
        throw new RuntimeException("billing 响应异常");
    }

    /* ---------------- 中转登记全量（report transit 数据集） ---------------- */

    @GetMapping("/internal/transit-logs")
    public ResponseEntity<?> transitLogs() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (TaskStore.TransitLog t : store.transitLogs) { // 插入序=id 升序，排序由消费方按需处理
                TaskStore.Employee e = TaskStore.employeeById(t.operatorId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id);
                m.put("waybill_no", t.waybillNo);
                m.put("node", t.node);
                m.put("next_hop", t.nextHop);
                m.put("flight", t.flight);
                m.put("operator_id", t.operatorId);
                m.put("security", t.security);
                m.put("created_at", t.createdAt);
                m.put("operator", e == null ? null : e.name());
                out.add(m);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /* ---------------- 工作台今日计数 ---------------- */

    @GetMapping("/internal/board-stats")
    public ResponseEntity<?> boardStats() {
        String today = LocalDate.now().toString(); // date(x)=date('now','localtime') 的日期部分
        long todayPickups = 0, todaySigns = 0;
        synchronized (store.lock) {
            for (TaskStore.PickupTask t : store.pickupTasks) {
                if ("已揽收".equals(t.status) && t.assignedAt != null
                        && t.assignedAt.length() >= 10 && t.assignedAt.substring(0, 10).equals(today)) {
                    todayPickups++;
                }
            }
            for (TaskStore.DeliveryTask t : store.deliveryTasks) {
                if ("已签收".equals(t.status) && t.signedAt != null
                        && t.signedAt.length() >= 10 && t.signedAt.substring(0, 10).equals(today)) {
                    todaySigns++;
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("today_pickups", todayPickups);
        data.put("today_signs", todaySigns);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
