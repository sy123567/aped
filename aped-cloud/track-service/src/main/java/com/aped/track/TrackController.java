package com.aped.track;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轨迹服务（对应原版 services/order.js 的 ⑦ 运单全程轨迹查询 + core/helpers.js 的 event() 写入）：
 * - POST /internal/events   各服务写轨迹（计划矩阵：task → track「失败重试、最终一致」；
 *                           order/exception/claim 的事件同路写入），对应原版 helpers.event()。
 * - GET  /internal/events   按运单号/订单号取整行事件（供 exception-service 异常详情拼全量轨迹）。
 * - GET  /tracking/{no}     轨迹查询：支持运单号 / 订单号，返回 {waybill, order, events, on_time}，
 *                           路径/返回/文案与原版逐字一致；客户只能查自己的运单。
 * 运单行经 Feign 取自 billing（其内部 JSON 为原版 waybills 行的超集，与批次 5 详情口径一致），
 * 订单行经 Feign 取自 order（内部接口返回 orders 原行）。事件存储在本服务（events 归 track 独占）。
 */
@RestController
public class TrackController {

    private final TrackStore store;
    private final BillingClient billingClient;
    private final OrderClient orderClient;

    public TrackController(TrackStore store, BillingClient billingClient, OrderClient orderClient) {
        this.store = store;
        this.billingClient = billingClient;
        this.orderClient = orderClient;
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static ResponseEntity<Map<String, Object>> degraded(String service) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", service + "暂不可用（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    /** 下游服务不可用（非业务错误）。 */
    private static final class Down extends RuntimeException {
        final String service;
        Down(String service) { super(service); this.service = service; }
    }

    /* ---------------- 轨迹写入（对应 helpers.event()） ---------------- */

    @PostMapping("/internal/events")
    public ResponseEntity<?> addEvent(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        TrackStore.EventRow e;
        synchronized (store.lock) {
            e = store.addEvent(TrackJson.asString(body.get("waybill_no")),
                    TrackJson.asString(body.get("order_no")),
                    TrackJson.asString(body.get("node")),
                    TrackJson.asString(body.get("desc_text")));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", TrackJson.eventJson(e)));
    }

    /* ---------------- 轨迹整行查询（供异常详情等拼装） ---------------- */

    @GetMapping("/internal/events")
    public ResponseEntity<?> listEvents(
            @RequestParam(name = "waybill_no", required = false) String waybillNo,
            @RequestParam(name = "order_no", required = false) String orderNo) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (TrackStore.EventRow e : store.events) { // 插入序即 id 序，对应 ORDER BY id
                boolean hit = (waybillNo != null && waybillNo.equals(e.waybillNo))
                        || (orderNo != null && orderNo.equals(e.orderNo));
                if (hit) out.add(TrackJson.eventJson(e));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /* ---------------- ⑦ 运单全程轨迹查询 ---------------- */

    @GetMapping("/tracking/{no}")
    public ResponseEntity<?> tracking(@PathVariable String no, HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);

        Map<String, Object> waybill;
        Map<String, Object> order;
        try {
            waybill = fetchWaybill(no);
            order = waybill != null
                    ? fetchOrderById(waybill.get("order_id"))
                    : fetchOrderByNo(no);
        } catch (Down d) {
            return degraded(d.service);
        }
        if (waybill == null && order == null) {
            return fail("未找到该运单号或订单号的跟踪记录", HttpStatus.NOT_FOUND);
        }
        // 客户只能查自己的运单（原版口径：以订单归属判定）
        if ("customer".equals(role) && order != null) {
            Long ownId = headerCustomerId(req);
            Object cid = order.get("customer_id");
            Long orderCust = cid instanceof Number n ? n.longValue() : null;
            if (ownId == null || !ownId.equals(orderCust)) {
                return fail("只能查询自己的运单", HttpStatus.FORBIDDEN);
            }
        }

        String wbKey = waybill != null ? String.valueOf(waybill.get("waybill_no")) : no;
        String orderKey = order != null ? String.valueOf(order.get("order_no")) : no;
        List<Map<String, Object>> events = new ArrayList<>();
        synchronized (store.lock) {
            for (TrackStore.EventRow e : store.events) {
                if (wbKey.equals(e.waybillNo) || orderKey.equals(e.orderNo)) {
                    events.add(TrackJson.eventBrief(e));
                }
            }
        }

        Object onTime = null;
        if (waybill != null) {
            String signedAt = TrackJson.asString(waybill.get("signed_at"));
            String promiseTime = TrackJson.asString(waybill.get("promise_time"));
            if (signedAt != null && promiseTime != null) onTime = signedAt.compareTo(promiseTime) <= 0;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybill", waybill);
        data.put("order", order);
        data.put("events", events);
        data.put("on_time", onTime);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 下游取数 ---------------- */

    private Map<String, Object> fetchWaybill(String no) {
        Map<String, Object> resp;
        try {
            resp = billingClient.getWaybill(no);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("计费服务");
        } catch (Exception e) {
            throw new Down("计费服务");
        }
        Map<String, Object> data = TrackJson.dataMap(resp);
        if (data == null) throw new Down("计费服务");
        return data;
    }

    private Map<String, Object> fetchOrderById(Object idRaw) {
        if (!(idRaw instanceof Number n)) return null;
        Map<String, Object> resp;
        try {
            resp = orderClient.get(n.longValue());
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("订单服务");
        } catch (Exception e) {
            throw new Down("订单服务");
        }
        Map<String, Object> data = TrackJson.dataMap(resp);
        if (data == null) throw new Down("订单服务");
        return data;
    }

    private Map<String, Object> fetchOrderByNo(String orderNo) {
        Map<String, Object> resp;
        try {
            resp = orderClient.getByNo(orderNo);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("订单服务");
        } catch (Exception e) {
            throw new Down("订单服务");
        }
        Map<String, Object> data = TrackJson.dataMap(resp);
        if (data == null) throw new Down("订单服务");
        return data;
    }

    private static Long headerCustomerId(HttpServletRequest req) {
        String cid = req.getHeader("X-User-Customer-Id");
        if (cid == null || cid.isBlank() || "null".equals(cid)) return null;
        try {
            return Long.valueOf(cid);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
