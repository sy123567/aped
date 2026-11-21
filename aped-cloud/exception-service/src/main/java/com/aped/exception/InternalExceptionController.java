package com.aped.exception;

import java.util.LinkedHashMap;
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
 * 服务间内部接口（exceptions 归 exception-service 独占）：
 * - POST /internal/exceptions（批次 4）：派送异常转异常件的登记，写入方在 task-service。
 *   字段与默认值照原版表结构：status='待处理'、severity 默认 '中度'、photos/closed_at 为空。
 * - GET  /internal/exceptions/{id}（批次 5）：理赔流程读异常单整行。
 * - GET  /internal/exceptions/{id}/assess（批次 5）：定责试算的服务间版本，
 *   claim-service 发起理赔时取核定结果（定责引擎只在本服务一份，避免两处口径漂移）。
 * - POST /internal/exceptions/{id}/status（批次 5）：理赔流程回写异常单
 *   （severity 核定落档、status 待审批/已理赔/待处理流转），对应原版 claim.js 内的 UPDATE exceptions。
 * - POST /internal/events（批次 5）：理赔流程写轨迹事件（理赔受理/驳回/赔付），对应原版 helpers.event()。
 * 内部接口不做用户角色限制（网关层仍要求登录态），与既有批次口径一致。
 */
@RestController
public class InternalExceptionController {

    private final ExceptionStore store;
    private final Assessor assessor;
    private final EventPublisher events;

    public InternalExceptionController(ExceptionStore store, Assessor assessor, EventPublisher events) {
        this.store = store;
        this.assessor = assessor;
        this.events = events;
    }

    /** 异常单全量（批次 6：report-service 运营汇总/异常数据集聚合用），插入序 = id 升序。 */
    @GetMapping("/internal/exceptions")
    public ResponseEntity<?> listAll() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        synchronized (store.lock) {
            for (ExceptionStore.ExceptionRow r : store.exceptions) out.add(ExJson.exceptionJson(r));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    @PostMapping("/internal/exceptions")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        ExceptionStore.ExceptionRow r = new ExceptionStore.ExceptionRow();
        synchronized (store.lock) {
            r.id = store.nextId();
            r.waybillNo = asString(body.get("waybill_no"));
            r.type = asString(body.get("type"));
            r.photos = asString(body.get("photos"));
            r.descText = asString(body.get("desc_text"));
            r.status = "待处理";
            r.createdAt = ExceptionStore.now();
            r.closedAt = null;
            r.severity = "中度";
            store.exceptions.add(r);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", r.id);
        data.put("waybill_no", r.waybillNo);
        data.put("type", r.type);
        data.put("status", r.status);
        data.put("created_at", r.createdAt);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    @GetMapping("/internal/exceptions/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Long eid = ExJson.asLong(id);
        ExceptionStore.ExceptionRow r = null;
        if (eid != null) {
            synchronized (store.lock) {
                r = store.byId(eid);
            }
        }
        if (r == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
        return ResponseEntity.ok(Map.of("ok", true, "data", ExJson.exceptionJson(r)));
    }

    @GetMapping("/internal/exceptions/{id}/assess")
    public ResponseEntity<?> assess(@PathVariable String id,
                                    @RequestParam(name = "severity", required = false) String severity,
                                    @RequestParam(name = "photos", required = false) String photos) {
        Long eid = ExJson.asLong(id);
        Map<String, Object> a = eid == null ? null : assessor.assessForException(eid, severity, photos);
        if (a == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
        return ResponseEntity.ok(Map.of("ok", true, "data", a));
    }

    /** 回写异常单字段：仅接受 status / severity / closed_at 的非 null 值，其余忽略。 */
    @PostMapping("/internal/exceptions/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        Long eid = ExJson.asLong(id);
        synchronized (store.lock) {
            ExceptionStore.ExceptionRow r = eid == null ? null : store.byId(eid);
            if (r == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
            String status = asString(body.get("status"));
            if (status != null) r.status = status;
            String severity = asString(body.get("severity"));
            if (severity != null) r.severity = severity;
            if (body.containsKey("closed_at")) r.closedAt = asString(body.get("closed_at"));
            return ResponseEntity.ok(Map.of("ok", true, "data", ExJson.exceptionJson(r)));
        }
    }

    @PostMapping("/internal/events")
    public ResponseEntity<?> addEvent(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        ExceptionStore.EventRow e;
        synchronized (store.lock) {
            e = store.addEvent(asString(body.get("waybill_no")),
                    asString(body.get("node")), asString(body.get("desc_text")));
            String orderNo = asString(body.get("order_no"));
            if (orderNo != null) e.orderNo = orderNo;
        }
        // 批次 6：同步转发 track-service（全量轨迹收口），best-effort 不影响理赔写链
        events.publish(e.waybillNo, e.node, e.descText);
        return ResponseEntity.ok(Map.of("ok", true, "data", ExJson.eventJson(e)));
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
